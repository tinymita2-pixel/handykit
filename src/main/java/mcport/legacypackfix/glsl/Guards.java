package mcport.legacypackfix.glsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the preprocessor condition each line of a (26.3) shader lives under, so declarations
 * that vanilla only makes for some pipeline variants can be guarded the same way in old shaders.
 */
public final class Guards {
   private static final Pattern IFDEF = Pattern.compile("^\\s*#\\s*ifdef\\s+(\\w+)");
   private static final Pattern IFNDEF = Pattern.compile("^\\s*#\\s*ifndef\\s+(\\w+)");
   private static final Pattern IF = Pattern.compile("^\\s*#\\s*if\\s+(.+)$");
   private static final Pattern ELIF = Pattern.compile("^\\s*#\\s*elif\\s+(.+)$");
   private static final Pattern ELSE = Pattern.compile("^\\s*#\\s*else\\b");
   private static final Pattern ENDIF = Pattern.compile("^\\s*#\\s*endif\\b");
   private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"]");
   private static final Pattern SAMPLER = Pattern.compile("^\\s*uniform\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?(\\w*sampler\\w*)\\s+(\\w+)\\s*;");

   /** name -> condition (null = unconditional) for the declaration kinds we mirror. */
   public final Map<String, String> samplers = new HashMap<>();
   public final Map<String, String> includes = new HashMap<>();
   public final Map<String, String> inputs = new HashMap<>();
   public final Map<String, String> outputs = new HashMap<>();
   /** name -> location for layout-qualified inputs / outputs. */
   public final Map<String, Integer> inputLocations = new HashMap<>();
   public final Map<String, Integer> outputLocations = new HashMap<>();
   /** name -> number of locations, for layout-qualified declarations. */
   public final Map<String, Integer> sizes = new HashMap<>();
   /** name -> type of the inputs that are no arrays. */
   public final Map<String, String> inputTypes = new HashMap<>();
   /** name -> members, as written, of the std140 uniform blocks the shader declares itself. */
   public final Map<String, String> blocks = new HashMap<>();

   private Guards() {
   }

   private static final class Frame {
      final List<String> previous = new ArrayList<>();
      String current;
      boolean includeGuard;
   }

   public static Guards scan(String source) {
      Guards guards = new Guards();
      Deque<Frame> stack = new ArrayDeque<>();
      int depth = 0;
      String text = Glsl.stripComments(Glsl.normalize(source));
      for (Glsl.Block block : Glsl.uniformBlocks(text)) {
         guards.blocks.put(block.name(), block.members());
      }

      for (String raw : text.split("\n", -1)) {
         String line = Glsl.stripLineComment(raw);
         Matcher m;
         if ((m = IFDEF.matcher(line)).find()) {
            stack.push(frame("defined(" + m.group(1) + ")", false));
         } else if ((m = IFNDEF.matcher(line)).find()) {
            // #ifndef FOO_GLSL / #define FOO_GLSL include guards are not real variant conditions
            stack.push(frame("!defined(" + m.group(1) + ")", m.group(1).endsWith("_GLSL")));
         } else if ((m = IF.matcher(line)).find()) {
            stack.push(frame("(" + m.group(1).trim() + ")", false));
         } else if ((m = ELIF.matcher(line)).find() && !stack.isEmpty()) {
            Frame f = stack.peek();
            f.previous.add(f.current);
            f.current = "(" + m.group(1).trim() + ")";
         } else if (ELSE.matcher(line).find() && !stack.isEmpty()) {
            Frame f = stack.peek();
            f.previous.add(f.current);
            f.current = null;
         } else if (ENDIF.matcher(line).find() && !stack.isEmpty()) {
            stack.pop();
         } else if (!line.trim().startsWith("#")) {
            if (depth == 0) {
               String cond = condition(stack);
               if ((m = SAMPLER.matcher(line)).find()) {
                  guards.samplers.put(m.group(2), cond);
               }

               for (Glsl.Decl d : Glsl.parseInterface(line)) {
                  if (d.direction().equals("in")) {
                     guards.inputs.put(d.name(), cond);
                     if (d.arraySize() == 0) {
                        guards.inputTypes.put(d.name(), d.type());
                     }

                     if (d.location() >= 0) {
                        guards.inputLocations.put(d.name(), d.location());
                        guards.sizes.put(d.name(), Glsl.locationSize(d.type(), d.arraySize()));
                     }
                  } else {
                     guards.outputs.put(d.name(), cond);
                     if (d.location() >= 0) {
                        guards.outputLocations.put(d.name(), d.location());
                        guards.sizes.put(d.name(), Glsl.locationSize(d.type(), d.arraySize()));
                     }
                  }
               }
            }

            depth = Math.max(0, depth + Glsl.braceDelta(line));
         } else if ((m = INCLUDE.matcher(line)).find()) {
            guards.includes.put(m.group(1), condition(stack));
         }
      }

      return guards;
   }

   private static Frame frame(String cond, boolean includeGuard) {
      Frame f = new Frame();
      f.current = cond;
      f.includeGuard = includeGuard;
      return f;
   }

   private static String condition(Deque<Frame> stack) {
      List<String> parts = new ArrayList<>();
      for (Frame f : stack) {
         if (f.includeGuard) {
            continue;
         }

         for (String prev : f.previous) {
            parts.add("!(" + prev + ")");
         }

         if (f.current != null) {
            parts.add(f.current);
         }
      }

      return parts.isEmpty() ? null : String.join(" && ", parts);
   }
}
