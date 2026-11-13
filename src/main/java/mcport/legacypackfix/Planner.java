package mcport.legacypackfix;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mcport.legacypackfix.glsl.Glsl;
import mcport.legacypackfix.glsl.Guards;
import mcport.legacypackfix.glsl.LegacyTranslator;
import mcport.legacypackfix.glsl.LegacyTranslator.Branch;
import mcport.legacypackfix.glsl.LegacyTranslator.Stage;
import mcport.legacypackfix.glsl.LegacyTranslator.Traits;
import mcport.legacypackfix.glsl.LegacyTranslator.VaryingPlan;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;

/**
 * Decides which shader files to rewrite and produces their 26.3 sources. Works on plain file data so
 * the same logic runs in game (from the resource manager) and in the offline test harness.
 */
public final class Planner {
   /**
    * A file of the shader tree, or a post effect file (post_effect/*.json, which says what vertex shader a post
    * fragment shader runs with): the top-most pack's text, which pack that is, and the text of the highest
    * resource that no player or server pack put there (vanilla's, or a mod's), if any.
    */
   public record File(Identifier location, String text, String pack, String baseText) {
   }

   public record Key(Identifier id, ShaderType type) {
   }

   public static final class Result {
      public final Map<Key, String> translated = new HashMap<>();
      public final Map<Key, String> vanilla = new HashMap<>();
      public final Map<Identifier, String> includes = new LinkedHashMap<>();
      public final Map<Identifier, String> vanillaIncludes = new HashMap<>();
      public final Set<Identifier> touchedShaders = new HashSet<>();
      public final Set<String> packs = new TreeSet<>();
      /** Stages a player or server pack replaces without translation (26.3 format), with what they replaced. */
      public final Map<Key, String> baseShaders = new HashMap<>();
      /** Includes a player or server pack replaces without translation, with what they replaced. */
      public final Map<Identifier, String> baseIncludes = new HashMap<>();
      /** Shaders with a stage from a player or server pack, translated or not. */
      public final Set<Identifier> userShaders = new HashSet<>();
      /** Some include comes from a player or server pack; any shader may pull it in. */
      public boolean userIncludes;
      /** Old stages ("id.stage") under names nothing in 26.3 draws with; they are left as they are. */
      public final Set<String> ignored = new TreeSet<>();

      /** A player or server pack carries shaders at all: pipelines may need the fallback. */
      public boolean active() {
         return this.translates() || !this.userShaders.isEmpty() || this.userIncludes;
      }

      /** Something of an older pack was rewritten. */
      public boolean translates() {
         return !this.translated.isEmpty() || !this.includes.isEmpty();
      }

      /** Whether a pipeline with these shaders may compile something a player or server pack brought. */
      public boolean touches(java.util.Collection<Identifier> shaders) {
         if (this.userIncludes) {
            return true;
         }

         for (Identifier id : shaders) {
            if (this.touchedShaders.contains(id) || this.userShaders.contains(id)) {
               return true;
            }
         }

         return false;
      }

      /** Whether a pipeline with these shaders compiles translated sources of an older pack. */
      public boolean translates(java.util.Collection<Identifier> shaders) {
         if (!this.includes.isEmpty()) {
            return true;
         }

         for (Identifier id : shaders) {
            if (this.touchedShaders.contains(id)) {
               return true;
            }
         }

         return false;
      }

      /** In the fallback, this stage is swapped for what the pack replaced. */
      public boolean replaces(Key key) {
         return this.translated.containsKey(key) || this.baseShaders.containsKey(key);
      }

      public String fallbackShader(Key key) {
         return this.translated.containsKey(key) ? this.vanilla.get(key) : this.baseShaders.get(key);
      }

      /** What an include falls back to, or null when it stays as it is. */
      public String fallbackInclude(Identifier id) {
         return this.includes.containsKey(id) ? this.vanillaIncludes.get(id) : this.baseIncludes.get(id);
      }
   }

   /** The top-most text of a stage; legacy: it is from a player or server pack and in an older format. */
   private record Src(String text, String pack, Identifier location, boolean legacy) {
   }

   private record Rename(String target, String condition) {
   }

   /** One pass of a post effect file: its shader pair, the uniform blocks it feeds, and the player or server pack it is from (or null). */
   private record Pass(Identifier vertex, Identifier fragment, Set<String> groups, String pack) {
   }

