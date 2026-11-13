package mcport.legacypackfix.glsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites shaders written for 1.21.6 - 1.21.11 (GLSL 150, #moj_import, implicit interface
 * locations) into what 26.3's ShaderC/SPIR-V pipeline accepts. Shaders of 1.21.5 and older get their loose
 * uniforms from the uniform blocks and the old fog and lightmap helpers back; what still does not compile falls
 * back per pipeline.
 */
public final class LegacyTranslator {
   public enum Stage {
      VERTEX,
      FRAGMENT
   }

   /** Varying locations shared by the two stages of one program, plus the native stage they must match. */
   public record VaryingPlan(Map<String, Integer> locations, Guards nativeCounterpart) {
   }

   public record Branch(String condition, String body) {
   }

   /**
    * What the planner knows about the shader files of the packs as a whole.
    *
    * @param text             the packs' include files, searched before a declaration of a stage is called unused
    * @param functions        old vanilla helper functions ({@link #definedHelpers}) some file of the packs defines
    *                         itself; they are not supplied a second time
    * @param standInFunctions by include id (fog.glsl, light.glsl): those of them that only the packs' own copy of
    *                         that include defines. Where our stand-in takes the place of that include they are
    *                         supplied after all
    * @param besideStandIn    the includes (fog.glsl, light.glsl) of which the packs carry an older copy that shares
    *                         no name with our stand-in: there the copy is read after the stand-in, for its functions
    * @param engineBlocks     the uniform blocks of the 26.3 includes: name to members, as 26.3 declares them
    */
   public record Pack(
      String text, Set<String> functions, Map<String, Set<String>> standInFunctions, Set<String> besideStandIn, Map<String, String> engineBlocks
   ) {
      public static final Pack NONE = new Pack("", Set.of(), Map.of(), Set.of(), Map.of());
   }

   /** Whether an older copy of fog.glsl or light.glsl can be read after our stand-in for it: they define nothing in common. */
   public static boolean disjoint(String copy, String standIn) {
      String a = Glsl.splitTopLevel(Glsl.stripComments(Glsl.normalize(copy)));
      String b = Glsl.splitTopLevel(Glsl.stripComments(Glsl.normalize(standIn)));
      if (!Glsl.uniformBlocks(a).isEmpty()) {
         return false;
      }

      Set<String> names = new HashSet<>(Glsl.functionTypes(b).keySet());
      Matcher macro = VALUE_MACRO.matcher(b);
      while (macro.find()) {
         names.add(macro.group(1));
      }

      for (String name : Glsl.functionTypes(a).keySet()) {
         if (names.contains(name)) {
            return false;
         }
      }

      macro = VALUE_MACRO.matcher(a);
      while (macro.find()) {
         if (names.contains(macro.group(1))) {
            return false;
         }
      }

      return true;
   }

   /**
    * What the planner knows about a stage beyond its own text.
    *
    * @param postVertex     the stage runs as the vertex shader of post effect passes (no vertex buffer, no Projection block)
    * @param postGroups     for a stage of post effect passes: the uniform blocks every such pass feeds; null otherwise
    * @param foldedLightmap the stage is one half of the 1.21.x item shader pair, whose vertex color carried the lightmap;
    *                       the other half is a 26.3 shader, where lightmap and overlay travel on their own
    * @param otherStage     guards of the other stage of the 26.3 shader this stage stands in for (may be null)
    */
   public record Traits(boolean postVertex, Set<String> postGroups, boolean foldedLightmap, Guards otherStage, Pack pack) {
      public static final Traits NONE = new Traits(false, null, false, null, Pack.NONE);
   }

   public static final String HEADER = "#version 330\n#extension GL_ARB_separate_shader_objects : require\n";

   /**
    * Includes that declare uniform blocks some pipelines do not bind; we ship stand-ins for them. Globals is not
    * one of them: every pipeline a pack shader can reach binds it, and a stand-in froze GameTime and ScreenSize.
    */
   public static final Set<String> STUBBED_INCLUDES = Set.of("minecraft:fog.glsl", "minecraft:light.glsl");
   /** 26.3 feeds these vertex attributes as integers (overlay and lightmap coordinates) in every vertex format. */
   private static final Set<String> INTEGER_ATTRIBUTES = Set.of("UV1", "UV2");

   private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\b.*$", Pattern.MULTILINE);
   private static final Pattern VERSION_NUMBER = Pattern.compile("#\\s*version\\s+(\\d+)");
   private static final Pattern SSO_EXT = Pattern.compile("^\\s*#\\s*extension\\s+GL_ARB_separate_shader_objects\\b.*$", Pattern.MULTILINE);
   private static final Pattern EXTENSION = Pattern.compile("^\\s*#\\s*extension\\b.*$", Pattern.MULTILINE);
   private static final Pattern ANY_MOJ_IMPORT = Pattern.compile("#\\s*moj_import\\b");
   private static final Pattern MOJ_IMPORT = Pattern.compile("^(\\s*)#\\s*moj_import\\s*([<\"])([^>\"]+)[>\"]");
   private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*<([^>]+)>");
   private static final Pattern SAMPLER_DECL = Pattern.compile("^\\s*uniform\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?(\\w*sampler\\w*)\\s+(\\w+)\\s*;\\s*$");
   private static final Pattern COLORISH = Pattern.compile("(?i)colou?r");
   private static final Pattern LOOSE_UNIFORM = Pattern.compile("^\\s*uniform\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?(\\w+)\\s+(\\w+)\\s*;\\s*$");
   /** Names GLSL 150 had and Vulkan GLSL lacks; an include that uses one was written for an older game. */
   private static final Pattern OLD_BUILTIN = Pattern.compile("\\bgl_VertexID\\b|\\bgl_InstanceID\\b|\\btexture2D(?:Lod)?\\s*\\(");
   /** Words Vulkan GLSL reserves that GLSL 150 left free for variables. */
   private static final Pattern RESERVED = Pattern.compile("\\b(sampler|samplerShadow)\\b");
   /** A macro with a value (an include guard has none). */
   private static final Pattern VALUE_MACRO = Pattern.compile("(?m)^[ \\t]*#[ \\t]*define[ \\t]+(\\w+)(?:\\([^)]*\\))?[ \\t]+\\S.*$");
   /** The #ifndef X / #define X pair that opens a vanilla include. */
   private static final Pattern INCLUDE_GUARD = Pattern.compile("#\\s*ifndef\\s+(\\w+)\\s*\\n\\s*#\\s*define\\s+\\1\\b");
   private static final Pattern IF_DIRECTIVE = Pattern.compile("^#\\s*if(?:def|ndef)?\\b");
   private static final Pattern ELSE_DIRECTIVE = Pattern.compile("^#\\s*(?:else|elif)\\b");
   private static final Pattern ENDIF_DIRECTIVE = Pattern.compile("^#\\s*endif\\b");
   /** Loose uniforms of older shaders that 26.3 serves from a uniform block, and the include declaring it. */
   private static final Map<String, String> BLOCK_MEMBERS = Map.ofEntries(
      Map.entry("ProjMat", "minecraft:projection.glsl"),
      Map.entry("ModelViewMat", "minecraft:dynamictransforms.glsl"),
      Map.entry("TextureMat", "minecraft:dynamictransforms.glsl"),
      Map.entry("ColorModulator", "minecraft:dynamictransforms.glsl"),
      Map.entry("ModelOffset", "minecraft:dynamictransforms.glsl"),
      Map.entry("FogColor", "minecraft:fog.glsl"),
      Map.entry("GameTime", "minecraft:globals.glsl"),
      Map.entry("ScreenSize", "minecraft:globals.glsl"),
      Map.entry("GlintAlpha", "minecraft:globals.glsl"),
      Map.entry("Light0_Direction", "minecraft:light.glsl"),
      Map.entry("Light1_Direction", "minecraft:light.glsl")
   );
   /**
    * Up to 1.21.5 there was one fog: that of the surroundings (water, lava, blindness, a foggy dimension) measured
    * as a sphere, or else the fade at the render distance measured as a cylinder. 26.3 carries both at once; the
    * old uniforms read the one the old game would have picked. With no Fog block (stand-in) both lie at infinity.
    */
   private static final String SURROUNDINGS_FOG = "(FogEnvironmentalEnd < FogRenderDistanceEnd)";
   private static final Map<String, String> OLD_FOG = Map.of(
      "FogStart", "(" + SURROUNDINGS_FOG + " ? FogEnvironmentalStart : FogRenderDistanceStart)",
      "FogEnd", "(" + SURROUNDINGS_FOG + " ? FogEnvironmentalEnd : FogRenderDistanceEnd)",
      "FogShape", "(" + SURROUNDINGS_FOG + " ? 0 : 1)"
   );
   /** Uniforms of older shaders that 26.3 hands over as a define of the pipeline (the layers of the end portal). */
   private static final Map<String, String> OLD_DEFINES = Map.of("EndPortalLayers", "PORTAL_LAYERS");
   /** Helpers of fog.glsl and light.glsl up to 1.21.5 that 26.3 dropped, as they were; the lightmap one reads texel centers like 26.3's. */
   private static final Map<String, String> OLD_HELPERS = new LinkedHashMap<>();

