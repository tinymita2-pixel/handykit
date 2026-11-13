package mcport.logviewer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's log folder: log4j rolls latest.log into yyyy-MM-dd-N.log.gz at every start and at midnight,
 * so each archive holds one day of one session and latest.log is the session running now.
 */
final class LogStore {
   static final Logger LOGGER = LoggerFactory.getLogger("logviewer");
   private static final Pattern ARCHIVE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-(\\d{1,6})\\.log\\.gz");
   /** The connect screen's own line, "Connecting to host, port"; other mods also start lines with "Connecting to". */
   private static final Pattern CONNECTING = Pattern.compile("Connecting to (\\S+), (\\d+)");
   /** How much of a growing file is read at a time. */
   private static final int CHUNK = 4 << 20;
   /** A single line longer than this is cut into pieces rather than held in memory whole. */
   private static final int LONGEST_LINE = 64 << 20;

   record LogFile(Path path, int index, boolean live) {
   }

   record Day(LocalDate date, List<LogFile> files) {
      boolean live() {
         return this.files.getLast().live();
      }
   }

   /** Newest day first; a day's files in the order they were written, latest.log last. */
   static List<Day> index(Path dir) {
      Map<LocalDate, List<LogFile>> byDate = new TreeMap<>(Comparator.reverseOrder());
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
         for (Path path : stream) {
            String name = path.getFileName().toString();
            Matcher m = ARCHIVE.matcher(name);
            if (m.matches()) {
               try {
                  LocalDate date = LocalDate.parse(m.group(1));
                  byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(new LogFile(path, Integer.parseInt(m.group(2)), false));
               } catch (DateTimeParseException ignored) {
               }
            } else if (name.equals("latest.log")) {
               LocalDate date = LocalDate.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault());
               byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(new LogFile(path, Integer.MAX_VALUE, true));
            }
         }
      } catch (IOException e) {
         LOGGER.warn("Could not list {}", dir, e);
      }

      List<Day> days = new ArrayList<>(byDate.size());
      byDate.forEach((date, files) -> {
         files.sort(Comparator.comparingInt(LogFile::index));
         days.add(new Day(date, List.copyOf(files)));
      });
      return days;
   }

   /** Turns lines into entries; a line without a [hh:mm:ss] header continues the entry before it. */
   static final class Parser {
      /** Shown for lines written while a singleplayer world was open. */
      static final String SINGLEPLAYER = "singleplayer";
      private final int file;
      LogEntry last;
      /** Where the client is as of the last line: follows "Connecting to", tab switches and disconnects. */
      private String server = "";

      Parser(int file) {
         this.file = file;
      }

      /** Returns the new entry, or null when the line was added to the previous one. */
      LogEntry line(String raw) {
         if (!isHeader(raw)) {
            if (this.last != null) {
               this.last.addExtra(raw);
               return null;
            }

            return this.last = new LogEntry(this.file, "", LogEntry.INFO, false, LogEntry.sanitize(raw), raw);
         }

         // game: "[12:34:56] [Render thread/INFO]: msg", Fabric dev runs: "[12:34:56] [Render thread/INFO] (Minecraft) msg"
         String time = raw.substring(1, 9);
         byte level = LogEntry.INFO;
         String msg = raw.substring(Math.min(raw.length(), 11));
         int close = raw.length() > 12 && raw.charAt(10) == ' ' && raw.charAt(11) == '[' ? raw.indexOf(']', 12) : -1;
         if (close > 0) {
            String head = raw.substring(12, close);
            level = level(head.substring(head.lastIndexOf('/') + 1));
            if (raw.startsWith(":", close + 1)) {
               msg = raw.substring(Math.min(raw.length(), close + 3));
            } else if (raw.startsWith(" (", close + 1) && raw.indexOf(") ", close + 3) > 0) {
               msg = raw.substring(raw.indexOf(") ", close + 3) + 2);
            }
         }

         boolean chat = false;
         int tag = msg.indexOf("[CHAT] ");
         if (tag >= 0 && tag <= 24 && (tag == 0 || msg.charAt(0) == '[')) {
            chat = true;
            msg = msg.substring(tag + 7);
         }

         // chat of a server tab that was in the background carries its address: "\u27E6host\u27E7 text"
         String tagged = null;
         if (chat && msg.startsWith("\u27E6")) {
            int end = msg.indexOf("\u27E7 ");
            if (end > 0 && end < 160) {
               tagged = address(msg.substring(1, end));
               msg = msg.substring(end + 2);
            }
         }

         boolean left = false;
         if (!chat) {
            Matcher connecting = msg.startsWith("Connecting to ") ? CONNECTING.matcher(msg) : null;
            if (connecting != null && connecting.matches()) {
               this.server = address(connecting.group(1) + ":" + connecting.group(2));
            } else if (msg.startsWith("Tab on screen: ")) {
               String on = msg.substring(15).trim();
               this.server = on.equals("-") ? "" : address(on);
            } else if (msg.startsWith("Starting integrated minecraft server")) {
               this.server = SINGLEPLAYER;
            } else if (msg.startsWith("Client disconnected with reason") || msg.equals("Stopping!") || msg.equals("Stopping server")) {
               left = true;
            }
         }

         LogEntry e = new LogEntry(this.file, time, level, chat, LogEntry.sanitize(msg), raw);
         e.server = tagged != null ? tagged : this.server;
         if (left) {
            // the line that says so still belongs to the server that was left
            this.server = "";
         }

         return this.last = e;
      }

      /** "Host:25565" and "host" are the same server. */
      static String address(String s) {
         String a = s.trim().toLowerCase(java.util.Locale.ROOT);
         return a.endsWith(":25565") ? a.substring(0, a.length() - 6) : a;
      }

      private static boolean isHeader(String s) {
         return s.length() >= 10 && s.charAt(0) == '[' && s.charAt(3) == ':' && s.charAt(6) == ':' && s.charAt(9) == ']'
            && digit(s, 1) && digit(s, 2) && digit(s, 4) && digit(s, 5) && digit(s, 7) && digit(s, 8);
      }

      private static boolean digit(String s, int i) {
         char c = s.charAt(i);
         return c >= '0' && c <= '9';
      }

      private static byte level(String name) {
         return switch (name) {
            case "WARN" -> LogEntry.WARN;
            case "ERROR", "FATAL" -> LogEntry.ERROR;
            case "DEBUG", "TRACE" -> LogEntry.DEBUG;
            default -> LogEntry.INFO;
         };
      }
   }

   static void readAll(Path path, Consumer<String> lines) throws IOException {
      boolean gz = path.getFileName().toString().endsWith(".gz");
      // the file itself is closed also when the archive is cut short and the unzipper refuses it
      try (
         InputStream raw = Files.newInputStream(path);
         InputStream in = gz ? new GZIPInputStream(raw, 1 << 16) : raw;
         BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)
      ) {
         for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            lines.accept(line);
         }
      }
   }

   /**
    * Reads the complete lines of a growing file from offset on and returns the offset after them, or -1 when the
    * file got shorter (log4j rolled it into an archive). A half-written last line is left for the next read.
    * NIO opens with delete sharing, so log4j can still rename the file while it is open here.
    */
   static long readLive(Path path, long offset, Consumer<String> lines) throws IOException {
      try (SeekableByteChannel channel = Files.newByteChannel(path)) {
         long size = channel.size();
         if (size < offset) {
            return -1;
         }

         if (size == offset) {
            return offset;
         }

         // a piece at a time: a log some mod filled with hundreds of megabytes is never held in memory whole
         ByteBuffer buf = ByteBuffer.allocate((int)Math.min(size - offset, CHUNK));
         long done = offset;
         channel.position(offset);
         while (true) {
            boolean more = true;
            while (buf.hasRemaining() && more) {
               more = channel.read(buf) > 0;
            }

            byte[] bytes = buf.array();
            int filled = buf.position();
            int end = filled - 1;
            while (end >= 0 && bytes[end] != '\n') {
               end--;
            }

            if (end < 0) {
               if (more && filled == bytes.length) {
                  // one line longer than the buffer: more room, or past the limit a cut in the middle of it
                  if (bytes.length < LONGEST_LINE) {
                     buf = ByteBuffer.allocate(Math.min(bytes.length * 2, LONGEST_LINE)).put(bytes, 0, filled);
                     continue;
                  }

                  end = filled - 1;
               } else {
                  // what is left is a line still being written
                  return done;
               }
            }

            new String(bytes, 0, end + 1, StandardCharsets.UTF_8).lines().forEach(lines);
            done += end + 1;
            // the unfinished line after the last line break goes to the front, the next piece is read behind it
            buf.clear();
            buf.put(bytes, end + 1, filled - end - 1);
            if (!more) {
               return done;
            }
         }
      }
   }

   /** Entries of the day that pass the test, or -1 when cancelled. */
   static int count(Day day, Predicate<LogEntry> test, BooleanSupplier cancelled) {
      int[] n = {0};
      int[] seen = {0};
      for (int i = 0; i < day.files().size(); i++) {
         Parser parser = new Parser(i);
         LogEntry[] prev = {null};
         try {
            readAll(day.files().get(i).path(), line -> {
               if ((++seen[0] & 4095) == 0 && cancelled.getAsBoolean()) {
                  throw new Cancelled();
               }

               LogEntry e = parser.line(line);
               if (e != null) {
                  if (prev[0] != null && test.test(prev[0])) {
                     n[0]++;
                  }

                  prev[0] = e;
               }
            });
         } catch (Cancelled c) {
            return -1;
         } catch (IOException e) {
            LOGGER.warn("Could not read {}", day.files().get(i).path(), e);
         }

         if (prev[0] != null && test.test(prev[0])) {
            n[0]++;
         }
      }

      return n[0];
   }

   static final class Cancelled extends RuntimeException {
      Cancelled() {
         super(null, null, false, false);
      }
   }
}
