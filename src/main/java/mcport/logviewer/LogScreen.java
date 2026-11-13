package mcport.logviewer;

import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Left: days. Top: search and the level filter. Middle: the chosen day, live when it is today's latest.log. */
public final class LogScreen extends Screen {
   private static final boolean DEBUG = Boolean.getBoolean("logviewer.debug");
   private static final int PAD = 6;
   private static final int BAR = 18;
   private static final int TOP = 30;
   private static final int ROW_H = 10;
   private static final int DATE_H = 12;
   private static final int MAX_GLYPH = 10;

   private static final int C_TIME = 0xFF6E6E6E;
   private static final int C_INFO = 0xFFA8A8A8;
   private static final int C_DEBUG = 0xFF7A7A7A;
   private static final int C_CHAT = 0xFFFFFFFF;
   private static final int C_WARN = 0xFFF0C24E;
   private static final int C_ERROR = 0xFFFF6E6E;
   private static final int C_EXTRA = 0xFF8C8C8C;
   private static final int C_EXTRA_ERROR = 0xFFC88080;
   private static final int C_MATCH = 0xB0806000;

   enum Filter {
      ALL("all"),
      CHAT("chat"),
      WARN("warn"),
      ERROR("error");

      final Component label;

      Filter(String key) {
         this.label = Component.translatable("logviewer.filter." + key);
      }

      boolean test(LogEntry e) {
         return switch (this) {
            case ALL -> true;
            case CHAT -> e.chat;
            case WARN -> e.level >= LogEntry.WARN;
            case ERROR -> e.level >= LogEntry.ERROR;
         };
      }
   }