   static {
      OLD_HELPERS.put("linear_fog", """
         #ifndef LPF_OLD_LINEAR_FOG
         #define LPF_OLD_LINEAR_FOG
         vec4 linear_fog(vec4 inColor, float vertexDistance, float fogStart, float fogEnd, vec4 fogColor) {
             if (vertexDistance <= fogStart) {
                 return inColor;
             }

             float fogValue = vertexDistance < fogEnd ? smoothstep(fogStart, fogEnd, vertexDistance) : 1.0;
             return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
         }
         #endif
         """);
      OLD_HELPERS.put("linear_fog_fade", """
         #ifndef LPF_OLD_LINEAR_FOG_FADE
         #define LPF_OLD_LINEAR_FOG_FADE
         float linear_fog_fade(float vertexDistance, float fogStart, float fogEnd) {
             if (vertexDistance <= fogStart) {
                 return 1.0;
             } else if (vertexDistance >= fogEnd) {
                 return 0.0;
             }

             return smoothstep(fogEnd, fogStart, vertexDistance);
         }
         #endif
         """);
      OLD_HELPERS.put("fog_distance", """
         #ifndef LPF_OLD_FOG_DISTANCE
         #define LPF_OLD_FOG_DISTANCE
         float fog_distance(vec3 pos, int shape) {
             if (shape == 0) {
                 return length(pos);
             } else {
                 float distXZ = length(pos.xz);
                 float distY = abs(pos.y);
                 return max(distXZ, distY);
             }
         }

         float fog_distance(mat4 modelViewMat, vec3 pos, int shape) {
             if (shape == 0) {
                 return length((modelViewMat * vec4(pos, 1.0)).xyz);
             } else {
                 float distXZ = length((modelViewMat * vec4(pos.x, 0.0, pos.z, 1.0)).xyz);
                 float distY = length((modelViewMat * vec4(0.0, pos.y, 0.0, 1.0)).xyz);
                 return max(distXZ, distY);
             }
         }
         #endif
         """);
      OLD_HELPERS.put("minecraft_sample_lightmap", """
         #ifndef LPF_OLD_SAMPLE_LIGHTMAP
         #define LPF_OLD_SAMPLE_LIGHTMAP
         vec4 minecraft_sample_lightmap(sampler2D lightMap, ivec2 uv) {
             return texture(lightMap, clamp((uv / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
         }
         #endif
         """);
   }

   private LegacyTranslator() {
   }

   // ------------------------------------------------------------------ detection

   public static boolean isLegacyShader(String src) {
      String s = Glsl.splitTopLevel(Glsl.stripComments(Glsl.normalize(src)));
      if (ANY_MOJ_IMPORT.matcher(s).find()) {
         return true;
      }

      Matcher v = VERSION_NUMBER.matcher(s);
      if (v.find() && Integer.parseInt(v.group(1)) < 330) {
         return true;
      }

      int depth = 0;
      for (String line : s.split("\n")) {
         if (depth == 0 && !line.trim().startsWith("#")) {
            for (Glsl.Decl d : Glsl.parseInterface(line)) {
               if (d.location() < 0) {
                  return true;
               }
            }
         }

         depth = Math.max(0, depth + Glsl.braceDelta(line));
      }

      return false;
   }

   public static boolean isLegacyInclude(String src) {
      String s = Glsl.splitTopLevel(Glsl.stripComments(Glsl.normalize(src)));
      // helper files often carry neither #version nor an import; what gives them away is a name only the old GLSL had
      return ANY_MOJ_IMPORT.matcher(s).find() || VERSION.matcher(s).find() || OLD_BUILTIN.matcher(s).find();
   }

   /** The old vanilla helper functions ({@link #OLD_HELPERS}) this pack text defines itself. */
   public static Set<String> definedHelpers(String src) {
      Set<String> out = new HashSet<>();
      Map<String, String> own = null;
      for (String name : OLD_HELPERS.keySet()) {
         if (!src.contains(name)) {
            continue;
         }

         if (own == null) {
            own = Glsl.functionTypes(Glsl.splitTopLevel(Glsl.stripComments(Glsl.normalize(src))));
         }

         if (own.containsKey(name)) {
            out.add(name);
         }
      }

      return out;
   }

   // ------------------------------------------------------------------ includes

   /**
    * relative: maps a quoted #moj_import path to an include id, or null when it cannot be found.
    * #moj_import put a file in once however often it was named; a plain #include repeats it, so the body is guarded.
    */
   public static String translateInclude(String src, String id, Function<String, String> relative) {
      return translateInclude(src, id, relative, Pack.NONE, null);
   }

   /**
    * replaced: the text of the 26.3 include this one takes the place of, or null. The game's own shaders read that
    * include too, and so do the pack's old shaders for the uniforms they declared loose: the uniform blocks, macros
    * and functions it has and the old copy lacks are added (BetterHud ships empty globals.glsl and
    * dynamictransforms.glsl for the versions that had none).
    */
   public static String translateInclude(
      String src, String id, Function<String, String> relative, Pack pack, String replaced
   ) {
      String stripped = engineBlocks(Glsl.stripComments(Glsl.normalize(src)), pack.engineBlocks()::get);
      String s = renameReserved(Glsl.splitTopLevel(stripped));
      s = VERSION.matcher(s).replaceAll("");
      s = SSO_EXT.matcher(s).replaceAll("");
      String guard = "LPF_INC_" + id.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(java.util.Locale.ROOT) + "_GLSL";
      StringBuilder before = new StringBuilder();
      StringBuilder after = new StringBuilder();
      String baseGuard = null;
      if (replaced != null) {
         String base = Glsl.stripComments(Glsl.normalize(replaced));
         Matcher baseGuards = INCLUDE_GUARD.matcher(base);
         baseGuard = baseGuards.find() ? baseGuards.group(1) : null;
         Set<String> blocks = new HashSet<>();
         Glsl.uniformBlocks(stripped).forEach(block -> blocks.add(block.name()));
         for (Glsl.Block block : Glsl.uniformBlocks(base)) {
            if (!blocks.contains(block.name())) {
               before.append(base, block.start(), block.end()).append('\n');
            }
         }

         Matcher macro = VALUE_MACRO.matcher(base);
         while (macro.find()) {
            if (!Pattern.compile("#\\s*define\\s+" + Pattern.quote(macro.group(1)) + "\\b").matcher(s).find()) {
               before.append(macro.group().strip()).append('\n');
            }
         }

         Map<String, String> own = Glsl.functionTypes(s);
         for (Glsl.Function function : Glsl.functionDefinitions(base)) {
            if (!own.containsKey(function.name())) {
               after.append(function.text()).append('\n');
            }
         }
      }

      StringBuilder body = new StringBuilder();
      boolean adds = !before.isEmpty() || !after.isEmpty();
      if (adds) {
         // not where our stand-in for this include came first: that has stood in for all of it already
         body.append(baseGuard == null ? "" : "#ifndef " + baseGuard + "\n#define " + baseGuard + "\n")
            .append("#define ").append(guard).append("_ADDS\n")
            .append(before)
            .append(baseGuard == null ? "" : "#endif\n");
      }

      for (String line : s.split("\n", -1)) {
         body.append(rewriteImport(line, relative)).append('\n');
      }

      if (adds) {
         body.append("#ifdef ").append(guard).append("_ADDS\n").append(after).append("#endif\n");
      }

      String text = renameBuiltins(body.toString());
      return "#ifndef " + guard + "\n#define " + guard + '\n' + oldHelpers(text, pack.functions()) + text + "#endif\n";
   }

   private static String rewriteImport(String line, Function<String, String> relative) {
      Matcher m = MOJ_IMPORT.matcher(line);
      if (!m.find()) {
         return line;
      }

      String target = m.group(3).trim();
      if (m.group(2).equals("\"")) {
         String resolved = relative == null ? null : relative.apply(target);
         return m.group(1) + (resolved != null ? "#include <" + resolved + ">" : "#include \"" + target + "\"");
      }

      if (!target.contains(":")) {
         target = "minecraft:" + target;
      }

      return m.group(1) + "#include <" + target + ">";
   }

   private static String renameBuiltins(String s) {
      return s.replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex")
         .replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex")
         .replaceAll("\\btexture2DLod\\s*\\(", "textureLod(")
         .replaceAll("\\btexture2D\\s*\\(", "texture(");
   }

   /** `sampler` is a type in Vulkan GLSL; older shaders name variables so. */
   private static String renameReserved(String s) {
      return s.contains("sampler") ? RESERVED.matcher(s).replaceAll("lpf_$1") : s;
   }

   /** Definitions of the old vanilla helpers the text calls and nothing of the pack defines. */
   private static String oldHelpers(String text, Set<String> packFunctions) {
      StringBuilder out = new StringBuilder();
      Map<String, String> own = null;
      for (Map.Entry<String, String> helper : OLD_HELPERS.entrySet()) {
         String name = helper.getKey();
         if (!text.contains(name) || packFunctions.contains(name) || !Glsl.calls(text, name)) {
            continue;
         }

         if (own == null) {
            own = Glsl.functionTypes(text);
         }

         if (!own.containsKey(name)) {
            out.append(helper.getValue());
         }
      }

      return out.toString();
   }

   // ------------------------------------------------------------------ varyings