   /** 1.21.x shader names that 26.3 merged into other shaders, and the define branch each maps to. */
   private static final Map<String, Rename> RENAMES = new LinkedHashMap<>();
   /** The 1.21.x item shader: its vertex color carried the lightmap, and it had no overlay. */
   private static final String OLD_ITEM = "minecraft:core/rendertype_item_entity_translucent_cull";
   private static final FileToIdConverter POST_EFFECTS = FileToIdConverter.json("post_effect");
   private static final Pattern DROPPED_POST_VERTEX_USE = Pattern.compile("\"vertex_shader\"\\s*:\\s*\"(?:minecraft:)?post/(?:blit|blur|sobel|screenquad|invert)\"");
   private static final Pattern QUOTED_IMPORT = Pattern.compile("#\\s*moj_import\\s*\"([^\"]+)\"");
   /**
    * Vertex shaders the vanilla post effects of 1.21.6 - 1.21.8 ran with; 26.3 has none of them (core/screenquad
    * took their place). The mod carries 26.3 versions under its own namespace.
    */
   private static final Set<String> DROPPED_POST_VERTEX = Set.of("post/blit", "post/blur", "post/sobel", "post/screenquad", "post/invert");
   private static final String OWN_NAMESPACE = "legacypackfix";

   static {
      RENAMES.put("minecraft:core/rendertype_text", new Rename("minecraft:core/text", "!defined(IS_SEE_THROUGH) && !defined(IS_GRAYSCALE)"));
      RENAMES.put("minecraft:core/rendertype_text_see_through", new Rename("minecraft:core/text", "defined(IS_SEE_THROUGH) && !defined(IS_GRAYSCALE)"));
      RENAMES.put("minecraft:core/rendertype_text_intensity", new Rename("minecraft:core/text", "defined(IS_GRAYSCALE) && !defined(IS_SEE_THROUGH)"));
      RENAMES.put("minecraft:core/rendertype_text_intensity_see_through", new Rename("minecraft:core/text", "defined(IS_GRAYSCALE) && defined(IS_SEE_THROUGH)"));
      RENAMES.put(OLD_ITEM, new Rename("minecraft:core/item", "1"));
      RENAMES.put("minecraft:core/rendertype_clouds", new Rename("minecraft:core/clouds", "1"));
      RENAMES.put("minecraft:core/rendertype_world_border", new Rename("minecraft:core/world_border", "1"));
   }

   private final Map<Identifier, File> files;
   private final Result result = new Result();
   private final List<Pass> passes = new ArrayList<>();
   /** Every stage some post effect pass names. */
   private final Set<Key> postStages = new HashSet<>();
   /** Where the old vertex stages of post passes put what they write (name to location), by shader id. */
   private final Map<Identifier, Map<String, Integer>> postOutputs = new HashMap<>();
   /** The uniform blocks the includes of the game (and of mods) declare: name to members. */
   private final Map<String, String> engineBlocks = new HashMap<>();
   /** What the stages need to know about the packs' other shader files. */
   private LegacyTranslator.Pack pack = LegacyTranslator.Pack.NONE;

   private Planner(Map<Identifier, File> files) {
      this.files = files;
   }

   public static Result plan(Map<Identifier, File> files) {
      Planner planner = new Planner(files);
      planner.run();
      return planner.result;
   }

