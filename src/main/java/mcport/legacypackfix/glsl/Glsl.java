package mcport.legacypackfix.glsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small line-oriented GLSL helpers; enough for the global declarations Minecraft shaders use. */
public final class Glsl {
   /** A `layout(std140) uniform Name { members } [instance];` declaration, and where it stands in the text. */
   public record Block(String name, String members, String instance, int start, int end) {
   }

   /** A function the text gives a body to: its name and the whole definition. */
   public record Function(String name, String text) {
   }

   /** One global in/out declarator. location is -1 when no layout(location) is given. */
   public record Decl(String direction, String qualifiers, String type, String name, int arraySize, int location) {
   }

   private static final Pattern INTERFACE = Pattern.compile(
      "^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\\s+)*)(in|out)\\s+((?:(?:flat|smooth|noperspective|centroid|highp|mediump|lowp)\\s+)*)(\\w+)\\s+([^;{]+);"
   );
   private static final Pattern LOCATION = Pattern.compile("location\\s*=\\s*(\\d+)");
   private static final Pattern DECLARATOR = Pattern.compile("^\\s*(\\w+)\\s*(?:\\[\\s*(\\w*)\\s*\\])?\\s*$");
   private static final Pattern INT_MACRO = Pattern.compile("^\\s*#\\s*define\\s+(\\w+)\\s+\\(?\\s*(\\d+)\\s*\\)?\\s*$");
   private static final Pattern INT_CONST = Pattern.compile("^\\s*const\\s+(?:(?:highp|mediump|lowp)\\s+)?int\\s+(\\w+)\\s*=\\s*(\\d+)\\s*;\\s*$");
   private static final Pattern FUNCTION = Pattern.compile(
      "(?m)^[ \\t]*(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)\\s*\\(([^(){};]*)\\)\\s*\\{"
   );
   private static final Pattern BLOCK = Pattern.compile("layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)\\s*\\{([^{}]*)\\}\\s*(\\w*)\\s*;");
   private static final Set<String> NOT_A_TYPE = Set.of("else", "return", "do", "case", "in", "out", "inout", "const", "uniform");
   private static final Set<String> NOT_A_FUNCTION = Set.of("if", "for", "while", "switch", "layout");

   private Glsl() {
   }

   public static String stripLineComment(String line) {
      int i = line.indexOf("//");
      return i >= 0 ? line.substring(0, i) : line;
   }

   /** Removes // and block comments, keeping line breaks so line structure survives. */
   public static String stripComments(String src) {
      StringBuilder out = new StringBuilder(src.length());
      int i = 0;
      int n = src.length();
      while (i < n) {
         char c = src.charAt(i);
         if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
            while (i < n && src.charAt(i) != '\n') {
               i++;
            }
         } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
            i += 2;
            while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
               if (src.charAt(i) == '\n') {
                  out.append('\n');
               }

               i++;
            }