   /**
    * Assigns interface locations for one program. Names the native stage already places keep its
    * locations; the rest are packed after them in declaration order. With an old vertex stage only what
    * it writes gets a location: an input nothing writes cannot be declared at all.
    */
   public static VaryingPlan planVaryings(String legacyVertex, String legacyFragment, Guards nativeCounterpart, boolean counterpartIsVertex) {
      Map<String, Integer> reserved = new LinkedHashMap<>();
      int next = 0;
      if (nativeCounterpart != null) {
         Map<String, Integer> locs = counterpartIsVertex ? nativeCounterpart.outputLocations : nativeCounterpart.inputLocations;
         reserved.putAll(locs);
         for (Map.Entry<String, Integer> e : locs.entrySet()) {
            next = Math.max(next, e.getValue() + nativeCounterpart.sizes.getOrDefault(e.getKey(), 1));
         }
      }

      Map<String, Integer> locations = new LinkedHashMap<>(reserved);
      List<Glsl.Decl> decls = new ArrayList<>();
      if (legacyVertex != null) {
         decls.addAll(interfaceDecls(legacyVertex, "out"));
      } else if (legacyFragment != null) {
         decls.addAll(interfaceDecls(legacyFragment, "in"));
      }

      for (Glsl.Decl d : decls) {
         if (!locations.containsKey(d.name())) {
            locations.put(d.name(), next);
            next += Glsl.locationSize(d.type(), d.arraySize());
         }
      }

      // the native stage's own location when there is one, otherwise a free one both translated stages share
      locations.putIfAbsent(GLINT_VARYING, next);
      return new VaryingPlan(locations, nativeCounterpart);
   }

   /**
    * Where an old vertex stage puts what it writes when nothing but fixed locations (name to location, of the
    * 26.3 fragment shaders it runs with) constrains it: those names keep their place, the rest follow them.
    */
   public static Map<String, Integer> outputLocations(String legacyVertex, Map<String, Integer> fixed) {
      Map<String, Integer> locations = new LinkedHashMap<>();
      int next = 0;
      for (int location : fixed.values()) {
         next = Math.max(next, location + 1);
      }

      for (Glsl.Decl d : interfaceDecls(legacyVertex, "out")) {
         if (locations.containsKey(d.name())) {
            continue;
         }

         Integer place = fixed.get(d.name());
         if (place != null) {
            locations.put(d.name(), place);
         } else {
            locations.put(d.name(), next);
            next += Glsl.locationSize(d.type(), d.arraySize());
         }
      }

      return locations;
   }

   private static List<Glsl.Decl> interfaceDecls(String src, String direction) {
      List<Glsl.Decl> out = new ArrayList<>();
      Map<String, Integer> constants = new HashMap<>();
      int depth = 0;
      for (String line : Glsl.splitTopLevel(Glsl.stripComments(Glsl.normalize(src))).split("\n")) {
         if (line.trim().startsWith("#")) {
            Glsl.noteConstant(line, constants);
            continue;
         }

         if (depth == 0) {
            Glsl.noteConstant(line, constants);
            for (Glsl.Decl d : Glsl.parseInterface(line, constants)) {
               if (d.direction().equals(direction)) {
                  out.add(d);
               }
            }
         }

         depth = Math.max(0, depth + Glsl.braceDelta(line));
      }

      return out;
   }

   // ------------------------------------------------------------------ stages

   /** One stage while it is rewritten. */
   private static final class Unit {
      final String source;
      final Stage stage;
      final VaryingPlan plan;
      final Guards vanillaTarget;
      final Traits traits;
      /** The stage outside its own declarations, and the pack's includes: where a name must occur to count as used. Null: anything may be. */
      final String uses;
      /**
       * By interface kind: every declaration of it carries its own layout(location). Such an interface is left as
       * the pack wrote it; one that mixes placed and unplaced declarations is numbered anew as a whole.
       */
      final boolean[] placedByPack = new boolean[3];
      /** There is exactly one main() to put code around. */
      final boolean canWrap;
      /** An old vertex stage in front of a 26.3 fragment shader that reads one color per face ({@link #fillNativeInputs}). */
      final boolean perFace;
      final StringBuilder wrapDeclarations = new StringBuilder();
      final StringBuilder wrapBefore = new StringBuilder();
      final StringBuilder wrapStatements = new StringBuilder();
      final Set<String> attributes = new HashSet<>();
      /** Vertex stage: the varyings it declares, with their types. */
      final Map<String, String> written = new HashMap<>();
      /** Includes that are in for every variant already (not inside a conditional of the pack). */
      final Set<String> included = new HashSet<>();
      int conditionals;
      int nextAttribute;
      String colorOutput;
      /** Fragment stage standing in for the 1.21.x item shader: the condition the overlay arrives under ("" for always), or null. */
      String overlayCondition;
      /** The Normal attribute is read through the face sign. */
      boolean facedNormal;
      /** An old vertex stage with one fog distance in front of a 26.3 fragment shader that reads both ({@link #fillNativeInputs}). */
      final boolean oneDistance;
      /** FogShape is a variable the new main() sets. */
      boolean shapeVariable;
      boolean samplerInfo;
      boolean blurConfig;

      Unit(String source, Stage stage, VaryingPlan plan, Guards vanillaTarget, Traits traits) {
         this.source = source;
         this.stage = stage;
         this.plan = plan;
         this.vanillaTarget = vanillaTarget;
         this.traits = traits;
         this.uses = survey(source, stage, traits.pack().text(), this.placedByPack);
         Matcher main = MAIN.matcher(source);
         this.canWrap = main.find() && !main.find();
         Guards fragment = stage == Stage.VERTEX ? plan.nativeCounterpart() : null;
         this.perFace = fragment != null
            && this.canWrap
            && fragment.inputLocations.containsKey(FACE_BACK)
            && fragment.inputLocations.containsKey(FACE_FRONT)
            && java.util.Objects.equals(fragment.inputs.get(FACE_BACK), fragment.inputs.get(FACE_FRONT))
            && !Glsl.mentions(source, FACE_BACK)
            && !Glsl.mentions(source, FACE_FRONT)
            && Glsl.mentions(source, VERTEX_COLOR);
         this.oneDistance = fragment != null
            && this.canWrap
            && fragment.inputLocations.containsKey(SPHERICAL_DISTANCE)
            && fragment.inputLocations.containsKey(CYLINDRICAL_DISTANCE)
            && java.util.Objects.equals(fragment.inputs.get(SPHERICAL_DISTANCE), fragment.inputs.get(CYLINDRICAL_DISTANCE))
            && !Glsl.mentions(source, SPHERICAL_DISTANCE)
            && !Glsl.mentions(source, CYLINDRICAL_DISTANCE)
            && Glsl.mentions(source, OLD_DISTANCE);
      }

      boolean used(String name) {
         return this.uses == null || Glsl.mentions(this.uses, name);
      }
   }

   private static final int ATTRIBUTES = 0;
   private static final int FRAGMENT_OUTPUTS = 1;
   private static final int VARYINGS = 2;

   private static int interfaceKind(Stage stage, Glsl.Decl d) {
      boolean in = d.direction().equals("in");
      return stage == Stage.VERTEX && in ? ATTRIBUTES : stage == Stage.FRAGMENT && !in ? FRAGMENT_OUTPUTS : VARYINGS;
   }

   /**
    * Reads the stage once before it is rewritten: fills in which interfaces are placed by the pack throughout, and
    * returns the text outside the stage's own declarations plus the pack's includes (null when token pasting may
    * spell names that are written nowhere).
    */
   private static String survey(String source, Stage stage, String packText, boolean[] placedByPack) {
      StringBuilder out = new StringBuilder(source.length() + packText.length());
      Map<String, Integer> constants = new HashMap<>();
      int[] placed = new int[3];
      int[] unplaced = new int[3];
      int depth = 0;
      for (String line : source.split("\n", -1)) {
         if (line.trim().startsWith("#")) {
            Glsl.noteConstant(line, constants);
            out.append(line).append('\n');
            continue;
         }

         if (depth == 0) {
            Glsl.noteConstant(line, constants);
            List<Glsl.Decl> decls = Glsl.parseInterface(line, constants);
            for (Glsl.Decl d : decls) {
               (d.location() >= 0 ? placed : unplaced)[interfaceKind(stage, d)]++;
            }

            if (!decls.isEmpty() || SAMPLER_DECL.matcher(line).find()) {
               continue;
            }
         }

         out.append(line).append('\n');
         depth = Math.max(0, depth + Glsl.braceDelta(line));
      }

      for (int kind = 0; kind < placedByPack.length; kind++) {
         placedByPack[kind] = placed[kind] > 0 && unplaced[kind] == 0;
      }

      // token pasting can spell a name that is written nowhere
      return source.contains("##") ? null : out.append(packText).toString();
   }

   /** Next free fragment output; the arms of a preprocessor conditional exclude each other, so each starts where the conditional did. */
   private static final class FragmentOutputs {
      int next;
      private final Deque<int[]> open = new ArrayDeque<>();

      void directive(String trimmed) {
         if (IF_DIRECTIVE.matcher(trimmed).find()) {
            this.open.push(new int[]{this.next, this.next});
         } else if (!this.open.isEmpty() && ELSE_DIRECTIVE.matcher(trimmed).find()) {
            int[] frame = this.open.peek();
            frame[1] = Math.max(frame[1], this.next);
            this.next = frame[0];
         } else if (!this.open.isEmpty() && ENDIF_DIRECTIVE.matcher(trimmed).find()) {
            this.next = Math.max(this.open.pop()[1], this.next);
         }
      }
   }

   public static String translateBody(String src, Stage stage, VaryingPlan plan, Guards vanillaTarget, Function<String, String> relative) {
      return translateBody(src, stage, plan, vanillaTarget, relative, Traits.NONE);
   }