   private void run() {
      Map<Key, Src> top = new HashMap<>();
      Map<Key, String> vanillaSrc = new HashMap<>();
      List<File> userIncludes = new ArrayList<>();
      List<File> postEffects = new ArrayList<>();
      boolean legacyStages = false;
      Map<String, List<Identifier>> definedIn = new HashMap<>();
      Set<String> ambiguous = new HashSet<>();
      StringBuilder helpers = new StringBuilder();
      for (File f : this.files.values()) {
         ShaderType type = ShaderType.byLocation(f.location());
         boolean user = f.text() != null && isUserPack(f.pack());
         if (type != null) {
            Key key = new Key(type.idConverter().fileToId(f.location()), type);
            if (f.text() != null) {
               Src src = new Src(f.text(), f.pack(), f.location(), user && LegacyTranslator.isLegacyShader(f.text()));
               top.put(key, src);
               legacyStages |= src.legacy;
            }

            if (f.baseText() != null) {
               vanillaSrc.put(key, f.baseText());
            }

            if (user) {
               this.result.userShaders.add(key.id());
               LegacyTranslator.definedHelpers(f.text()).forEach(name -> definedIn.computeIfAbsent(name, n -> new ArrayList<>()).add(f.location()));
               if (f.baseText() != null) {
                  this.result.baseShaders.put(key, f.baseText());
               }
            }
         } else if (POST_EFFECTS.matches(f.location())) {
            postEffects.add(f);
         } else if (user && f.location().getPath().endsWith(".glsl")) {
            // an include, or a helper file next to the shaders that a quoted import names
            helpers.append(f.text()).append('\n');
            LegacyTranslator.definedHelpers(f.text()).forEach(name -> definedIn.computeIfAbsent(name, n -> new ArrayList<>()).add(f.location()));
            if (ShaderManager.SHADER_INCLUDE_CONVERTER.matches(f.location())) {
               userIncludes.add(f);
            }
         }

         if (type == null && f.baseText() != null && ShaderManager.SHADER_INCLUDE_CONVERTER.matches(f.location())) {
            for (Glsl.Block block : Glsl.uniformBlocks(Glsl.stripComments(Glsl.normalize(f.baseText())))) {
               // one name with two layouts would be nobody's layout
               String known = this.engineBlocks.putIfAbsent(block.name(), block.members());
               if (known != null && !known.replaceAll("\\s+", " ").equals(block.members().replaceAll("\\s+", " "))) {
                  ambiguous.add(block.name());
               }
            }
         }
      }

      // a quoted import may name any file of the pack, whatever its extension
      Set<Identifier> imported = new HashSet<>();
      for (File f : this.files.values()) {
         if (f.text() == null || !isUserPack(f.pack()) || !f.text().contains("moj_import")) {
            continue;
         }

         Matcher quoted = QUOTED_IMPORT.matcher(f.text());
         while (quoted.find()) {
            Identifier location = sibling(f.location(), quoted.group(1));
            File target = location != null ? this.files.get(location) : null;
            if (target != null && target.text() != null && !target.location().getPath().endsWith(".glsl") && imported.add(target.location())) {
               helpers.append(target.text()).append('\n');
            }
         }
      }

      this.engineBlocks.keySet().removeAll(ambiguous);
      for (File f : postEffects) {
         // only old stages are planned against the passes; without any, what matters is a pass that names a dropped vertex shader
         if (!legacyStages && (!user(f) || !DROPPED_POST_VERTEX_USE.matcher(f.text()).find())) {
            continue;
         }

         // a file the game cannot read (older than 1.21.6) is swapped for the one it replaced (PostEffects.repair)
         if ((f.text() == null || !this.readPasses(f.text(), user(f) ? f.pack() : null)) && f.baseText() != null && user(f)) {
            this.readPasses(f.baseText(), null);
         }
      }

      // an old helper only the pack's own copy of fog.glsl or light.glsl defines is out of reach where our stand-in replaces that include
      Map<String, Set<String>> standIn = new HashMap<>();
      definedIn.forEach((name, where) -> {
         if (where.size() == 1 && ShaderManager.SHADER_INCLUDE_CONVERTER.matches(where.getFirst())) {
            String id = ShaderManager.SHADER_INCLUDE_CONVERTER.fileToId(where.getFirst()).withSuffix(".glsl").toString();
            if (LegacyTranslator.STUBBED_INCLUDES.contains(id)) {
               standIn.computeIfAbsent(id, i -> new HashSet<>()).add(name);
            }
         }
      });
      Set<String> besideStandIn = new HashSet<>();
      for (File f : userIncludes) {
         String id = ShaderManager.SHADER_INCLUDE_CONVERTER.fileToId(f.location()).withSuffix(".glsl").toString();
         File ours = this.files.get(Identifier.fromNamespaceAndPath(OWN_NAMESPACE, "shaders/include/stub/" + f.location().getPath().substring("shaders/include/".length())));
         if (LegacyTranslator.STUBBED_INCLUDES.contains(id) && ours != null && ours.text() != null && LegacyTranslator.isLegacyInclude(f.text())
            && LegacyTranslator.disjoint(f.text(), ours.text())) {
            besideStandIn.add(id);
         }
      }

      this.pack = new LegacyTranslator.Pack(helpers.toString(), definedIn.keySet(), standIn, besideStandIn, this.engineBlocks);
      for (File f : userIncludes) {
         Identifier id = ShaderManager.SHADER_INCLUDE_CONVERTER.fileToId(f.location()).withSuffix(".glsl");
         this.result.userIncludes = true;
         if (LegacyTranslator.isLegacyInclude(f.text())) {
            this.result.includes.put(
               id,
               LegacyTranslator.translateInclude(f.text(), id.toString(), this.relative(f.location()), this.pack, f.baseText())
            );
            if (f.baseText() != null) {
               this.result.vanillaIncludes.put(id, f.baseText());
            }

            this.result.packs.add(f.pack());
         } else if (f.baseText() != null) {
            this.result.baseIncludes.put(id, f.baseText());
         }
      }

      this.droppedPostVertex(top);
      this.renamed(top, vanillaSrc);
      this.postPasses(top, vanillaSrc);
      this.sameName(top, vanillaSrc);
   }