            i = Math.min(n, i + 2);
            out.append(' ');
         } else {
            out.append(c);
            i++;
         }
      }

      return out.toString();
   }

   public static String normalize(String src) {
      if (!src.isEmpty() && src.charAt(0) == '﻿') {
         src = src.substring(1);
      }

      // glslang refuses a backslash line continuation at #version 330; the old GL drivers took it
      return src.replace("\r\n", "\n").replace('\r', '\n').replace("\\\n", "");
   }

   private static final Pattern MULTI_UNIFORM = Pattern.compile(
      "^(\\s*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?\\w+)\\s+(\\w+(?:\\s*\\[\\s*\\w*\\s*\\])?(?:\\s*,\\s*\\w+(?:\\s*\\[\\s*\\w*\\s*\\])?)+)\\s*;\\s*$"
   );

   /** `uniform sampler2D A, B;` becomes one global line per name, which is what the line rules expect. */
   public static String splitUniforms(String src) {
      if (!src.contains(",")) {
         return src;
      }

      StringBuilder out = new StringBuilder(src.length() + 32);
      int depth = 0;
      String[] lines = src.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
         String line = lines[i];
         Matcher m = depth == 0 && !line.stripLeading().startsWith("#") ? MULTI_UNIFORM.matcher(line) : null;
         if (m != null && m.find()) {
            String[] names = m.group(2).split(",");
            for (int n = 0; n < names.length; n++) {
               out.append(m.group(1)).append(' ').append(names[n].trim()).append(';');
               if (n < names.length - 1) {
                  out.append('\n');
               }
            }
         } else {
            out.append(line);
            if (!line.stripLeading().startsWith("#")) {
               depth = Math.max(0, depth + braceDelta(line));
            }
         }

         if (i < lines.length - 1) {
            out.append('\n');
         }
      }

      return out.toString();
   }

   /**
    * Puts every global statement on its own line. Minified shaders (BetterHud) chain declarations and whole
    * functions on one line, and the translator rewrites globals line by line. Function bodies and
    * preprocessor lines are left alone.
    */
   public static String splitTopLevel(String src) {
      StringBuilder out = new StringBuilder(src.length() + 64);
      int depth = 0;
      boolean functionBody = false;
      for (String line : src.split("\n", -1)) {
         if (out.length() > 0) {
            out.append('\n');
         }

         if (line.stripLeading().startsWith("#")) {
            out.append(line);
            continue;
         }

         int start = 0;
         for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean split = false;
            if (c == '{') {
               if (depth == 0) {
                  functionBody = line.substring(start, i).stripTrailing().endsWith(")");
               }

               depth++;
            } else if (c == '}') {
               depth = Math.max(0, depth - 1);
               split = depth == 0 && functionBody;
            } else if (c == ';') {
               split = depth == 0;
            }

            if (split && !line.substring(i + 1).isBlank()) {
               out.append(line, start, i + 1).append('\n');
               start = i + 1;
               while (start < line.length() && line.charAt(start) == ' ') {
                  start++;
               }
            }
         }

         out.append(line, start, line.length());
      }

      return out.toString();
   }

   public static int braceDelta(String line) {
      int d = 0;
      for (int i = 0; i < line.length(); i++) {
         char c = line.charAt(i);
         if (c == '{') {
            d++;
         } else if (c == '}') {
            d--;
         }
      }

      return d;
   }

   /** Records `#define N 2` and `const int N = 2;`: names an array size may be given by. */
   public static void noteConstant(String line, Map<String, Integer> constants) {
      Matcher m = INT_MACRO.matcher(line);
      if (m.find() || (m = INT_CONST.matcher(line)).find()) {
         try {
            constants.put(m.group(1), Integer.parseInt(m.group(2)));
         } catch (NumberFormatException e) {
            // not a size anyone can declare
         }
      }
   }

   /** The functions a (comment-free) text defines, whole. */
   public static List<Function> functionDefinitions(String src) {
      List<Function> out = new ArrayList<>();
      Matcher m = FUNCTION.matcher(src);
      int from = 0;
      while (m.find(from)) {
         from = m.end();
         if (NOT_A_TYPE.contains(m.group(1)) || NOT_A_FUNCTION.contains(m.group(2))) {
            continue;
         }

         int depth = 0;
         int close = -1;
         for (int i = m.end() - 1; i < src.length() && close < 0; i++) {
            char c = src.charAt(i);
            if (c == '{') {
               depth++;
            } else if (c == '}' && --depth == 0) {
               close = i;
            }
         }

         if (close < 0) {
            break;
         }

         out.add(new Function(m.group(2), src.substring(m.start(), close + 1).strip()));
         from = close + 1;
      }

      return out;
   }

   /** The std140 uniform blocks a (comment-free) text declares. */
   public static List<Block> uniformBlocks(String src) {
      List<Block> out = new ArrayList<>();
      if (!src.contains("std140")) {
         return out;
      }

      Matcher m = BLOCK.matcher(src);
      while (m.find()) {
         out.add(new Block(m.group(1), m.group(2), m.group(3), m.start(), m.end()));
      }

      return out;
   }

   /** Whether the identifier occurs in the text. */
   public static boolean mentions(String text, String word) {
      int from = 0;
      int at;
      while ((at = text.indexOf(word, from)) >= 0) {
         int end = at + word.length();
         if ((at == 0 || !isWordChar(text.charAt(at - 1))) && (end == text.length() || !isWordChar(text.charAt(end)))) {
            return true;
         }

         from = end;
      }

      return false;
   }

   public static boolean isWordChar(char c) {
      return c == '_' || Character.isLetterOrDigit(c);
   }

   /** Whether the text calls (or defines) a function of this name. */
   public static boolean calls(String text, String name) {
      return Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(text).find();
   }

   /** Return types of the functions the text gives a body to, by name; the first definition of a name counts. */
   public static Map<String, String> functionTypes(String text) {
      Map<String, String> out = new LinkedHashMap<>();
      Matcher m = FUNCTION.matcher(text);
      while (m.find()) {
         if (!NOT_A_TYPE.contains(m.group(1)) && !NOT_A_FUNCTION.contains(m.group(2))) {
            out.putIfAbsent(m.group(2), m.group(1));
         }
      }

      return out;
   }

   /** Parses a global `[layout(...)] [qualifiers] in|out type a[, b[2]];` line. */
   public static List<Decl> parseInterface(String line) {
      return parseInterface(line, Map.of());
   }

   /** The same, with array sizes that are given by a named constant ({@link #noteConstant}). */
   public static List<Decl> parseInterface(String line, Map<String, Integer> constants) {
      List<Decl> out = new ArrayList<>();
      Matcher m = INTERFACE.matcher(line);
      if (!m.find()) {
         return out;
      }

      String layout = m.group(1);
      int location = -1;
      if (layout != null) {
         Matcher lm = LOCATION.matcher(layout);
         if (lm.find()) {
            location = Integer.parseInt(lm.group(1));
         }
      }

      String qualifiers = (m.group(2) + m.group(4)).trim().replaceAll("\\s+", " ");
      String direction = m.group(3);
      String type = m.group(5);
      if (type.equals("uniform") || type.equals("buffer")) {
         return out;
      }

      for (String part : m.group(6).split(",")) {
         Matcher dm = DECLARATOR.matcher(part);
         if (!dm.find()) {
            return new ArrayList<>();
         }

         int array;
         String size = dm.group(2);
         if (size == null) {
            array = 0;
         } else if (size.isEmpty()) {
            array = 1;
         } else if (Character.isDigit(size.charAt(0))) {
            try {
               array = Integer.parseInt(size);
            } catch (NumberFormatException e) {
               return new ArrayList<>();
            }
         } else if (constants.containsKey(size)) {
            array = constants.get(size);
         } else {
            // a size nothing here can tell: left to the compiler, as before
            return new ArrayList<>();
         }

         out.add(new Decl(direction, qualifiers, type, dm.group(1), array, location));
         if (location >= 0) {
            location += locationSize(type, array);
         }
      }

      return out;
   }

   /** Number of interface locations a type consumes. */
   public static int locationSize(String type, int arraySize) {
      int base = switch (type) {
         case "mat2", "mat2x2", "mat2x3", "mat2x4" -> 2;
         case "mat3", "mat3x2", "mat3x3", "mat3x4" -> 3;
         case "mat4", "mat4x2", "mat4x3", "mat4x4" -> 4;
         default -> 1;
      };
      return base * Math.max(1, arraySize);
   }

   /** A zero value expression for a scalar/vector type, used for stand-in constants. */
   public static String zero(String type) {
      return switch (type) {
         case "float" -> "0.0";
         case "int" -> "0";
         case "uint" -> "0u";
         case "bool" -> "false";
         default -> type + "(0)";
      };
   }

   /** Finds the index of the parenthesis closing the one at {@code open}. */
   public static int matchParen(String s, int open) {
      int depth = 0;
      for (int i = open; i < s.length(); i++) {
         char c = s.charAt(i);
         if (c == '(') {
            depth++;
         } else if (c == ')') {
            depth--;
            if (depth == 0) {
               return i;
            }
         }
      }

      return -1;
   }

   /** Splits a call's argument text at top-level commas. */
   public static List<String> splitArgs(String args) {
      List<String> out = new ArrayList<>();
      int depth = 0;
      int start = 0;
      for (int i = 0; i < args.length(); i++) {
         char c = args.charAt(i);
         if (c == '(' || c == '[') {
            depth++;
         } else if (c == ')' || c == ']') {
            depth--;
         } else if (c == ',' && depth == 0) {
            out.add(args.substring(start, i).trim());
            start = i + 1;
         }
      }

      out.add(args.substring(start).trim());
      return out;
   }
}