   /**
    * Translates one legacy stage into a 26.3 body (no #version header).
    *
    * @param vanillaTarget guards of the 26.3 vanilla shader this stage stands in for (may be null);
    *                      samplers, attributes and includes vanilla only declares for some pipeline
    *                      variants get the same guard, with stand-ins for the other variants.
    */
   public static String translateBody(String src, Stage stage, VaryingPlan plan, Guards vanillaTarget, Function<String, String> relative, Traits traits) {
      // the blocks of the 26.3 includes, and for a shader of the world or the GUI also those the 26.3 shader declares itself
      // (gui, position_tex_color, lightmap); the blocks of a post shader are laid out by its post effect file
      boolean post = traits.postVertex() || traits.postGroups() != null;
      Function<String, String> blocks = name -> {
         String members = traits.pack().engineBlocks().get(name);
         return members != null || post || vanillaTarget == null ? members : vanillaTarget.blocks.get(name);
      };
      String s = renameReserved(Glsl.splitUniforms(Glsl.splitTopLevel(engineBlocks(Glsl.stripComments(Glsl.normalize(src)), blocks))));
      s = VERSION.matcher(s).replaceAll("");
      s = SSO_EXT.matcher(s).replaceAll("");
      Unit unit = new Unit(s, stage, plan, vanillaTarget, traits);
      Map<String, String[]> guardedSamplers = new LinkedHashMap<>();
      Set<String> integerAttributes = new HashSet<>();
      Map<String, Integer> constants = new HashMap<>();
      FragmentOutputs fragmentOutputs = new FragmentOutputs();
      StringBuilder out = new StringBuilder();
      int depth = 0;
      for (String rawLine : s.split("\n", -1)) {
         String line = rewriteImport(rawLine, relative);
         Matcher inc = INCLUDE.matcher(line);
         if (inc.find()) {
            out.append(include(line, inc.group(1), unit)).append('\n');
            continue;
         }

         String trimmed = line.trim();
         if (trimmed.startsWith("#")) {
            Glsl.noteConstant(line, constants);
            fragmentOutputs.directive(trimmed);
            if (IF_DIRECTIVE.matcher(trimmed).find()) {
               unit.conditionals++;
            } else if (ENDIF_DIRECTIVE.matcher(trimmed).find()) {
               unit.conditionals = Math.max(0, unit.conditionals - 1);
            }

            out.append(line).append('\n');
            continue;
         }

         if (depth == 0) {
            Glsl.noteConstant(line, constants);
            Matcher sm = SAMPLER_DECL.matcher(line);
            if (sm.find()) {
               String name = sm.group(2);
               if (!unit.used(name)) {
                  // declared and never read: all it can do is fail the pipelines that have no such sampler
                  continue;
               }

               String cond = samplerCondition(name, unit);
               if (cond != null) {
                  guardedSamplers.put(name, new String[]{sm.group(1), cond});
                  out.append(samplerMark(name));
                  continue;
               }
            }

            // Vulkan GLSL has no loose non-opaque uniforms: block members come from their (guarded) include,
            // uniforms 26.3 no longer feeds (ChunkOffset, ...) become zero constants
            Matcher um = LOOSE_UNIFORM.matcher(line);
            if (um.find() && !um.group(1).contains("sampler") && !um.group(1).contains("image")) {
               out.append(looseUniform(um.group(1), um.group(2), unit)).append('\n');
               continue;
            }

            List<Glsl.Decl> decls = Glsl.parseInterface(line, constants);
            if (!decls.isEmpty() && !(decls.get(0).location() >= 0 && unit.placedByPack[interfaceKind(stage, decls.get(0))])) {
               for (Glsl.Decl d : decls) {
                  if (stage == Stage.VERTEX && d.direction().equals("in")) {
                     if (traits.postVertex() && d.name().equals("Position") && d.arraySize() == 0 && POST_POSITION.containsKey(d.type())) {
                        // a post pass draws one triangle over the target without a vertex buffer; this is its corner, as the old quad's was
                        out.append("#define Position ").append(POST_POSITION.get(d.type())).append('\n');
                        continue;
                     }

                     String cond = vanillaTarget == null ? null : vanillaTarget.inputs.get(d.name());
                     if ((vanillaTarget == null || !vanillaTarget.inputs.containsKey(d.name())) && !unit.used(d.name())) {
                        // never read, and not an attribute the 26.3 shader has: the vertex format may not carry it
                        out.append(standIn(d));
                        continue;
                     }

                     unit.attributes.add(d.name());
                     if (INTEGER_ATTRIBUTES.contains(d.name()) && d.type().equals("vec2") && d.arraySize() == 0) {
                        // older vanilla shaders (and every pack made from them) declare these as floats; the attribute
                        // is found by name, so the name stays, the type becomes what the vertex format carries,
                        // and the body reads it through a conversion
                        Glsl.Decl integer = new Glsl.Decl(d.direction(), d.qualifiers(), "ivec2", ATTRIBUTE_MARK + d.name(), 0, d.location());
                        out.append(guarded(cond, declare(integer, unit.nextAttribute), standIn(integer)));
                        integerAttributes.add(d.name());
                     } else if (unit.perFace && !unit.facedNormal && d.name().equals(NORMAL) && d.type().equals("vec3") && d.arraySize() == 0) {
                        // read through the face sign, so the old main() can also light the face that looks away
                        Glsl.Decl marked = new Glsl.Decl(d.direction(), d.qualifiers(), d.type(), ATTRIBUTE_MARK + d.name(), 0, d.location());
                        out.append(guarded(cond, declare(marked, unit.nextAttribute), standIn(marked)))
                           .append(guarded(
                              unit.plan.nativeCounterpart().inputs.get(FACE_BACK),
                              "float lpf_face = 1.0;\n#define " + FACED_NORMAL + " (lpf_face * " + marked.name() + ")\n",
                              "#define " + FACED_NORMAL + " " + marked.name() + "\n"
                           ));
                        unit.facedNormal = true;
                     } else {
                        out.append(guarded(cond, declare(d, unit.nextAttribute), standIn(d)));
                     }

                     unit.nextAttribute += Glsl.locationSize(d.type(), d.arraySize());
                  } else if (stage == Stage.FRAGMENT && d.direction().equals("out")) {
                     if (unit.colorOutput == null && d.type().equals("vec4") && d.arraySize() == 0) {
                        unit.colorOutput = d.name();
                     }

                     // the location a pack wrote names the color attachment; it stays
                     int location = d.location() >= 0 ? d.location() : fragmentOutputs.next;
                     out.append(declare(d, location)).append('\n');
                     fragmentOutputs.next = Math.max(fragmentOutputs.next, location + Glsl.locationSize(d.type(), d.arraySize()));
                  } else {
                     if (stage == Stage.VERTEX) {
                        unit.written.put(d.name(), d.arraySize() == 0 ? d.type() : d.type() + "[]");
                     }

                     out.append(varying(d, unit));
                  }
               }

               continue;
            }
         }

         out.append(line).append('\n');
         depth = Math.max(0, depth + Glsl.braceDelta(line));
      }

      String body = renameBuiltins(out.toString());
      Map<String, String> functionTypes = guardedSamplers.isEmpty() ? Map.of() : Glsl.functionTypes(body);
      Map<String, String> samplerBlocks = new LinkedHashMap<>();
      for (Map.Entry<String, String[]> sampler : guardedSamplers.entrySet()) {
         String name = sampler.getKey();
         Map<String, String> present = new LinkedHashMap<>();
         Map<String, String> absent = new LinkedHashMap<>();
         body = rewriteSamplerCalls(body, name);
         body = rewriteSamplerArguments(body, name, sampler.getValue()[0], functionTypes, present, absent);
         samplerBlocks.put(name, guardedSampler(sampler.getValue()[0], name, sampler.getValue()[1], present.values(), absent.values()));
      }

      for (Map.Entry<String, String> block : samplerBlocks.entrySet()) {
         body = body.replace(samplerMark(block.getKey()), block.getValue());
      }

      for (String attribute : integerAttributes) {
         body = body.replaceAll("\\b" + attribute + "\\b", "vec2(" + attribute + ")").replace(ATTRIBUTE_MARK + attribute, attribute);
      }

      if (unit.facedNormal) {
         body = body.replaceAll("\\b" + NORMAL + "\\b", FACED_NORMAL).replace(ATTRIBUTE_MARK + NORMAL, NORMAL);
      }

      String formerUniforms = formerUniformAttributes(unit);
      fillNativeInputs(unit);
      overlay(unit);
      glint(unit, body, unit.nextAttribute);
      body = wrapMain(unit, body);
      return inFront(body, oldHelpers(body, traits.pack().functions()) + formerUniforms);
   }

   /** What 1.21.6 - 1.21.8 fed in the DynamicTransforms block and 26.3 feeds per vertex. */
   private static final Set<String> FORMER_BLOCK_MEMBERS = Set.of("LineWidth");
   private static final Pattern DECLARATION_OF = Pattern.compile("\\b(?:float|int|uint|bool|[biu]?vec[234]|mat[234])\\s+(\\w+)\\b");

   /**
    * An old vertex stage that reads such a value without declaring it (it came with the include) gets the attribute
    * the 26.3 shader reads it from. A stage that declares the name in some way of its own is left alone.
    */
   private static String formerUniformAttributes(Unit unit) {
      Guards vanilla = unit.vanillaTarget;
      if (unit.stage != Stage.VERTEX || vanilla == null) {
         return "";
      }

      StringBuilder out = new StringBuilder();
      for (String name : FORMER_BLOCK_MEMBERS) {
         String type = vanilla.inputTypes.get(name);
         if (type == null || unit.attributes.contains(name) || !Glsl.mentions(unit.source, name)) {
            continue;
         }

         boolean declared = false;
         Matcher declaration = DECLARATION_OF.matcher(unit.source);
         while (!declared && declaration.find()) {
            declared = declaration.group(1).equals(name);
         }

         if (!declared) {
            Glsl.Decl d = new Glsl.Decl("in", "", type, name, 0, -1);
            out.append(guarded(vanilla.inputs.get(name), declare(d, unit.nextAttribute), standIn(d)));
            unit.nextAttribute += Glsl.locationSize(type, 0);
            unit.attributes.add(name);
         }
      }

      return out.toString();
   }