   /** Reads the passes of a post effect file; false when it is not a file 26.3 can read (the game reports that). */
   private boolean readPasses(String json, String pack) {
      JsonElement root;
      try {
         root = JsonParser.parseString(json);
      } catch (RuntimeException e) {
         return false;
      }

      if (!root.isJsonObject()) {
         return false;
      }

      JsonElement all = root.getAsJsonObject().get("passes");
      if (all == null) {
         return true;
      }

      if (!all.isJsonArray()) {
         return false;
      }

      List<Pass> read = new ArrayList<>();
      for (JsonElement element : all.getAsJsonArray()) {
         if (!element.isJsonObject()) {
            return false;
         }

         JsonObject pass = element.getAsJsonObject();
         Identifier vertex = shaderId(pass.get("vertex_shader"));
         Identifier fragment = shaderId(pass.get("fragment_shader"));
         JsonElement uniforms = pass.get("uniforms");
         // up to 1.21.4 a pass named a program, in 1.21.5 its uniforms were a list
         if (vertex == null || fragment == null || !pass.has("output") || uniforms != null && !uniforms.isJsonObject()) {
            return false;
         }

         read.add(new Pass(vertex, fragment, uniforms == null ? Set.of() : new HashSet<>(uniforms.getAsJsonObject().keySet()), pack));
      }

      for (Pass pass : read) {
         this.passes.add(pass);
         this.postStages.add(new Key(pass.vertex(), ShaderType.VERTEX));
         this.postStages.add(new Key(pass.fragment(), ShaderType.FRAGMENT));
      }

      return true;
   }

   private static Identifier shaderId(JsonElement element) {
      return element != null && element.isJsonPrimitive() ? Identifier.tryParse(element.getAsString()) : null;
   }

   /**
    * Where a pack's post effect names one of the vertex shaders 26.3 dropped and nothing provides it, the mod's
    * 26.3 version is served under that name: the pack's effect loads instead of being swapped out.
    */
   private void droppedPostVertex(Map<Key, Src> top) {
      for (Pass pass : this.passes) {
         Identifier id = pass.vertex();
         Key key = new Key(id, ShaderType.VERTEX);
         if (pass.pack() == null || !id.getNamespace().equals("minecraft") || !DROPPED_POST_VERTEX.contains(id.getPath()) || top.containsKey(key)) {
            continue;
         }

         Src own = top.get(new Key(Identifier.fromNamespaceAndPath(OWN_NAMESPACE, id.getPath()), ShaderType.VERTEX));
         if (own == null) {
            continue;
         }

         this.result.translated.put(key, own.text);
         // it is the mod's own shader: a pass that falls back to what the pack replaced keeps it
         this.result.vanilla.put(key, own.text);
         this.result.touchedShaders.add(id);
         this.result.packs.add(pass.pack());
      }
   }

