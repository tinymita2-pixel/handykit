package mcport.logviewer;

import java.util.ArrayList;
import java.util.Locale;

/** One log record: its first line plus any continuation lines (stack traces, mod lists) written under it. */
final class LogEntry {
   static final byte DEBUG = 0;
   static final byte INFO = 1;
   static final byte WARN = 2;
   static final byte ERROR = 3;

   /** Which session (file) of the day this came from, in day order. */
   final int file;
   final String time;
   final byte level;
   final boolean chat;
   /** First line as shown: no thread/level header, chat lines without their [System] [CHAT] tags. */
   final String text;
   /** First line exactly as written to the file. */
   final String raw;
   /** The server the client was on (or the background tab the line came from); empty in the menus. */
   String server = "";
   private ArrayList<String> extra;
   /** The continuation lines made safe to draw; null while every one of them is safe as written. */
   private ArrayList<String> shown;
   private String lower;
   /** Row ends of the first line for wrapWidth (null: one row), kept so filtering again needs no font work. */
   int wrapWidth = -1;
   int[] wrapEnds;

   LogEntry(int file, String time, byte level, boolean chat, String text, String raw) {
      this.file = file;
      this.time = time;
      this.level = level;
      this.chat = chat;
      this.text = text;
      this.raw = raw;
   }

   void addExtra(String line) {
      if (this.extra == null) {
         this.extra = new ArrayList<>(2);
      }

      // made safe once here, not on every frame for every row on screen
      String safe = sanitize(line);
      if (this.shown != null) {
         this.shown.add(safe);
      } else if (safe != line) {
         this.shown = new ArrayList<>(this.extra);
         this.shown.add(safe);
      }

      this.extra.add(line);
      this.lower = null;
   }

   int extraCount() {
      return this.extra == null ? 0 : this.extra.size();
   }

   /** Line 0 is the first line, 1.. are the continuation lines, all made safe to draw. */
   String display(int line) {
      return line == 0 ? this.text : (this.shown != null ? this.shown : this.extra).get(line - 1);
   }

   private String lower() {
      if (this.lower == null) {
         StringBuilder sb = new StringBuilder(this.time.length() + this.text.length() + 1).append(this.time).append(' ').append(this.text);
         if (this.extra != null) {
            for (String line : this.extra) {
               sb.append('\n').append(line);
            }
         }

         this.lower = sb.toString().toLowerCase(Locale.ROOT);
      }

      return this.lower;
   }

   /** Every term must appear somewhere in the time, the shown first line or the continuation lines. */
   boolean matches(String[] terms) {
      if (terms.length == 0) {
         return true;
      }

      String l = this.lower();
      for (String term : terms) {
         if (!l.contains(term)) {
            return false;
         }
      }

      return true;
   }

   /** True when some term is found only in the continuation lines, which a collapsed entry hides. */
   boolean matchHidden(String[] terms) {
      if (terms.length == 0 || this.extra == null) {
         return false;
      }

      String l = this.lower();
      int firstEnd = this.time.length() + 1 + this.text.length();
      for (String term : terms) {
         int i = l.indexOf(term);
         if (i < 0 || i + term.length() > firstEnd) {
            return true;
         }
      }

      return false;
   }

   String copyText() {
      if (this.extra == null) {
         return this.raw;
      }

      StringBuilder sb = new StringBuilder(this.raw);
      for (String line : this.extra) {
         sb.append('\n').append(line);
      }

      return sb.toString();
   }

   /**
    * Tabs become spaces, other control characters (NULs from crashed writes, escapes) blanks, and private-use
    * characters (server pack icons, HUD images, negative spaces) a box, so every row keeps text height and width.
    */
   static String sanitize(String s) {
      int n = s.length();
      int i = 0;
      while (i < n && plain(s.charAt(i))) {
         i++;
      }

      if (i == n) {
         return s;
      }

      StringBuilder sb = new StringBuilder(n + 8).append(s, 0, i);
      for (; i < n; i++) {
         char c = s.charAt(i);
         if (plain(c)) {
            sb.append(c);
         } else if (c == '\t') {
            sb.append("    ");
         } else if (c < ' ') {
            sb.append(' ');
         } else {
            sb.append('□');
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
               i++;
            }
         }
      }

      return sb.toString();
   }

   /** Not a control character, not in the BMP private use area, not the start of a plane 15/16 private use pair. */
   private static boolean plain(char c) {
      return c >= ' ' && (c < '' || c > '') && (c < '\uDB80' || c > '\uDBFF');
   }
}