   /**
    * The engine writes its uniform blocks in the member order of 26.3. A block of such a name that an older shader
    * declares itself (1.21.8's gui and position_tex_color shaders do, and packs that carry copies of the includes)
    * would read other bytes than it names, so it is declared the way 26.3 declares it. A member 26.3 dropped is then
    * gone: a shader that reads it does not compile and falls back, instead of drawing with the wrong numbers.
    */
   private static String engineBlocks(String src, Function<String, String> canonical) {
      List<Glsl.Block> blocks = Glsl.uniformBlocks(src);
      StringBuilder out = null;
      int pos = 0;
      for (Glsl.Block block : blocks) {
         String members = canonical.apply(block.name());
         if (members == null || members.replaceAll("\\s+", " ").trim().equals(block.members().replaceAll("\\s+", " ").trim())) {
            continue;
         }

         if (out == null) {
            out = new StringBuilder(src.length() + 64);
         }

         out.append(src, pos, block.start())
            .append("layout(std140) uniform ").append(block.name()).append(" {").append(members).append('}')
            .append(block.instance().isEmpty() ? "" : " " + block.instance()).append(';');
         pos = block.end();
      }

      return out == null ? src : out.append(src, pos, src.length()).toString();
   }

   private static final String GLINT_VARYING = "texCoordGlint";
   private static final String NORMAL = "Normal";
   private static final String FACED_NORMAL = "LPF_FACED_NORMAL";
   private static final String VERTEX_COLOR = "vertexColor";
   private static final String FACE_FRONT = "vertexPerFaceColorFront";
   private static final String FACE_BACK = "vertexPerFaceColorBack";
   private static final String LIGHTMAP_COLOR = "lightMapColor";
   private static final String OLD_DISTANCE = "vertexDistance";
   private static final String SPHERICAL_DISTANCE = "sphericalVertexDistance";
   private static final String CYLINDRICAL_DISTANCE = "cylindricalVertexDistance";
   private static final String OVERLAY_COLOR = "overlayColor";
   private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
   /** Stands for a guarded sampler's declaration until the calls on it are known; in it the name is not a word of its own. */
   private static String samplerMark(String name) {
      return "#LPF_SAMPLER_" + name + "_\n";
   }

   /** The corner of the triangle a 26.3 post pass draws, by the type the old shader declared its quad corner with. */
   private static final Map<String, String> POST_POSITION = Map.of(
      "vec2", "vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2)",
      "vec3", "vec3((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2, 0.0)",
      "vec4", "vec4((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2, 0.0, 1.0)"
   );

   /** Puts declarations in front of a body, after any #extension line (those must come before code). */
   private static String inFront(String body, String helpers) {
      if (helpers.isEmpty()) {
         return body;
      }

      int at = 0;
      Matcher extension = EXTENSION.matcher(body);
      while (extension.find()) {
         at = Math.min(body.length(), extension.end() + 1);
      }

      return body.substring(0, at) + helpers + body.substring(at);
   }

   /**
    * Code some stages need around the old main(): the old main() is kept under another name and a new one calls it.
    * Left alone when there is no single main().
    */
   private static String wrapMain(Unit unit, String body) {
      if (unit.wrapStatements.isEmpty() || !unit.canWrap) {
         return body;
      }

      Matcher main = MAIN.matcher(body);
      if (!main.find()) {
         return body;
      }

      int start = main.start();
      int end = main.end();
      if (main.find()) {
         return body;
      }

      return body.substring(0, start) + "void lpf_legacy_main()" + body.substring(end) + '\n'
         + unit.wrapDeclarations
         + "void main() {\n"
         + unit.wrapBefore
         + "    lpf_legacy_main();\n"
         + unit.wrapStatements
         + "}\n";
   }

   /**
    * 1.21.x drew the enchantment glint as a second pass with its own shader; 26.3 adds it inside the entity and
    * item shaders, in the pipeline variants that define GLINT. An older shader knows nothing of that, so enchanted
    * items and armor lost their glint. Where the 26.3 shader this stage stands in for has the glint, a new main()
    * adds what vanilla adds. Left alone when the shape is not what is expected (no single main, no UV0, no color
    * output); a variant that does not compile falls back as usual.
    */
   private static void glint(Unit unit, String body, int nextAttribute) {
      Guards vanillaTarget = unit.vanillaTarget;
      if (vanillaTarget == null || !unit.canWrap || body.contains(GLINT_VARYING)) {
         return;
      }

      Integer location = unit.plan.locations().get(GLINT_VARYING);
      if (location == null) {
         return;
      }

      if (unit.stage == Stage.FRAGMENT) {
         String colorOutput = unit.colorOutput;
         if (!vanillaTarget.samplers.containsKey("GlintSampler") || colorOutput == null) {
            return;
         }

         String cond = vanillaTarget.samplers.get("GlintSampler");
         cond = cond == null ? "1" : cond;
         unit.wrapDeclarations.append("#if ").append(cond).append('\n')
            .append("#include <minecraft:globals.glsl>\n")
            .append("uniform sampler2D GlintSampler;\n")
            .append("layout(location = ").append(location).append(") in vec2 ").append(GLINT_VARYING).append(";\n")
            .append("#endif\n");
         unit.wrapStatements.append("#if ").append(cond).append('\n')
            .append("    vec4 lpf_glint = GlintAlpha * texture(GlintSampler, ").append(GLINT_VARYING).append(");\n")
            .append("    ").append(colorOutput).append(".rgb += lpf_glint.rgb * lpf_glint.rgb;\n")
            .append("    ").append(colorOutput).append(".a = max(").append(colorOutput).append(".a, GlintAlpha);\n")
            .append("#endif\n");
      } else {
         if (!vanillaTarget.outputs.containsKey(GLINT_VARYING) || !unit.attributes.contains("UV0")) {
            return;
         }

         String cond = vanillaTarget.outputs.get(GLINT_VARYING);
         cond = cond == null ? "1" : cond;
         // some item variants carry the glint coordinates in an attribute of their own
         String special = unit.attributes.contains("UV3") ? null : vanillaTarget.inputs.get("UV3");
         unit.wrapDeclarations.append("#if ").append(cond).append('\n')
            .append("#include <minecraft:dynamictransforms.glsl>\n")
            .append("layout(location = ").append(location).append(") out vec2 ").append(GLINT_VARYING).append(";\n");
         if (special != null) {
            unit.wrapDeclarations.append("#if ").append(special).append('\n')
               .append("layout(location = ").append(nextAttribute).append(") in vec2 UV3;\n")
               .append("#endif\n");
         }

         unit.wrapDeclarations.append("#endif\n");
         unit.wrapStatements.append("#if ").append(cond).append('\n');
         if (special != null) {
            unit.wrapStatements.append("#if ").append(special).append('\n')
               .append("    ").append(GLINT_VARYING).append(" = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;\n")
               .append("#else\n")
               .append("    ").append(GLINT_VARYING).append(" = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;\n")
               .append("#endif\n");
         } else {
            unit.wrapStatements.append("    ").append(GLINT_VARYING).append(" = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;\n");
         }

         unit.wrapStatements.append("#endif\n");
      }
   }

   /**
    * An old vertex stage in front of a 26.3 fragment shader: what that shader reads and the old stage never heard
    * of is written around the old main(). 26.3 lights the two faces of entity geometry apart (PER_FACE_LIGHTING),
    * the old shader made one color; the 1.21.x item shader had the lightmap in its vertex color and no overlay.
    */
   private static void fillNativeInputs(Unit unit) {
      Guards fragment = unit.plan.nativeCounterpart();
      if (unit.stage != Stage.VERTEX || fragment == null || !unit.canWrap) {
         return;
      }

      if (unit.perFace && "vec4".equals(unit.written.get(VERTEX_COLOR))) {
         String cond = fragment.inputs.get(FACE_BACK);
         unit.wrapDeclarations.append(onlyIf(
            cond,
            "layout(location = " + fragment.inputLocations.get(FACE_BACK) + ") out vec4 " + FACE_BACK + ";\n"
               + "layout(location = " + fragment.inputLocations.get(FACE_FRONT) + ") out vec4 " + FACE_FRONT + ";\n"
         ));
         if (unit.facedNormal) {
            // the old main() runs once with the normal turned around, for the face that looks away, as 26.3 lights
            // that face; whatever else that run writes, the run after it writes again
            unit.wrapBefore.append(onlyIf(cond, "    lpf_face = -1.0;\n    lpf_legacy_main();\n    " + FACE_BACK + " = " + VERTEX_COLOR + ";\n    lpf_face = 1.0;\n"));
            unit.wrapStatements.append(onlyIf(cond, "    " + FACE_FRONT + " = " + VERTEX_COLOR + ";\n"));
         } else {
            unit.wrapStatements.append(onlyIf(cond, "    " + FACE_BACK + " = " + VERTEX_COLOR + ";\n    " + FACE_FRONT + " = " + VERTEX_COLOR + ";\n"));
         }
      }

      if (unit.oneDistance && "float".equals(unit.written.get(OLD_DISTANCE))) {
         // up to 1.21.5 one fog distance, in the shape FogShape asked for; 26.3 reads the distance in both shapes.
         // The old main() runs once more with the other shape; a stage that never asks FogShape hands its one
         // distance over as both
         String cond = fragment.inputs.get(SPHERICAL_DISTANCE);
         unit.wrapDeclarations.append(onlyIf(
            cond,
            "layout(location = " + fragment.inputLocations.get(SPHERICAL_DISTANCE) + ") out float " + SPHERICAL_DISTANCE + ";\n"
               + "layout(location = " + fragment.inputLocations.get(CYLINDRICAL_DISTANCE) + ") out float " + CYLINDRICAL_DISTANCE + ";\n"
         ));
         if (unit.shapeVariable) {
            unit.wrapBefore.append(onlyIf(cond, "    lpf_fog_shape = 0;\n    lpf_legacy_main();\n    " + SPHERICAL_DISTANCE + " = " + OLD_DISTANCE + ";\n    lpf_fog_shape = 1;\n"));
            unit.wrapStatements.append(onlyIf(cond, "    " + CYLINDRICAL_DISTANCE + " = " + OLD_DISTANCE + ";\n"));
         } else {
            unit.wrapStatements.append(onlyIf(cond, "    " + SPHERICAL_DISTANCE + " = " + OLD_DISTANCE + ";\n    " + CYLINDRICAL_DISTANCE + " = " + OLD_DISTANCE + ";\n"));
         }
      }

      if (unit.traits.foldedLightmap()) {
         fill(unit, fragment, LIGHTMAP_COLOR, "vec4(1.0)");
         fill(unit, fragment, OVERLAY_COLOR, "vec4(1.0)");
      }
   }