   /** 1.21.x shaders whose names no longer exist: fold them into the 26.3 shader as define branches. */
   private void renamed(Map<Key, Src> top, Map<Key, String> vanillaSrc) {
      Map<String, List<Map.Entry<String, Rename>>> byTarget = new LinkedHashMap<>();
      RENAMES.entrySet().forEach(e -> byTarget.computeIfAbsent(e.getValue().target(), t -> new ArrayList<>()).add(e));
      for (Map.Entry<String, List<Map.Entry<String, Rename>>> t : byTarget.entrySet()) {
         Identifier target = Identifier.parse(t.getKey());
         Key tv = new Key(target, ShaderType.VERTEX);
         Key tf = new Key(target, ShaderType.FRAGMENT);
         if (vanillaSrc.get(tv) == null || vanillaSrc.get(tf) == null || overriddenNatively(top.get(tv)) || overriddenNatively(top.get(tf))) {
            continue;
         }

         Guards vanV = Guards.scan(vanillaSrc.get(tv));
         Guards vanF = Guards.scan(vanillaSrc.get(tf));
         List<Branch> vBranches = new ArrayList<>();
         List<Branch> fBranches = new ArrayList<>();
         for (Map.Entry<String, Rename> mapping : t.getValue()) {
            Identifier legacyId = Identifier.parse(mapping.getKey());
            Src lv = legacy(top.get(new Key(legacyId, ShaderType.VERTEX)));
            Src lf = legacy(top.get(new Key(legacyId, ShaderType.FRAGMENT)));
            if (lv == null && lf == null) {
               continue;
            }

            VaryingPlan plan = lv != null && lf != null
               ? LegacyTranslator.planVaryings(lv.text, lf.text, null, false)
               : lv != null ? LegacyTranslator.planVaryings(lv.text, null, vanF, false) : LegacyTranslator.planVaryings(null, lf.text, vanV, true);
            String cond = mapping.getValue().condition();
            // one half of the old item pair in front of 26.3's other half: the lightmap moved between the stages
            boolean folded = mapping.getKey().equals(OLD_ITEM) && (lv == null || lf == null);
            if (lv != null) {
               vBranches.add(new Branch(cond, LegacyTranslator.translateBody(lv.text, Stage.VERTEX, plan, vanV, this.relative(lv.location), this.traits(vanF, folded))));
               this.result.packs.add(lv.pack);
            }

            if (lf != null) {
               fBranches.add(new Branch(cond, LegacyTranslator.translateBody(lf.text, Stage.FRAGMENT, plan, vanF, this.relative(lf.location), this.traits(vanV, folded))));
               this.result.packs.add(lf.pack);
            }
         }

         this.addWrapped(tv, vBranches, vanillaSrc.get(tv));
         this.addWrapped(tf, fBranches, vanillaSrc.get(tf));
      }
   }