   private static final ExecutorService COUNTER = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "logviewer-count");
      // normal priority: a whole history takes about a second, and the lowest priority starved behind rendering
      t.setDaemon(true);
      return t;
   });

   /** Reads the chosen day: a busy day is tens of megabytes to unpack and parse, which is not for the render thread. */
   private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "logviewer-load");
      t.setDaemon(true);
      return t;
   });

   // kept between openings so the key toggles back to where you were
   private static String savedQuery = "";
   private static Filter savedFilter = Filter.ALL;
   private static @Nullable LocalDate savedDay;
   private static boolean savedDayWasLive = true;

   private final @Nullable Screen parent;
   private final Path dir;
   private List<LogStore.Day> days = List.of();
   /** Date list rows: a day index, or -year for a year heading. */
   private final IntArrayList dateItems = new IntArrayList();
   private int dayIndex = -1;
   /** The day whose lines are shown. It trails dayIndex, the day chosen, while that one is being read. */
   private int shownIndex = -1;
   /** Counts the days chosen: a day that finishes reading after another was chosen is dropped. */
   private final AtomicInteger loadGen = new AtomicInteger();
   /** "MM-dd " and the weekday of every day in the list, made once instead of on every frame. */
   private String[] dayLabels = new String[0];
   private String[] dayWeekdays = new String[0];
   /** The widest advance of a plain ASCII character in the font in use. */
   private int asciiW = MAX_GLYPH;
   private final List<LogEntry> entries = new ArrayList<>();
   private LogStore.@Nullable Parser liveParser;
   private long liveOffset = -1;
   private int ticks;

   private final List<LogEntry> view = new ArrayList<>();
   private final IntArrayList rowView = new IntArrayList();
   private final IntArrayList rowLine = new IntArrayList();
   private final IntArrayList rowFrom = new IntArrayList();
   private final IntArrayList rowTo = new IntArrayList();
   private final Set<LogEntry> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
   private @Nullable LogEntry selected;
   private @Nullable LogEntry flash;
   private boolean flashAll;
   private long flashUntil;
   private int scroll = Integer.MAX_VALUE;
   private double scrollRest;
   private int dateScroll;
   private boolean dragBar;
   /** Date list rows at or below minus this are servers of the shown day. */
   private static final int SERVER_ITEM = 1_000_000;
   private static final int C_SERVER = 0xFF8FC0FF;
   /** Only lines of this server (of the shown day), or all. */
   private @Nullable String serverFilter;
   /** Servers of the shown day in order of first appearance, and how many entries each has. */
   private final List<String> dayServers = new ArrayList<>();
   private final IntArrayList dayServerCounts = new IntArrayList();
   /** Names on the "now on this server" rows (rowView -2), indexed by rowFrom. */
   private final List<String> dividers = new ArrayList<>();
   /** Showing a search hit in its place: every line of the day, until Esc goes back to the hits. */
   private boolean context;
   private int contextBack;

   private String query;
   private String[] terms;
   private Filter filter;
   private long applyAt = -1;
   private final AtomicInteger countGen = new AtomicInteger();
   private volatile Map<LocalDate, Integer> counts = Map.of();

   private EditBox search;
   private int dateW;
   private int logX;
   private int logW;
   private int logTop;
   private int logBottom;
   private int barX;
   private int timeW;
   private int chipsX;
   private Locale weekdayLocale = Locale.KOREAN;

   public LogScreen(@Nullable Screen parent) {
      super(Component.translatable("logviewer.title"));
      this.parent = parent;
      this.dir = this.minecraft.gameDirectory.toPath().resolve("logs");
      this.query = savedQuery;
      this.terms = split(savedQuery);
      this.filter = savedFilter;
      this.reindex(savedDayWasLive ? null : savedDay);
   }

   // ---------------------------------------------------------------- data

   private void reindex(@Nullable LocalDate keep) {
      this.days = LogStore.index(this.dir);
      // the lines on screen belong to a day of the list before this one
      this.shownIndex = -1;
      this.labelDays();
      this.rebuildDateItems();
      int target = 0;
      for (int i = 0; i < this.days.size(); i++) {
         if (this.days.get(i).date().equals(keep)) {
            target = i;
         }
      }

      this.selectDay(this.days.isEmpty() ? -1 : target);
      this.startCount();
   }

   private void labelDays() {
      int n = this.days.size();
      this.dayLabels = new String[n];
      this.dayWeekdays = new String[n];
      for (int i = 0; i < n; i++) {
         LocalDate date = this.days.get(i).date();
         this.dayLabels[i] = String.format("%02d-%02d ", date.getMonthValue(), date.getDayOfMonth());
         this.dayWeekdays[i] = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, this.weekdayLocale);
      }
   }

   /** Years, days, and under the shown day the servers the client was on that day. */
   private void rebuildDateItems() {
      this.dateItems.clear();
      int year = LocalDate.now().getYear();
      for (int i = 0; i < this.days.size(); i++) {
         LocalDate date = this.days.get(i).date();
         if (date.getYear() != year) {
            year = date.getYear();
            this.dateItems.add(-year);
         }

         this.dateItems.add(i);
         if (i == this.shownIndex) {
            for (int k = 0; k < this.dayServers.size(); k++) {
               this.dateItems.add(-(SERVER_ITEM + k));
            }
         }
      }
   }

   private void indexServers() {
      this.dayServers.clear();
      this.dayServerCounts.clear();
      for (LogEntry e : this.entries) {
         this.countServer(e);
      }

      if (this.serverFilter != null && !this.dayServers.contains(this.serverFilter)) {
         this.serverFilter = null;
      }
   }

   /** Returns true when the entry's server is new for the day. */
   private boolean countServer(LogEntry e) {
      if (e.server.isEmpty()) {
         return false;
      }

      int k = this.dayServers.indexOf(e.server);
      if (k < 0) {
         this.dayServers.add(e.server);
         this.dayServerCounts.add(1);
         return true;
      }

      this.dayServerCounts.set(k, this.dayServerCounts.getInt(k) + 1);
      return false;
   }

   private void setServer(@Nullable String server) {
      this.serverFilter = server != null && server.equals(this.serverFilter) ? null : server;
      this.context = false;
      this.rebuildView();
      this.scrollToStart();
   }

   private static String serverLabel(String server) {
      return server.equals(LogStore.Parser.SINGLEPLAYER) ? Component.translatable("handykit.tabs.singleplayer").getString() : server;
   }

   /**
    * Chooses a day. Its files are read and parsed on the loader thread; the lines on screen stay until the new
    * ones are there (the old day is no longer followed meanwhile).
    */
   private void selectDay(int index) {
      long started = System.nanoTime();
      this.dayIndex = index;
      this.liveParser = null;
      this.liveOffset = -1;
      int gen = this.loadGen.incrementAndGet();
      if (index < 0) {
         this.showDay(index, List.of(), null, -1, started);
         return;
      }

      LogStore.Day day = this.days.get(index);
      savedDay = day.date();
      savedDayWasLive = day.live();
      LOADER.execute(() -> {
         List<LogEntry> loaded = new ArrayList<>();
         LogStore.Parser live = null;
         long offset = -1;
         int[] seen = {0};
         try {
            for (int i = 0; i < day.files().size(); i++) {
               LogStore.LogFile file = day.files().get(i);
               LogStore.Parser parser = new LogStore.Parser(i);
               Consumer<String> add = line -> {
                  if ((++seen[0] & 4095) == 0 && this.loadGen.get() != gen) {
                     // another day was chosen meanwhile
                     throw new LogStore.Cancelled();
                  }

                  LogEntry e = parser.line(line);
                  if (e != null) {
                     loaded.add(e);
                  }
               };
               try {
                  if (file.live()) {
                     live = parser;
                     offset = Math.max(0, LogStore.readLive(file.path(), 0, add));
                  } else {
                     LogStore.readAll(file.path(), add);
                  }
               } catch (IOException e) {
                  LogStore.LOGGER.warn("Could not read {}", file.path(), e);
               }
            }
         } catch (LogStore.Cancelled c) {
            return;
         } catch (RuntimeException e) {
            // what was read so far is shown rather than nothing
            LogStore.LOGGER.warn("Could not read all of the log of {}", day.date(), e);
         }

         LogStore.Parser liveParser = live;
         long liveOffset = offset;
         this.minecraft.execute(() -> {
            if (this.loadGen.get() == gen) {
               this.showDay(index, loaded, liveParser, liveOffset, started);
            }
         });
      });
   }

   /** The chosen day has been read: its lines take the place of the ones on screen. */
   private void showDay(int index, List<LogEntry> loaded, LogStore.@Nullable Parser live, long offset, long started) {
      this.shownIndex = index;
      this.entries.clear();
      this.entries.addAll(loaded);
      this.expanded.clear();
      this.selected = null;
      this.liveParser = live;
      this.liveOffset = offset;
      this.serverFilter = null;
      this.context = false;
      this.indexServers();
      this.rebuildDateItems();
      long read = System.nanoTime();
      this.rebuildView();
      this.scrollToStart();
      if (DEBUG) {
         LogStore.LOGGER.info("[logviewer] day {} loaded: {} entries, {} rows, read {} ms, layout {} ms", index < 0 ? "-" : this.days.get(index).date(),
            this.entries.size(), this.rowView.size(), (read - started) / 1_000_000, (System.nanoTime() - read) / 1_000_000);
      }
   }

   /** Today opens at its newest line, older days at their first. */
   private void scrollToStart() {
      this.scroll = this.isLive() ? Integer.MAX_VALUE : 0;
   }

   private boolean isLive() {
      return this.dayIndex >= 0 && this.days.get(this.dayIndex).live();
   }

   private void pollLive() {
      if (this.liveParser == null || this.liveOffset < 0) {
         return;
      }

      LogStore.LogFile file = this.days.get(this.dayIndex).files().getLast();
      LogStore.Parser parser = this.liveParser;
      LogEntry before = parser.last;
      List<LogEntry> added = new ArrayList<>();
      boolean[] grew = {false};
      long next;
      try {
         next = LogStore.readLive(file.path(), this.liveOffset, line -> {
            LogEntry e = parser.line(line);
            if (e != null) {
               added.add(e);
            } else if (added.isEmpty()) {
               grew[0] = true;
            }
         });
      } catch (IOException e) {
         return;
      }

      if (next < 0) {
         // midnight rolled latest.log into an archive: pick the files up again, staying on the same day
         this.reindex(this.days.get(this.dayIndex).date());
         return;
      }

      this.liveOffset = next;
      if (added.isEmpty() && !grew[0]) {
         return;
      }

      boolean atBottom = this.scroll >= this.maxScroll();
      if (grew[0] && before != null) {
         boolean shown = !this.view.isEmpty() && this.view.getLast() == before;
         if (shown) {
            this.dropLastLayout();
            this.layoutEntry(this.view.size() - 1);
         } else if (this.passes(before)) {
            this.view.add(before);
            this.layoutEntry(this.view.size() - 1);
         }
      }

      this.entries.addAll(added);
      boolean newServer = false;
      for (LogEntry e : added) {
         newServer |= this.countServer(e);
      }

      if (newServer) {
         this.rebuildDateItems();
      }

      for (LogEntry e : added) {
         if (this.passes(e)) {
            this.view.add(e);
            this.layoutEntry(this.view.size() - 1);
         }
      }

      if (atBottom) {
         this.scroll = Integer.MAX_VALUE;
      }
   }

   private boolean passes(LogEntry e) {
      if (this.context) {
         return true;
      }

      return (this.serverFilter == null || this.serverFilter.equals(e.server)) && this.filter.test(e) && e.matches(this.terms);
   }

   /** Some lines are filtered away, so "show this line where it stands" has somewhere to go. */
   private boolean narrowed() {
      return !this.context && (this.terms.length > 0 || this.filter != Filter.ALL || this.serverFilter != null);
   }

   /** From a hit in the filtered list to the same line among all lines of the day. Esc returns to the list. */
   private void showInPlace(LogEntry e) {
      this.contextBack = this.scroll;
      this.context = true;
      this.rebuildView();
      int vi = this.view.indexOf(e);
      for (int r = 0; r < this.rowView.size(); r++) {
         if (this.rowView.getInt(r) == vi) {
            this.scroll = Math.clamp(r - this.visibleRows() / 3, 0, this.maxScroll());
            break;
         }
      }

      this.selected = e;
      this.flash = e;
      this.flashAll = false;
      this.flashUntil = System.currentTimeMillis() + 1200;
   }

   private void leaveContext() {
      this.context = false;
      this.rebuildView();
      this.scroll = Math.clamp(this.contextBack, 0, this.maxScroll());
   }

   private static String[] split(String query) {
      String q = query.trim().toLowerCase(Locale.ROOT);
      return q.isEmpty() ? new String[0] : q.split("\\s+");
   }

   private boolean countsShown() {
      return this.terms.length > 0 || this.filter != Filter.ALL;
   }

   /** Counts matches of every day in the background; the list fills in as days finish. */
   private void startCount() {
      int gen = this.countGen.incrementAndGet();
      if (!this.countsShown()) {
         this.counts = Map.of();
         return;
      }

      Map<LocalDate, Integer> map = new ConcurrentHashMap<>();
      this.counts = map;
      String[] t = this.terms;
      Filter f = this.filter;
      List<LogStore.Day> list = this.days;
      COUNTER.execute(() -> {
         long started = System.nanoTime();
         for (LogStore.Day day : list) {
            if (this.countGen.get() != gen) {
               return;
            }

            int n = LogStore.count(day, e -> f.test(e) && e.matches(t), () -> this.countGen.get() != gen);
            if (n >= 0) {
               map.put(day.date(), n);
            }
         }

         if (DEBUG) {
            LogStore.LOGGER.info("[logviewer] counted {} days for {} {}: {} ms, total {}", list.size(), f, String.join(" ", t),
               (System.nanoTime() - started) / 1_000_000, map.values().stream().mapToInt(Integer::intValue).sum());
         }
      });
   }

   private void applyQuery(String value) {
      if (value.equals(this.query)) {
         return;
      }

      this.query = value;
      savedQuery = value;
      this.terms = split(value);
      this.context = false;
      this.rebuildView();
      this.scrollToStart();
      this.startCount();
   }

   private void setFilter(Filter f) {
      if (f == this.filter) {
         return;
      }

      this.filter = f;
      savedFilter = f;
      this.context = false;
      this.rebuildView();
      this.scrollToStart();
      this.startCount();
   }

   // ---------------------------------------------------------------- layout

   private void rebuildView() {
      this.view.clear();
      for (LogEntry e : this.entries) {
         if (this.passes(e)) {
            this.view.add(e);
         }
      }

      this.relayout();
   }

   private void relayout() {
      this.rowView.clear();
      this.rowLine.clear();
      this.rowFrom.clear();
      this.rowTo.clear();
      this.dividers.clear();
      if (this.logW <= 0) {
         return;
      }

      for (int i = 0; i < this.view.size(); i++) {
         this.layoutEntry(i);
      }
   }

   private void layoutEntry(int vi) {
      LogEntry e = this.view.get(vi);
      if (this.logW <= 0) {
         return;
      }

      if (vi > 0 && this.view.get(vi - 1).file != e.file) {
         this.addRow(-1, 0, 0, 0);
      }

      // a heading where the client arrives on a server (not needed when only one server is shown)
      if (this.serverFilter == null && !e.server.isEmpty() && !e.server.equals(vi > 0 ? this.view.get(vi - 1).server : "")) {
         this.dividers.add(e.server);
         this.addRow(-2, 0, this.dividers.size() - 1, 0);
      }

      int width = this.logW - this.timeW;
      int first = width - (e.extraCount() > 0 ? this.font.width("+0000") + 4 : 0);
      if (e.wrapWidth != first) {
         e.wrapEnds = this.rowEnds(e.text, first);
         e.wrapWidth = first;
      }

      this.addRows(vi, 0, e.text, e.wrapEnds);
      if (this.expanded.contains(e)) {
         for (int k = 1; k <= e.extraCount(); k++) {
            String s = e.display(k);
            this.addRows(vi, k, s, this.rowEnds(s, width));
         }
      }
   }

   private void addRows(int vi, int line, String s, int @Nullable [] ends) {
      if (ends == null) {
         this.addRow(vi, line, 0, s.length());
         return;
      }

      int from = 0;
      for (int end : ends) {
         this.addRow(vi, line, from, end);
         from = end;
      }
   }

   /** Removes the rows of the last shown entry (and the session line in front of it, if any). */
   private void dropLastLayout() {
      int last = this.view.size() - 1;
      int n = this.rowView.size();
      while (n > 0 && this.rowView.getInt(n - 1) == last) {
         n--;
      }

      while (n > 0 && this.rowView.getInt(n - 1) < 0) {
         if (this.rowView.getInt(n - 1) == -2 && !this.dividers.isEmpty()) {
            this.dividers.removeLast();
         }

         n--;
      }

      this.rowView.size(n);
      this.rowLine.size(n);
      this.rowFrom.size(n);
      this.rowTo.size(n);
   }

   /** Where each row of s ends when wrapped to width, or null when it fits on one row. */
   private int @Nullable [] rowEnds(String s, int width) {
      int n = s.length();
      // no default font glyph is wider than MAX_GLYPH (measured in 26.3: Hangul 8, CJK and box drawing 9),
      // so short lines need no measuring
      if (n == 0 || n * MAX_GLYPH <= width && s.indexOf('§') < 0 || n * this.asciiW <= width && ascii(s) || this.font.width(s) <= width) {
         return null;
      }

      IntArrayList ends = new IntArrayList(4);
      int from = 0;
      while (from < n) {
         // a row holds at most a character per pixel: no need to hand over the whole rest of a very long line
         int room = Math.max(width, 8);
         int len = this.font.plainSubstrByWidth(s.substring(from, Math.min(n, from + room + 1)), room).length();
         if (len <= 0) {
            len = Character.charCount(s.codePointAt(from));
         } else if (from + len < n) {
            int space = s.lastIndexOf(' ', from + len - 1);
            if (space > from + len * 2 / 3) {
               len = space + 1 - from;
            }
         }

         from += len;
         ends.add(from);
      }

      return ends.toIntArray();
   }

   /** Plain ASCII only: letters, digits and signs whose width the font was asked for once. */
   private static boolean ascii(String s) {
      for (int i = 0, n = s.length(); i < n; i++) {
         char c = s.charAt(i);
         if (c < ' ' || c > '~') {
            return false;
         }
      }

      return true;
   }

   private void addRow(int vi, int line, int from, int to) {
      this.rowView.add(vi);
      this.rowLine.add(line);
      this.rowFrom.add(from);
      this.rowTo.add(to);
   }

   private int visibleRows() {
      return Math.max(1, (this.logBottom - this.logTop - 4) / ROW_H);
   }

   private int maxScroll() {
      return Math.max(0, this.rowView.size() - this.visibleRows());
   }

   private int visibleDates() {
      return Math.max(1, (this.logBottom - this.logTop - 4) / DATE_H);
   }

   private void clampScroll() {
      this.scroll = Math.clamp(this.scroll, 0, this.maxScroll());
      this.dateScroll = Math.clamp(this.dateScroll, 0, Math.max(0, this.dateItems.size() - this.visibleDates()));
   }

   @Override
   protected void init() {
      // decided before the geometry changes: first open keeps scrollToStart, a resize keeps a bottom-pinned view pinned
      boolean keepBottom = this.rowView.isEmpty() ? this.scroll == Integer.MAX_VALUE : this.scroll >= this.maxScroll();
      this.weekdayLocale = this.minecraft.options.languageCode.startsWith("ko") ? Locale.KOREAN : Locale.ENGLISH;
      this.labelDays();
      this.timeW = this.font.width("00:00:00") + 6;
      int widest = 1;
      for (char c = ' '; c <= '~'; c++) {
         widest = Math.max(widest, this.font.width(String.valueOf(c)));
      }

      this.asciiW = widest;
      int weekday = 0;
      for (DayOfWeek d : DayOfWeek.values()) {
         weekday = Math.max(weekday, this.font.width(d.getDisplayName(TextStyle.SHORT, this.weekdayLocale)));
      }

      this.dateW = 4 + this.font.width("00-00 ") + weekday + 8 + this.font.width("00000") + 4;
      this.logTop = TOP;
      this.logBottom = this.height - PAD;
      this.logX = PAD + this.dateW + 6;
      this.barX = this.width - PAD - 4;
      this.logW = this.barX - 3 - this.logX;
      int chips = -2;
      for (Filter f : Filter.values()) {
         chips += this.font.width(f.label) + 14;
      }

      this.chipsX = this.width - PAD - chips;
      this.search = new EditBox(this.font, PAD, PAD, this.chipsX - 8 - PAD, BAR, this.title);
      this.search.setMaxLength(256);
      this.search.setValue(this.query);
      this.search.setResponder(value -> this.applyAt = System.currentTimeMillis() + 150);
      this.addRenderableWidget(this.search);
      this.setInitialFocus(this.search);
      this.relayout();
      if (keepBottom) {
         this.scroll = Integer.MAX_VALUE;
      }
   }

   @Override
   public void tick() {
      if (this.applyAt >= 0 && System.currentTimeMillis() >= this.applyAt) {
         this.applyAt = -1;
         this.applyQuery(this.search.getValue());
      }

      if (++this.ticks % 10 == 0) {
         this.pollLive();
      }

      // the screen underneath may be what keeps a login going (the connect screen, a tab still logging in): its
      // connection is ticked there, and dropped connections are noticed there
      if (this.parent instanceof ConnectScreen || this.parent != null && this.parent.getClass().getName().equals("mcport.tabs.WaitingScreen")) {
         this.parent.tick();
      }
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   @Override
   public void onClose() {
      this.minecraft.gui.setScreen(this.parent);
   }

   @Override
   public void removed() {
      this.countGen.incrementAndGet();
   }

   // ---------------------------------------------------------------- drawing

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      super.extractRenderState(g, mouseX, mouseY, a);
      this.clampScroll();
      g.fill(PAD, this.logTop, PAD + this.dateW, this.logBottom, 0x90000000);
      g.fill(this.logX - 3, this.logTop, this.width - PAD, this.logBottom, 0x90000000);
      this.drawChips(g, mouseX, mouseY);
      if (this.context) {
         // a hit shown in its place: the filters are set aside until Esc
         g.fill(PAD, PAD + BAR, this.chipsX - 8, PAD + BAR + 2, 0xFFE0A030);
      }
      this.drawDates(g, mouseX, mouseY);
      this.drawLog(g, mouseX, mouseY);
   }

   private void drawChips(GuiGraphicsExtractor g, int mouseX, int mouseY) {
      int x = this.chipsX;
      for (Filter f : Filter.values()) {
         int w = this.font.width(f.label) + 12;
         boolean hover = mouseX >= x && mouseX < x + w && mouseY >= PAD && mouseY < PAD + BAR;
         int bg;
         int fg;
         if (f == this.filter) {
            bg = 0xFFE4E4E4;
            fg = 0xFF1A1A1A;
         } else {
            bg = hover ? 0x70FFFFFF : 0x90000000;
            fg = hover ? 0xFFFFFFFF : 0xFFA0A0A0;
         }

         g.fill(x, PAD, x + w, PAD + BAR, bg);
         g.text(this.font, f.label, x + 6, PAD + (BAR - 8) / 2, fg, false);
         x += w + 2;
      }
   }

   private void drawDates(GuiGraphicsExtractor g, int mouseX, int mouseY) {
      int x0 = PAD;
      int x1 = PAD + this.dateW;
      boolean shown = this.countsShown();
      Map<LocalDate, Integer> map = this.counts;
      g.enableScissor(x0, this.logTop, x1, this.logBottom);
      int end = Math.min(this.dateItems.size(), this.dateScroll + this.visibleDates() + 1);
      for (int i = this.dateScroll; i < end; i++) {
         int y = this.logTop + 3 + (i - this.dateScroll) * DATE_H;
         int item = this.dateItems.getInt(i);
         if (item <= -SERVER_ITEM) {
            int k = -item - SERVER_ITEM;
            String name = this.dayServers.get(k);
            boolean on = name.equals(this.serverFilter);
            boolean over = mouseX >= x0 && mouseX < x1 && mouseY >= y - 2 && mouseY < y - 2 + DATE_H;
            if (on) {
               g.fill(x0 + 6, y - 2, x1, y - 2 + DATE_H, 0x503080FF);
            } else if (over) {
               g.fill(x0 + 6, y - 2, x1, y - 2 + DATE_H, 0x20FFFFFF);
            }

            String label = this.font.plainSubstrByWidth(serverLabel(name), x1 - x0 - 14);
            g.text(this.font, label, x0 + 10, y, on ? 0xFFFFFFFF : C_SERVER, false);
            continue;
         }

         if (item < 0) {
            g.text(this.font, Integer.toString(-item), x0 + 4, y + 1, C_TIME, false);
            continue;
         }

         LogStore.Day day = this.days.get(item);
         Integer n = item == this.shownIndex ? Integer.valueOf(this.view.size()) : map.get(day.date());
         boolean empty = shown && n != null && n == 0;
         boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= y - 2 && mouseY < y - 2 + DATE_H;
         if (item == this.dayIndex) {
            g.fill(x0, y - 2, x1, y - 2 + DATE_H, 0x50FFFFFF);
         } else if (hover) {
            g.fill(x0, y - 2, x1, y - 2 + DATE_H, 0x20FFFFFF);
         }

         LocalDate date = day.date();
         String md = this.dayLabels[item];
         int base = empty ? 0xFF555555 : 0xFFE0E0E0;
         int wd = switch (date.getDayOfWeek()) {
            case SATURDAY -> empty ? 0xFF3F4F70 : 0xFF8FB4FF;
            case SUNDAY -> empty ? 0xFF704040 : 0xFFFF8F8F;
            default -> base;
         };
         g.text(this.font, md, x0 + 4, y, base, false);
         g.text(this.font, this.dayWeekdays[item], x0 + 4 + this.font.width(md), y, wd, false);
         if (shown && n != null && n > 0) {
            String s = n > 99999 ? "99999+" : n.toString();
            g.text(this.font, s, x1 - 4 - this.font.width(s), y, 0xFFC8B060, false);
         }
      }

      g.disableScissor();
   }

   private void drawLog(GuiGraphicsExtractor g, int mouseX, int mouseY) {
      int rows = this.rowView.size();
      int visible = this.visibleRows();
      int hoverRow = this.rowAt(mouseX, mouseY);
      LogEntry hover = hoverRow >= 0 && this.rowView.getInt(hoverRow) >= 0 ? this.view.get(this.rowView.getInt(hoverRow)) : null;
      boolean flashing = System.currentTimeMillis() < this.flashUntil;
      g.enableScissor(this.logX - 3, this.logTop, this.barX - 1, this.logBottom);
      int end = Math.min(rows, this.scroll + visible + 1);
      int tx = this.logX + this.timeW;
      for (int r = this.scroll; r < end; r++) {
         int y = this.logTop + 3 + (r - this.scroll) * ROW_H;
         int vi = this.rowView.getInt(r);
         if (vi == -2) {
            String name = serverLabel(this.dividers.get(this.rowFrom.getInt(r)));
            int nx = this.logX + 8;
            g.fill(this.logX, y + 4, nx - 3, y + 5, 0x60FFFFFF);
            g.text(this.font, name, nx, y, C_SERVER, false);
            g.fill(nx + this.font.width(name) + 3, y + 4, this.logX + this.logW, y + 5, 0x60FFFFFF);
            continue;
         }

         if (vi < 0) {
            g.fill(this.logX, y + 3, this.logX + this.logW, y + 4, 0x30FFFFFF);
            continue;
         }

         LogEntry e = this.view.get(vi);
         int bg = 0;
         if (flashing && (this.flashAll || e == this.flash)) {
            bg = 0x5040C040;
         } else if (e == this.selected) {
            bg = 0x38FFFFFF;
         } else if (e == hover) {
            bg = 0x16FFFFFF;
         }

         if (bg != 0) {
            g.fill(this.logX - 3, y - 1, this.logX + this.logW, y + ROW_H - 1, bg);
         }

         int line = this.rowLine.getInt(r);
         int from = this.rowFrom.getInt(r);
         int to = this.rowTo.getInt(r);
         if (line == 0 && from == 0 && !e.time.isEmpty()) {
            this.highlight(g, e.time, this.logX, y);
            g.text(this.font, e.time, this.logX, y, C_TIME, false);
         }

         String full = e.display(line);
         String seg = full.substring(from, to);
         this.highlight(g, seg, tx, y);
         g.text(this.font, seg, tx, y, color(e, line), false);
         if (line == 0 && to == full.length() && e.extraCount() > 0 && !this.expanded.contains(e)) {
            String mark = "+" + e.extraCount();
            int c = e.matchHidden(this.terms) ? 0xFFFFD040 : C_TIME;
            g.text(this.font, mark, this.logX + this.logW - this.font.width(mark), y, c, false);
         }
      }

      g.disableScissor();
      if (rows > visible) {
         int trackH = this.logBottom - this.logTop;
         int thumbH = Math.max(10, (int)((long)trackH * visible / rows));
         int thumbY = this.logTop + (int)((long)(trackH - thumbH) * this.scroll / Math.max(1, rows - visible));
         g.fill(this.barX, this.logTop, this.barX + 4, this.logBottom, 0x50000000);
         boolean hot = this.dragBar || mouseX >= this.barX - 1 && mouseX < this.barX + 5 && mouseY >= this.logTop && mouseY < this.logBottom;
         g.fill(this.barX, thumbY, this.barX + 4, thumbY + thumbH, hot ? 0xFFE0E0E0 : 0xFF8A8A8A);
      }
   }

   private static int color(LogEntry e, int line) {
      if (line > 0) {
         return e.level >= LogEntry.ERROR ? C_EXTRA_ERROR : C_EXTRA;
      }

      if (e.level >= LogEntry.ERROR) {
         return C_ERROR;
      }

      if (e.level == LogEntry.WARN) {
         return C_WARN;
      }

      if (e.chat) {
         return C_CHAT;
      }

      return e.level == LogEntry.DEBUG ? C_DEBUG : C_INFO;
   }

   private void highlight(GuiGraphicsExtractor g, String seg, int x, int y) {
      if (this.terms.length == 0) {
         return;
      }

      String low = seg.toLowerCase(Locale.ROOT);
      if (low.length() != seg.length()) {
         return;
      }

      for (String term : this.terms) {
         for (int i = low.indexOf(term); i >= 0; i = low.indexOf(term, i + term.length())) {
            int x1 = x + this.font.width(seg.substring(0, i));
            int x2 = x + this.font.width(seg.substring(0, i + term.length()));
            g.fill(x1, y - 1, x2, y + ROW_H - 1, C_MATCH);
         }
      }
   }

   // ---------------------------------------------------------------- input

   private int rowAt(double mouseX, double mouseY) {
      if (mouseX < this.logX - 3 || mouseX >= this.barX - 1 || mouseY < this.logTop + 2 || mouseY >= this.logBottom) {
         return -1;
      }

      // the scroll may still hold the "stick to the bottom" marker until the next frame clamps it
      int r = Math.clamp(this.scroll, 0, this.maxScroll()) + (int)((mouseY - this.logTop - 2) / ROW_H);
      return r < this.rowView.size() ? r : -1;
   }

   private @Nullable LogEntry entryAt(double mouseX, double mouseY) {
      int r = this.rowAt(mouseX, mouseY);
      return r >= 0 && this.rowView.getInt(r) >= 0 ? this.view.get(this.rowView.getInt(r)) : null;
   }

   private boolean overDates(double mouseX, double mouseY) {
      return mouseX >= PAD && mouseX < PAD + this.dateW && mouseY >= this.logTop && mouseY < this.logBottom;
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      double mx = event.x();
      double my = event.y();
      if (my >= PAD && my < PAD + BAR && mx >= this.chipsX) {
         int x = this.chipsX;
         for (Filter f : Filter.values()) {
            int w = this.font.width(f.label) + 12;
            if (mx >= x && mx < x + w) {
               this.setFilter(f);
               return true;
            }

            x += w + 2;
         }

         return true;
      }

      if (this.overDates(mx, my)) {
         int i = this.dateScroll + (int)((my - this.logTop - 1) / DATE_H);
         if (i >= 0 && i < this.dateItems.size()) {
            int item = this.dateItems.getInt(i);
            if (item <= -SERVER_ITEM) {
               this.setServer(this.dayServers.get(-item - SERVER_ITEM));
            } else if (item >= 0 && item != this.dayIndex) {
               this.selectDay(item);
            } else if (item >= 0 && this.serverFilter != null) {
               // the day itself: every server again
               this.setServer(null);
            }
         }

         return true;
      }

      if (mx >= this.barX - 1 && mx < this.barX + 5 && my >= this.logTop && my < this.logBottom) {
         this.dragBar = true;
         this.dragTo(my);
         return true;
      }

      LogEntry e = this.entryAt(mx, my);
      if (e != null) {
         if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            this.copy(e);
         } else if (this.narrowed() && (doubleClick || event.button() == InputConstants.MOUSE_BUTTON_MIDDLE)) {
            this.showInPlace(e);
         } else {
            this.selected = e;
            if (e.extraCount() > 0) {
               if (!this.expanded.remove(e)) {
                  this.expanded.add(e);
               }

               this.relayout();
            }
         }

         return true;
      }

      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
      if (this.dragBar) {
         this.dragTo(event.y());
         return true;
      }

      return super.mouseDragged(event, dx, dy);
   }

   @Override
   public boolean mouseReleased(MouseButtonEvent event) {
      this.dragBar = false;
      return super.mouseReleased(event);
   }

   private void dragTo(double my) {
      int visible = this.visibleRows();
      int rows = this.rowView.size();
      int trackH = this.logBottom - this.logTop;
      int thumbH = Math.max(10, (int)((long)trackH * visible / Math.max(1, rows)));
      double t = (my - this.logTop - thumbH / 2.0) / Math.max(1, trackH - thumbH);
      this.scroll = (int)Math.round(Math.clamp(t, 0.0, 1.0) * this.maxScroll());
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
      if (this.overDates(mouseX, mouseY)) {
         this.dateScroll -= (int)Math.signum(vertical);
         this.clampScroll();
         return true;
      }

      this.scrollRest += vertical * 3;
      int step = (int)this.scrollRest;
      this.scrollRest -= step;
      this.scroll = Math.clamp((long)this.scroll - step, 0, this.maxScroll());
      return true;
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if (LogViewerClient.OPEN.matches(event) && !LogViewerClient.typing(this)) {
         if (LogViewerClient.pressed()) {
            this.onClose();
         }

         return true;
      }

      if (this.context && event.isEscape()) {
         this.leaveContext();
         return true;
      }

      int key = event.input();
      boolean ctrl = event.hasControlDown();
      if (ctrl && event.shortcutKey() == 'c' && (!this.search.isFocused() || this.search.getHighlighted().isEmpty())) {
         if (event.hasShiftDown()) {
            this.copyAll();
         } else if (this.selected != null) {
            this.copy(this.selected);
         }

         return true;
      }

      if (ctrl && event.shortcutKey() == 'f') {
         this.setFocused(this.search);
         this.search.moveCursorToEnd(false);
         this.search.setHighlightPos(0);
         return true;
      }

      if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
         this.applyAt = -1;
         this.applyQuery(this.search.getValue());
         this.jumpToMatchDay(event.hasShiftDown() ? -1 : 1);
         return true;
      }

      int page = this.visibleRows() - 1;
      int move = 0;
      if (key == InputConstants.KEY_PAGEUP) {
         move = -page;
      } else if (key == InputConstants.KEY_PAGEDOWN) {
         move = page;
      } else if (key == InputConstants.KEY_UP) {
         move = -1;
      } else if (key == InputConstants.KEY_DOWN) {
         move = 1;
      } else if (key == InputConstants.KEY_HOME && (ctrl || !this.search.isFocused())) {
         move = Integer.MIN_VALUE;
      } else if (key == InputConstants.KEY_END && (ctrl || !this.search.isFocused())) {
         move = Integer.MAX_VALUE;
      }

      if (move != 0) {
         this.scroll = (int)Math.clamp((long)this.scroll + move, 0, this.maxScroll());
         return true;
      }

      return super.keyPressed(event);
   }

   /** Enter: the next older day that has matches; Shift+Enter: the next newer one. */
   private void jumpToMatchDay(int direction) {
      if (!this.countsShown() || this.days.isEmpty()) {
         return;
      }

      Map<LocalDate, Integer> map = this.counts;
      for (int i = this.dayIndex + direction; i >= 0 && i < this.days.size(); i += direction) {
         Integer n = map.get(this.days.get(i).date());
         if (n != null && n > 0) {
            this.selectDay(i);
            int pos = this.dateItems.indexOf(i);
            if (pos < this.dateScroll || pos >= this.dateScroll + this.visibleDates()) {
               this.dateScroll = pos - this.visibleDates() / 2;
            }

            return;
         }
      }
   }

   private void copy(LogEntry e) {
      this.minecraft.keyboardHandler.setClipboard(e.copyText());
      this.flash = e;
      this.flashAll = false;
      this.flashUntil = System.currentTimeMillis() + 500;
   }

   private void copyAll() {
      StringBuilder sb = new StringBuilder();
      for (LogEntry e : this.view) {
         sb.append(e.copyText()).append('\n');
      }

      this.minecraft.keyboardHandler.setClipboard(sb.toString());
      this.flash = null;
      this.flashAll = true;
      this.flashUntil = System.currentTimeMillis() + 500;
   }
}