   private static void fill(Unit unit, Guards fragment, String name, String value) {
      Integer location = fragment.inputLocations.get(name);
      if (location == null || unit.written.containsKey(name) || Glsl.mentions(unit.source, name)) {
         return;
      }

      String cond = fragment.inputs.get(name);
      unit.wrapDeclarations.append(onlyIf(cond, "layout(location = " + location + ") out vec4 " + name + ";\n"));
      unit.wrapStatements.append(onlyIf(cond, "    " + name + " = " + value + ";\n"));
   }

   /** The overlay a 26.3 item vertex shader hands over, mixed in after an old item fragment shader that never had one. */
   private static void overlay(Unit unit) {
      if (unit.overlayCondition == null || unit.colorOutput == null || !unit.canWrap) {
         return;
      }

      String color = unit.colorOutput;
      unit.wrapStatements.append(onlyIf(
         unit.overlayCondition.isEmpty() ? null : unit.overlayCondition,
         "    " + color + ".rgb = mix(" + OVERLAY_COLOR + ".rgb * " + LIGHTMAP_COLOR + ".rgb, " + color + ".rgb, " + OVERLAY_COLOR + ".a);\n"
      ));
   }

   /** Keeps the declaration of an integer attribute out of the way while its uses are wrapped. */
   private static final String ATTRIBUTE_MARK = "LPF_DECLARE_";

   private static String include(String line, String id, Unit unit) {
      if (unit.traits.postVertex() && id.equals("minecraft:projection.glsl")) {
         return postProjection(unit);
      }

      if (unit.conditionals == 0) {
         unit.included.add(id);
      }

      return guardInclude(line, id, unit);
   }

   private static String looseUniform(String type, String name, Unit unit) {
      if (unit.traits.postVertex() && name.equals("ProjMat")) {
         return postProjection(unit);
      }

      if (name.equals("FogShape") && unit.oneDistance) {
         // the new main() asks the old one for the distance in both shapes
         unit.shapeVariable = true;
         return "int lpf_fog_shape = 1;\n#define FogShape lpf_fog_shape";
      }

      String fog = OLD_FOG.get(name);
      if (fog != null) {
         return blockInclude("minecraft:fog.glsl", unit) + "#define " + name + " " + fog;
      }

      String define = OLD_DEFINES.get(name);
      if (define != null) {
         return "#ifdef " + define + "\n#define " + name + " " + define + "\n#else\nconst " + type + " " + name + " = " + Glsl.zero(type) + ";\n#endif";
      }

      Guards vanilla = unit.vanillaTarget;
      if (unit.stage == Stage.VERTEX && vanilla != null && type.equals(vanilla.inputTypes.get(name)) && !unit.attributes.contains(name)) {
         // what older versions fed as a uniform and 26.3 feeds per vertex (the line width)
         Glsl.Decl d = new Glsl.Decl("in", "", type, name, 0, -1);
         String text = guarded(vanilla.inputs.get(name), declare(d, unit.nextAttribute), standIn(d));
         unit.nextAttribute += Glsl.locationSize(type, 0);
         unit.attributes.add(name);
         return text.substring(0, text.length() - 1);
      }

      String include = BLOCK_MEMBERS.get(name);
      if (include != null) {
         String text = blockInclude(include, unit);
         return text.isEmpty() ? "" : text.substring(0, text.length() - 1);
      }

      // What is left has no place in 26.3 and reads as zero (ChunkOffset, ...). Not where the 26.3 shader takes its
      // numbers from a block of its own: there a uniform that is read cannot be fed, zeros would draw the wrong
      // thing (a black world from the lightmap, an invisible outline from a post pass), so the line stays, the stage
      // does not compile and the pipeline falls back
      if (unit.used(name)) {
         if (unit.traits.postGroups() != null || unit.traits.postVertex()) {
            return "uniform " + type + " " + name + ";";
         }

         if (vanilla != null) {
            for (String block : vanilla.blocks.keySet()) {
               if (!unit.traits.pack().engineBlocks().containsKey(block)) {
                  return "uniform " + type + " " + name + ";";
               }
            }
         }
      }

      return "const " + type + " " + name + " = " + Glsl.zero(type) + ";";
   }

   /**
    * Post passes of 26.3 bind no Projection block. An older post vertex shader multiplies its quad corner, scaled
    * to the target, by ProjMat; here ProjMat is the orthographic matrix over that same size, so the product
    * covers the target as it did.
    */
   private static String postProjection(Unit unit) {
      boolean target = Glsl.mentions(unit.source, "OutSize");
      return (target ? "" : "#include <minecraft:globals.glsl>\n")
         + "#ifndef LPF_POST_PROJECTION\n"
         + "#define LPF_POST_PROJECTION\n"
         + "mat4 lpf_post_projection(vec2 size) {\n"
         + "    return mat4(2.0 / size.x, 0.0, 0.0, 0.0, 0.0, 2.0 / size.y, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, -1.0, -1.0, 0.0, 1.0);\n"
         + "}\n"
         + "#define ProjMat lpf_post_projection(" + (target ? "OutSize" : "ScreenSize") + ")\n"
         + "#endif";
   }

   /**
    * An include added for a loose uniform, as a line; nothing when the stage has that include for every variant
    * already. Blocks the vanilla shader never binds get our stand-in.
    */
   private static String blockInclude(String id, Unit unit) {
      if (unit.included.contains(id)) {
         return "";
      }

      if (unit.conditionals == 0) {
         unit.included.add(id);
      }

      if (STUBBED_INCLUDES.contains(id) && unit.vanillaTarget == null) {
         return stubInclude(id, unit) + "\n";
      }

      return guardInclude("#include <" + id + ">", id, unit) + "\n";
   }

   /**
    * fog.glsl and light.glsl declare uniform blocks only some pipelines bind. The include stays under the condition
    * the 26.3 shader has it under, in this stage or (the blocks of a pipeline are shared by its stages) in the other
    * one; elsewhere the stand-in takes its place. A shader with the include in neither stage runs in no pipeline
    * that binds the block.
    */
   private static String guardInclude(String line, String id, Unit unit) {
      Guards own = unit.vanillaTarget;
      if (own == null || !STUBBED_INCLUDES.contains(id)) {
         return line;
      }

      Guards other = unit.traits.otherStage();
      String cond;
      if (own.includes.containsKey(id)) {
         cond = own.includes.get(id);
      } else if (other != null && other.includes.containsKey(id)) {
         cond = other.includes.get(id);
      } else {
         return stubInclude(id, unit);
      }

      if (cond == null) {
         return line;
      }

      return "#if " + cond + "\n" + line.trim() + "\n#else\n" + stubInclude(id, unit) + "\n#endif";
   }

   /**
    * Our stand-in for fog.glsl or light.glsl. When the pack carries its own older copy of that include, the old
    * helpers that copy defines would be out of reach here: the copy is read after the stand-in when the two share
    * no name (the stand-in has defined the include's guard, so the copy adds only its own text), otherwise the
    * vanilla helpers come with the stand-in.
    */
   private static String stubInclude(String id, Unit unit) {
      StringBuilder out = new StringBuilder("#include <legacypackfix:stub/" + id.substring(id.indexOf(':') + 1) + ">");
      if (unit.traits.pack().besideStandIn().contains(id)) {
         return out.append("\n#include <").append(id).append('>').toString();
      }

      for (String helper : unit.traits.pack().standInFunctions().getOrDefault(id, Set.of())) {
         if (Glsl.calls(unit.source, helper) && OLD_HELPERS.containsKey(helper)) {
            out.append('\n').append(OLD_HELPERS.get(helper).stripTrailing());
         }
      }

      return out.toString();
   }

   /**
    * The condition a sampler exists under, or null when it is declared as it stands. The uniforms of a pipeline
    * are shared by its stages, so for a sampler the 26.3 shader has only in its other stage (the lightmap read in
    * the fragment stage) that stage's condition holds here too.
    */
   private static String samplerCondition(String name, Unit unit) {
      if (unit.vanillaTarget != null && unit.vanillaTarget.samplers.containsKey(name)) {
         return unit.vanillaTarget.samplers.get(name);
      }

      Guards other = unit.traits.otherStage();
      return other == null ? null : other.samplers.get(name);
   }