   /**
    * Post effect files say which vertex shader a fragment shader runs with, pass by pass. The old stages of post
    * passes are planned against those partners instead of a stage of the same name: old vertex stages first (they
    * keep what 26.3 fragment shaders beside them read where those read it), then each old fragment stage, which
    * can read what every vertex shader it runs with writes at one place.
    */
   private void postPasses(Map<Key, Src> top, Map<Key, String> vanillaSrc) {
      Map<Identifier, Set<Identifier>> fragmentsOf = new LinkedHashMap<>();
      Map<Identifier, Set<Identifier>> verticesOf = new LinkedHashMap<>();
      Map<Key, Set<String>> groups = new HashMap<>();
      for (Pass pass : this.passes) {
         fragmentsOf.computeIfAbsent(pass.vertex(), id -> new TreeSet<>()).add(pass.fragment());
         verticesOf.computeIfAbsent(pass.fragment(), id -> new TreeSet<>()).add(pass.vertex());
         for (Key key : List.of(new Key(pass.vertex(), ShaderType.VERTEX), new Key(pass.fragment(), ShaderType.FRAGMENT))) {
            Set<String> fed = groups.get(key);
            if (fed == null) {
               groups.put(key, new HashSet<>(pass.groups()));
            } else {
               fed.retainAll(pass.groups());
            }
         }
      }

      Map<Identifier, Guards> nativeVertex = new HashMap<>();
      for (Map.Entry<Identifier, Set<Identifier>> entry : fragmentsOf.entrySet()) {
         Identifier id = entry.getKey();
         Key kv = new Key(id, ShaderType.VERTEX);
         Src lv = legacy(top.get(kv));
         if (lv == null) {
            String text = this.result.translated.containsKey(kv) ? this.result.translated.get(kv) : top.containsKey(kv) ? top.get(kv).text : null;
            if (text != null) {
               nativeVertex.put(id, Guards.scan(text));
            }

            continue;
         }

         if (RENAMES.containsKey(id.toString()) || this.result.translated.containsKey(kv)) {
            continue;
         }

         Map<String, Integer> fixed = new HashMap<>();
         for (Identifier fragment : entry.getValue()) {
            Src nf = top.get(new Key(fragment, ShaderType.FRAGMENT));
            if (nf != null && legacy(nf) == null) {
               Guards.scan(nf.text).inputLocations.forEach(fixed::putIfAbsent);
            }
         }

         // a shader the game also draws vertex buffers with (its 26.3 version reads Position) is not rewritten for post passes
         String base = vanillaSrc.get(kv);
         boolean bufferless = base == null || !Guards.scan(base).inputs.containsKey("Position");
         Map<String, Integer> locations = LegacyTranslator.outputLocations(lv.text, fixed);
         this.postOutputs.put(id, locations);
         this.addSingle(kv, lv, Stage.VERTEX, new VaryingPlan(locations, null), base, this.traits(bufferless, groups.get(kv), false, null));
      }

      for (Map.Entry<Identifier, Set<Identifier>> entry : verticesOf.entrySet()) {
         Identifier id = entry.getKey();
         Key kf = new Key(id, ShaderType.FRAGMENT);
         Src lf = legacy(top.get(kf));
         if (lf == null || RENAMES.containsKey(id.toString()) || this.result.translated.containsKey(kf)) {
            continue;
         }

         Map<String, Integer> shared = null;
         Guards only = null;
         int partners = 0;
         for (Identifier vertex : entry.getValue()) {
            Guards guards = nativeVertex.get(vertex);
            Map<String, Integer> theirs = guards != null ? guards.outputLocations : this.postOutputs.get(vertex);
            if (theirs == null) {
               // a vertex shader nothing provides: that pass cannot load whatever is done here
               continue;
            }

            partners++;
            only = guards;
            if (shared == null) {
               shared = new LinkedHashMap<>(theirs);
            } else {
               shared.entrySet().removeIf(e -> !e.getValue().equals(theirs.get(e.getKey())));
            }
         }

         if (shared == null) {
            continue;
         }

         VaryingPlan plan = partners == 1 && only != null ? LegacyTranslator.planVaryings(null, lf.text, only, true) : new VaryingPlan(shared, null);
         this.addSingle(kf, lf, Stage.FRAGMENT, plan, vanillaSrc.get(kf), this.traits(false, groups.get(kf), false, null));
      }
   }

   private void sameName(Map<Key, Src> top, Map<Key, String> vanillaSrc) {
      Set<Identifier> ids = new HashSet<>();
      top.keySet().forEach(k -> ids.add(k.id()));
      for (Identifier id : ids) {
         if (RENAMES.containsKey(id.toString())) {
            continue;
         }

         Key kv = new Key(id, ShaderType.VERTEX);
         Key kf = new Key(id, ShaderType.FRAGMENT);
         Src lv = this.unplanned(kv, top, vanillaSrc);
         Src lf = this.unplanned(kf, top, vanillaSrc);
         if (lv == null && lf == null) {
            continue;
         }

         VaryingPlan plan;
         if (lv != null && lf != null) {
            plan = LegacyTranslator.planVaryings(lv.text, lf.text, null, false);
         } else if (lv != null) {
            Src nf = top.get(kf);
            plan = LegacyTranslator.planVaryings(lv.text, null, nf != null && legacy(nf) == null ? Guards.scan(nf.text) : null, false);
         } else if (this.postOutputs.containsKey(id)) {
            plan = new VaryingPlan(this.postOutputs.get(id), null);
         } else {
            Src nv = top.get(kv);
            String counterpart = nv != null && legacy(nv) == null ? nv.text
               : id.getPath().startsWith("post/") ? vanillaSrc.get(new Key(Identifier.withDefaultNamespace("core/screenquad"), ShaderType.VERTEX)) : null;
            plan = LegacyTranslator.planVaryings(null, lf.text, counterpart != null ? Guards.scan(counterpart) : null, true);
         }

         if (lv != null) {
            this.addSingle(kv, lv, Stage.VERTEX, plan, vanillaSrc.get(kv), this.traits(guards(vanillaSrc.get(kf)), false));
         }

         if (lf != null) {
            Set<String> fed = this.postStages.contains(kf) || id.getPath().startsWith("post/") ? Set.of() : null;
            this.addSingle(kf, lf, Stage.FRAGMENT, plan, vanillaSrc.get(kf), this.traits(false, fed, false, guards(vanillaSrc.get(kv))));
         }
      }
   }