   private static String guardedSampler(String type, String name, String cond, Iterable<String> present, Iterable<String> absent) {
      String one = type.startsWith("isampler") ? "ivec4(1)" : type.startsWith("usampler") ? "uvec4(1u)" : "vec4(1.0)";
      StringBuilder out = new StringBuilder();
      out.append("#if ").append(cond).append('\n')
         .append("uniform ").append(type).append(' ').append(name).append(";\n")
         .append("#define LPF_texelFetch_").append(name).append("(a, b) texelFetch(").append(name).append(", a, b)\n")
         .append("#define LPF_texture2_").append(name).append("(a) texture(").append(name).append(", a)\n")
         .append("#define LPF_texture3_").append(name).append("(a, b) texture(").append(name).append(", a, b)\n")
         .append("#define LPF_textureLod_").append(name).append("(a, b) textureLod(").append(name).append(", a, b)\n")
         .append("#define LPF_textureSize_").append(name).append("(a) textureSize(").append(name).append(", a)\n")
         .append("#define LPF_sample_lightmap_").append(name).append("(a) sample_lightmap(").append(name).append(", a)\n");
      present.forEach(define -> out.append(define).append('\n'));
      out.append("#else\n")
         .append("#define LPF_texelFetch_").append(name).append("(a, b) ").append(one).append('\n')
         .append("#define LPF_texture2_").append(name).append("(a) ").append(one).append('\n')
         .append("#define LPF_texture3_").append(name).append("(a, b) ").append(one).append('\n')
         .append("#define LPF_textureLod_").append(name).append("(a, b) ").append(one).append('\n')
         .append("#define LPF_textureSize_").append(name).append("(a) ivec2(1)\n")
         .append("#define LPF_sample_lightmap_").append(name).append("(a) vec4(1.0)\n");
      absent.forEach(define -> out.append(define).append('\n'));
      return out.append("#endif\n").toString();
   }

   /** Routes texelFetch/texture/textureLod/textureSize (and vanilla's sample_lightmap) on a guarded sampler through its macros. */
   static String rewriteSamplerCalls(String body, String sampler) {
      Pattern call = Pattern.compile("\\b(texelFetch|texture|textureLod|textureSize|sample_lightmap)\\s*\\(\\s*" + Pattern.quote(sampler) + "\\s*,");
      StringBuilder out = new StringBuilder();
      int pos = 0;
      Matcher m = call.matcher(body);
      while (m.find(pos)) {
         int lineStart = body.lastIndexOf('\n', m.start()) + 1;
         if (body.startsWith("#define LPF_", lineStart)) {
            // our own forwarding macros must keep calling the real builtin
            int lineEnd = body.indexOf('\n', m.start());
            int end = lineEnd < 0 ? body.length() : lineEnd;
            out.append(body, pos, end);
            pos = end;
            continue;
         }

         int open = body.indexOf('(', m.start());
         int close = Glsl.matchParen(body, open);
         if (close < 0) {
            break;
         }

         List<String> args = Glsl.splitArgs(body.substring(open + 1, close));
         args.remove(0);
         String fn = m.group(1);
         String macro = switch (fn) {
            case "texture" -> "LPF_texture" + (args.size() + 1) + "_" + sampler;
            default -> "LPF_" + fn + "_" + sampler;
         };
         out.append(body, pos, m.start()).append(macro).append('(').append(String.join(", ", args)).append(')');
         pos = close + 1;
      }

      out.append(body.substring(pos));
      return out.toString();
   }

   private static final Set<String> NOT_A_CALL = Set.of("if", "for", "while", "switch", "return", "layout");

   /**
    * A guarded sampler handed to a function (a helper of the pack, one of the old vanilla helpers) cannot be named
    * in the variants that have no such sampler. Such a call goes through a macro: the call itself where the
    * sampler exists, elsewhere "white" of the function's type, which is what the lookups themselves read there.
    * A call whose result type cannot stand in (void, a struct) stays, and that variant falls back.
    */
   private static String rewriteSamplerArguments(
      String text, String sampler, String samplerType, Map<String, String> functionTypes, Map<String, String> present, Map<String, String> absent
   ) {
      StringBuilder out = new StringBuilder();
      int pos = 0;
      int at;
      while ((at = wordIndex(text, sampler, pos)) >= 0) {
         int next = at + sampler.length();
         int open = enclosingParen(text, at);
         int close = open < 0 ? -1 : Glsl.matchParen(text, open);
         int start = close < 0 ? -1 : calleeStart(text, open);
         String callee = start < 0 ? null : text.substring(start, open).trim();
         if (callee == null || start < pos || NOT_A_CALL.contains(callee)) {
            out.append(text, pos, next);
            pos = next;
            continue;
         }

         List<String> args = Glsl.splitArgs(text.substring(open + 1, close));
         int index = args.indexOf(sampler);
         String white = index < 0 ? null : white(resultType(callee, samplerType, functionTypes));
         if (white == null) {
            out.append(text, pos, next);
            pos = next;
            continue;
         }

         String macro = "LPF_arg_" + callee + "_" + args.size() + "_" + index + "_" + sampler;
         if (!present.containsKey(macro)) {
            List<String> parameters = new ArrayList<>();
            List<String> forwarded = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
               if (i == index) {
                  forwarded.add(sampler);
               } else {
                  parameters.add("a" + parameters.size());
                  forwarded.add(parameters.getLast());
               }
            }

            String head = "#define " + macro + "(" + String.join(", ", parameters) + ") ";
            present.put(macro, head + callee + "(" + String.join(", ", forwarded) + ")");
            absent.put(macro, head + white);
         }

         args.remove(index);
         args.replaceAll(arg -> rewriteSamplerArguments(arg, sampler, samplerType, functionTypes, present, absent));
         out.append(text, pos, start).append(macro).append('(').append(String.join(", ", args)).append(')');
         pos = close + 1;
      }