   /**
    * The old stage under this key that is still to be planned. One under a name nothing in 26.3 draws with (no
    * shader of the game or a mod has that name, no post effect names it) is noted and left alone.
    */
   private Src unplanned(Key key, Map<Key, Src> top, Map<Key, String> vanillaSrc) {
      Src src = legacy(top.get(key));
      if (src == null || this.result.translated.containsKey(key)) {
         return null;
      }

      if (!vanillaSrc.containsKey(key) && !this.postStages.contains(key)) {
         this.result.ignored.add(key.id() + "." + key.type().getName());
         return null;
      }

      return src;
   }

   private Traits traits(Guards otherStage, boolean foldedLightmap) {
      return this.traits(false, null, foldedLightmap, otherStage);
   }

   private Traits traits(boolean postVertex, Set<String> postGroups, boolean foldedLightmap, Guards otherStage) {
      return new Traits(postVertex, postGroups, foldedLightmap, otherStage, this.pack);
   }

   private static Guards guards(String text) {
      return text != null ? Guards.scan(text) : null;
   }

   private void addWrapped(Key key, List<Branch> branches, String vanillaText) {
      if (!branches.isEmpty()) {
         this.result.translated.put(key, LegacyTranslator.wrap(branches, LegacyTranslator.stripHeader(vanillaText)));
         this.result.vanilla.put(key, vanillaText);
         this.result.touchedShaders.add(key.id());
      }
   }

   private void addSingle(Key key, Src src, Stage stage, VaryingPlan plan, String vanillaText, Traits traits) {
      Guards vanillaTarget = vanillaText != null ? Guards.scan(vanillaText) : null;
      this.result.translated.put(key, LegacyTranslator.single(LegacyTranslator.translateBody(src.text, stage, plan, vanillaTarget, this.relative(src.location), traits)));
      if (vanillaText != null) {
         this.result.vanilla.put(key, vanillaText);
      }

      this.result.touchedShaders.add(key.id());
      this.result.packs.add(src.pack);
   }

   /** Resolves quoted #moj_import paths relative to the importing file and exposes them as includes. */
   private Function<String, String> relative(Identifier from) {
      return path -> {
         try {
            Identifier location = sibling(from, path);
            if (location == null) {
               return null;
            }

            Identifier id = Identifier.fromNamespaceAndPath(OWN_NAMESPACE, "rel/" + location.getNamespace() + "/" + location.getPath());
            if (this.result.includes.containsKey(id)) {
               return id.toString();
            }

            File file = this.files.get(location);
            if (file == null || file.text() == null) {
               return null;
            }

            this.result.includes.put(id, "");
            this.result.includes.put(id, LegacyTranslator.translateInclude(file.text(), id.toString(), this.relative(location), this.pack, null));
            return id.toString();
         } catch (RuntimeException e) {
            return null;
         }
      };
   }

   /** The file a path names relative to the folder of another one; null when it is no valid location. */
   private static Identifier sibling(Identifier from, String path) {
      String dir = from.getPath().contains("/") ? from.getPath().substring(0, from.getPath().lastIndexOf('/') + 1) : "";
      return Identifier.tryBuild(from.getNamespace(), normalizePath(dir + path.trim()));
   }

   private static String normalizePath(String path) {
      Deque<String> parts = new ArrayDeque<>();
      for (String part : path.split("/")) {
         if (part.equals("..")) {
            if (!parts.isEmpty()) {
               parts.removeLast();
            }
         } else if (!part.isEmpty() && !part.equals(".")) {
            parts.addLast(part);
         }
      }

      return String.join("/", parts);
   }

   private static boolean overriddenNatively(Src src) {
      return src != null && !"vanilla".equals(src.pack) && !(isUserPack(src.pack) ? src.legacy : LegacyTranslator.isLegacyShader(src.text));
   }

   private static Src legacy(Src src) {
      return src != null && src.legacy ? src : null;
   }

   private static boolean user(File file) {
      return file.text() != null && isUserPack(file.pack());
   }

   /** Only packs the player added or a server sent are rewritten; mods' own shaders are left alone. */
   static boolean isUserPack(String pack) {
      return pack != null && (pack.startsWith("file/") || pack.startsWith("server/"));
   }
}