      return out.append(text, pos, text.length()).toString();
   }

   private static int wordIndex(String text, String word, int from) {
      int at;
      while ((at = text.indexOf(word, from)) >= 0) {
         int end = at + word.length();
         if ((at == 0 || !Glsl.isWordChar(text.charAt(at - 1))) && (end == text.length() || !Glsl.isWordChar(text.charAt(end)))) {
            return at;
         }

         from = end;
      }

      return -1;
   }

   /** The parenthesis whose argument list holds the position, or -1 when it is not inside one. */
   private static int enclosingParen(String text, int at) {
      int depth = 0;
      for (int i = at - 1; i >= 0; i--) {
         char c = text.charAt(i);
         if (c == ')') {
            depth++;
         } else if (c == '(') {
            if (depth == 0) {
               return i;
            }

            depth--;
         } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) {
            return -1;
         }
      }

      return -1;
   }

   /** Where the name in front of a parenthesis starts, or -1 when no name stands there. */
   private static int calleeStart(String text, int open) {
      int end = open;
      while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
         end--;
      }

      int start = end;
      while (start > 0 && Glsl.isWordChar(text.charAt(start - 1))) {
         start--;
      }

      return start == end || Character.isDigit(text.charAt(start)) ? -1 : start;
   }

   private static String resultType(String function, String samplerType, Map<String, String> functionTypes) {
      String declared = functionTypes.get(function);
      if (declared != null) {
         return declared;
      }

      if (function.equals("textureQueryLod")) {
         return "vec2";
      }

      if (function.equals("textureQueryLevels")) {
         return "int";
      }

      if (function.equals("textureSize")) {
         return samplerType.endsWith("2D") ? "ivec2" : null;
      }

      if (function.startsWith("texture") || function.startsWith("texelFetch")) {
         return samplerType.startsWith("isampler") ? "ivec4" : samplerType.startsWith("usampler") ? "uvec4" : "vec4";
      }

      // the old vanilla helpers, or one from an include: a lookup that hands the texel back
      return "vec4";
   }

   private static String white(String type) {
      if (type == null) {
         return null;
      }

      return switch (type) {
         case "float" -> "1.0";
         case "int" -> "1";
         case "uint" -> "1u";
         case "bool" -> "true";
         case "vec2", "vec3", "vec4" -> type + "(1.0)";
         case "ivec2", "ivec3", "ivec4" -> type + "(1)";
         case "uvec2", "uvec3", "uvec4" -> type + "(1u)";
         case "bvec2", "bvec3", "bvec4" -> type + "(true)";
         default -> null;
      };
   }

   private static String varying(Glsl.Decl d, Unit unit) {
      Integer loc = unit.plan.locations().get(d.name());
      Guards counterpart = unit.plan.nativeCounterpart();
      if (unit.stage == Stage.VERTEX) {
         String cond = counterpart != null && counterpart.inputs.containsKey(d.name()) ? counterpart.inputs.get(d.name()) : null;
         return guarded(cond, declare(d, loc == null ? 31 : loc), d.type() + " " + d.name() + arraySuffix(d) + ";\n");
      }

      if (loc == null || (counterpart != null && !counterpart.outputs.containsKey(d.name()))) {
         // no vertex stage writes this one; reading it would fail pipeline validation
         String derived = derivedPostVarying(d, unit);
         if (derived == null && counterpart != null) {
            derived = oldFogDistance(d, counterpart, unit);
         }

         return derived != null ? derived : standIn(d);
      }

      String cond = counterpart == null ? null : counterpart.outputs.get(d.name());
      if (counterpart != null && d.name().equals(VERTEX_COLOR) && d.type().equals("vec4") && d.arraySize() == 0) {
         String adapted = cond != null ? perFaceColor(d, loc, cond, counterpart, unit) : foldedLightmap(d, loc, counterpart, unit);
         if (adapted != null) {
            return adapted;
         }
      }

      return guarded(cond, declare(d, loc), standIn(d));
   }

   /**
    * Up to 1.21.5 the vertex shader handed over one fog distance, measured the way the one fog of the moment asked
    * for ({@link #OLD_FOG}). A 26.3 vertex shader hands over both measures; an old fragment shader in front of it
    * reads the one that belongs to the fog its FogStart and FogEnd describe.
    */
   private static String oldFogDistance(Glsl.Decl d, Guards vertex, Unit unit) {
      Integer spherical = vertex.outputLocations.get(SPHERICAL_DISTANCE);
      Integer cylindrical = vertex.outputLocations.get(CYLINDRICAL_DISTANCE);
      if (!d.name().equals(OLD_DISTANCE) || !d.type().equals("float") || d.arraySize() != 0 || spherical == null || cylindrical == null) {
         return null;
      }

      if (Glsl.mentions(unit.source, SPHERICAL_DISTANCE) || Glsl.mentions(unit.source, CYLINDRICAL_DISTANCE)) {
         return null;
      }

      return blockInclude("minecraft:fog.glsl", unit)
         + guarded(vertex.outputs.get(SPHERICAL_DISTANCE), "layout(location = " + spherical + ") in float " + SPHERICAL_DISTANCE + ";\n", "const float " + SPHERICAL_DISTANCE + " = 0.0;\n")
         + guarded(vertex.outputs.get(CYLINDRICAL_DISTANCE), "layout(location = " + cylindrical + ") in float " + CYLINDRICAL_DISTANCE + ";\n", "const float " + CYLINDRICAL_DISTANCE + " = 0.0;\n")
         + "#define " + d.name() + " (" + SURROUNDINGS_FOG + " ? " + SPHERICAL_DISTANCE + " : " + CYLINDRICAL_DISTANCE + ")\n";
   }

   /**
    * 26.3 lights the two faces of entity geometry apart (PER_FACE_LIGHTING): its vertex shader writes one color
    * per face instead of vertexColor. An old fragment shader in front of it reads the color of the face it draws.
    */
   private static String perFaceColor(Glsl.Decl d, int loc, String cond, Guards vertex, Unit unit) {
      Integer back = vertex.outputLocations.get(FACE_BACK);
      Integer front = vertex.outputLocations.get(FACE_FRONT);
      if (back == null || front == null || Glsl.mentions(unit.source, FACE_BACK) || Glsl.mentions(unit.source, FACE_FRONT)) {
         return null;
      }

      String faces = vertex.outputs.get(FACE_BACK);
      if (faces == null ? vertex.outputs.get(FACE_FRONT) != null : !faces.equals(vertex.outputs.get(FACE_FRONT))) {
         return null;
      }

      String perFace = "layout(location = " + back + ") in vec4 " + FACE_BACK + ";\n"
         + "layout(location = " + front + ") in vec4 " + FACE_FRONT + ";\n"
         + "#define " + VERTEX_COLOR + " (gl_FrontFacing ? " + FACE_FRONT + " : " + FACE_BACK + ")\n";
      return guarded(cond, declare(d, loc), guarded(faces, perFace, standIn(d)));
   }

   /**
    * The 1.21.x item vertex shader multiplied the lightmap into vertexColor; 26.3's hands lightmap and overlay over
    * on their own. An old item fragment shader in front of it reads vertexColor with the lightmap in it again; the
    * overlay is mixed in after its main() ({@link #overlay}).
    */
   private static String foldedLightmap(Glsl.Decl d, int loc, Guards vertex, Unit unit) {
      Integer light = vertex.outputLocations.get(LIGHTMAP_COLOR);
      if (!unit.traits.foldedLightmap() || light == null || Glsl.mentions(unit.source, LIGHTMAP_COLOR)) {
         return null;
      }

      Glsl.Decl plain = new Glsl.Decl(d.direction(), d.qualifiers(), d.type(), "lpf_" + d.name(), 0, d.location());
      StringBuilder out = new StringBuilder();
      out.append(declare(plain, loc))
         .append(guarded(vertex.outputs.get(LIGHTMAP_COLOR), "layout(location = " + light + ") in vec4 " + LIGHTMAP_COLOR + ";\n", "const vec4 " + LIGHTMAP_COLOR + " = vec4(1.0);\n"))
         .append("#define ").append(d.name()).append(" (").append(plain.name()).append(" * ").append(LIGHTMAP_COLOR).append(")\n");
      Integer overlay = vertex.outputLocations.get(OVERLAY_COLOR);
      if (overlay != null && !Glsl.mentions(unit.source, OVERLAY_COLOR)) {
         String cond = vertex.outputs.get(OVERLAY_COLOR);
         out.append(guarded(cond, "layout(location = " + overlay + ") in vec4 " + OVERLAY_COLOR + ";\n", "const vec4 " + OVERLAY_COLOR + " = vec4(1.0);\n"));
         unit.overlayCondition = cond == null ? "" : cond;
      }

      return out.toString();
   }

   /**
    * What the vertex shaders 26.3 dropped (post/sobel, post/blur) worked out for their fragment shaders: when the
    * vertex shader a pass names does not hand it over, the fragment shader works it out, as 26.3's own do.
    */
   private static String derivedPostVarying(Glsl.Decl d, Unit unit) {
      Set<String> groups = unit.traits.postGroups();
      if (groups == null || !d.type().equals("vec2") || d.arraySize() != 0) {
         return null;
      }

      boolean step = d.name().equals("sampleStep");
      if (!step && !d.name().equals("oneTexel")) {
         return null;
      }

      // a block the shader declares itself must have the member; one it does not declare is declared here
      boolean ownInfo = Glsl.mentions(unit.source, "SamplerInfo");
      boolean ownBlur = Glsl.mentions(unit.source, "BlurConfig");
      if (ownInfo ? !Glsl.mentions(unit.source, "InSize") : Glsl.mentions(unit.source, "InSize") || Glsl.mentions(unit.source, "OutSize")) {
         return null;
      }

      if (step && !groups.contains("BlurConfig")) {
         return null;
      }

      if (step && (ownBlur ? !Glsl.mentions(unit.source, "BlurDir") : Glsl.mentions(unit.source, "BlurDir") || Glsl.mentions(unit.source, "Radius"))) {
         return null;
      }

      StringBuilder out = new StringBuilder();
      if (!ownInfo && !unit.samplerInfo) {
         unit.samplerInfo = true;
         out.append("layout(std140) uniform SamplerInfo {\n    vec2 OutSize;\n    vec2 InSize;\n};\n");
      }

      if (step && !ownBlur && !unit.blurConfig) {
         unit.blurConfig = true;
         out.append("layout(std140) uniform BlurConfig {\n    vec2 BlurDir;\n    float Radius;\n};\n");
      }

      return out.append("#define ").append(d.name()).append(step ? " ((1.0 / InSize) * BlurDir)\n" : " (1.0 / InSize)\n").toString();
   }

   private static String declare(Glsl.Decl d, int location) {
      String q = d.qualifiers().isEmpty() ? "" : d.qualifiers() + " ";
      return "layout(location = " + location + ") " + q + d.direction() + " " + d.type() + " " + d.name() + arraySuffix(d) + ";\n";
   }

   private static String arraySuffix(Glsl.Decl d) {
      return d.arraySize() > 0 ? "[" + d.arraySize() + "]" : "";
   }

   /** Constant used in place of an input that a pipeline variant does not provide. */
   private static String standIn(Glsl.Decl d) {
      String value = COLORISH.matcher(d.name()).find() && !d.type().startsWith("i") && !d.type().startsWith("u")
         ? (d.type().equals("float") ? "1.0" : d.type() + "(1.0)")
         : Glsl.zero(d.type());
      if (d.arraySize() > 0) {
         return d.type() + " " + d.name() + "[" + d.arraySize() + "];\n";
      }

      return "const " + d.type() + " " + d.name() + " = " + value + ";\n";
   }

   private static String guarded(String cond, String yes, String no) {
      if (cond == null) {
         return yes.endsWith("\n") ? yes : yes + "\n";
      }

      return "#if " + cond + "\n" + yes + (yes.endsWith("\n") ? "" : "\n") + "#else\n" + no + (no.endsWith("\n") ? "" : "\n") + "#endif\n";
   }

   private static String onlyIf(String cond, String text) {
      return cond == null ? text : "#if " + cond + "\n" + text + "#endif\n";
   }

   // ------------------------------------------------------------------ assembly

   /** A native 26.3 shader without its #version / SSO extension lines, for use as a branch body. */
   public static String stripHeader(String nativeSrc) {
      String s = Glsl.normalize(nativeSrc);
      s = VERSION.matcher(s).replaceAll("");
      return SSO_EXT.matcher(s).replaceAll("");
   }

   public static String single(String body) {
      return HEADER + body;
   }

   /** Selects one complete shader per pipeline variant with the preprocessor. */
   public static String wrap(List<Branch> branches, String elseBody) {
      StringBuilder out = new StringBuilder(HEADER);
      for (int i = 0; i < branches.size(); i++) {
         Branch b = branches.get(i);
         out.append(i == 0 ? "#if " : "#elif ").append(b.condition()).append('\n').append(b.body()).append('\n');
      }

      out.append("#else\n").append(elseBody).append("\n#endif\n");
      return out.toString();
   }
}
