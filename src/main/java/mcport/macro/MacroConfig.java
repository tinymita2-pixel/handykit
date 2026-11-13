package mcport.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Everything the macro screen edits, saved as config/handykit/macro.json; recordings live in recordings.json next to it. */
public final class MacroConfig {
   public static final Logger LOGGER = LoggerFactory.getLogger("handykit");
   public static final int PRESETS = 10;
   private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("handykit");
   private static final Path FILE = DIR.resolve("macro.json");
   private static final Path RECORDINGS = DIR.resolve("recordings.json");
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
   private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

   public enum ClickMode {
      CLICK,
      HOLD,
      COOLDOWN
   }

   /** What the server resource packs of several tabs do with the one set of loaded resources. */
   public enum TabPacks {
      /** The packs of every open tab stay loaded: changing tabs loads nothing. */
      TOGETHER,
      /**
       * Like TOGETHER, but a pack of another tab that changes something the tab on screen would see (a vanilla
       * texture, a shader, a line of text) is taken out while that tab is on screen: every tab looks as it would
       * alone, and a change of tab reloads only when it has to.
       */
      EXACT,
      /** Only the packs of the tab on screen: every tab looks exactly as it would alone, at the price of a reload on changing tabs. */
      OWN
   }

   public enum TabSound {
      /** Only the tab on screen. */
      CURRENT,
      /** Every tab at full volume. */
      ALL,
      /** Every tab, the ones behind the screen quieter. */
      QUIET
   }

   /** Servers (with the account each is joined with) that are opened together. */
   public static final class TabSet {
      public String name = "";
      /** Open when the game has started. */
      public boolean autoOpen;
      public List<TabEntry> tabs = new ArrayList<>();
   }

   public static final class TabEntry {
      public String name = "";
      public String address = "";
      /** Name of the account, or null for the one the game was started with. */
      public String account;
      public boolean microsoft;
   }

   public enum Repeat {
      ONCE,
      COUNT,
      LOOP
   }

   public static final class Clicker {
      public ClickMode mode = ClickMode.CLICK;
      public double cps;

      Clicker() {
         this(10);
      }

      Clicker(double cps) {
         this.cps = cps;
      }
   }

   public static final class Rule {
      public boolean on = true;
      public String keyword = "";
      public boolean exact;
      public String reply = "";
      /** The keyword as it is compared (one case, single spaces): worked out again only when the keyword changed. */
      private transient String normOf;
      private transient String norm = "";

      String normKeyword() {
         String k = this.keyword;
         if (k != this.normOf) {
            this.norm = k == null ? "" : ChatMacros.norm(k);
            this.normOf = k;
         }

         return this.norm;
      }
   }

   /** One player to tune out; each part can be left on (for example hidden but still readable in chat). */
   public static final class Ignored {
      public String name = "";
      public boolean chat = true;
      public boolean render = true;
      public boolean sound = true;
   }

   public Clicker left = new Clicker(10);
   public Clicker right = new Clicker(5);
   public List<String> presets = new ArrayList<>();
   public boolean replyOn = true;
   public double replyDelay = 0.5;
   public double replyCooldown = 3.0;
   public List<Rule> rules = new ArrayList<>();
   public boolean recordLook = true;
   /** The recording the play key starts. Recordings are one list for every server, so is this choice: read it from base(). */
   public int selected;
   /** Send chat without a signature, so it cannot be used as evidence in a chat report. */
   public boolean unsignedChat = true;
   // ---- the settings from here to tabSets are the same for every server: read them from base()
   /** Which server tabs are heard. */
   public TabSound tabSound = TabSound.CURRENT;
   /** How loud tabs behind the screen are with TabSound.QUIET, 0..1. */
   public float tabQuietVolume = 0.3F;
   /** Always OWN since 2026-11-11: every tab has its own packs (the other two are no longer offered). */
   public TabPacks tabPacks = TabPacks.OWN;
   /** Chat lines under a small view (the arrows in its header change it; a tab opened later starts with the last choice). */
   public int tabWatchLines = 1;
   /**
    * Where the player put the small view of a tab, by server address (and account): its right edge from the window's
    * right edge, its top from the window's top. A tab that is not in here stacks in the corner.
    */
   public java.util.Map<String, int[]> tabWatchPlaces = new java.util.HashMap<>();
   /** How wide the player pulled the small view of a tab (GUI pixels), by the same key; not in here: the usual width. */
   public java.util.Map<String, Integer> tabWatchSizes = new java.util.HashMap<>();
   /** The memo pad: shown or not, what is written on it, where it is (right edge from the window's right, lower edge from its bottom) and how large. */
   public boolean memoOn;
   public String memoText = "";
   public int[] memoPlace = {4, 28};
   public int[] memoSize = {160, 100};
   /** View distance asked from the server of a tab behind the screen, in chunks; 0 leaves it alone. */
   public int tabBackgroundView = 4;
   /** A tab behind the screen calls for attention when someone whispers, names me, or says one of the words. */
   public boolean tabAlertWhisper = true;
   public boolean tabAlertMention = true;
   public String tabAlertWords = "";
   public boolean tabAlertSound = true;
   /** A tab that lost its server joins again by itself. */
   public boolean tabReconnect;
   public int tabReconnectTries = 5;
   public int tabReconnectDelay = 5;
   /** What to send after joining, by server address: lines separated by ";" ("/..." is a command, anything else chat). */
   public java.util.Map<String, String> joinCommands = new java.util.HashMap<>();
   public List<TabSet> tabSets = new ArrayList<>();
   /** Hide every other player, whoever they are. */
   public boolean hideAllPlayers;
   public boolean muteAllPlayers;
   public List<Ignored> ignored = new ArrayList<>();
   /** The list of the old Ignoring mod (config/ignoring.json) was taken over once. */
   public boolean ignoringImported;
   public transient List<Recording> recordings = new ArrayList<>();
   /** Bumped whenever the ignore list changes, so lookups can be cached. */
   public transient int ignoreVersion;
   /** The ignore list by name, whatever the case. Kept with its own config: the shared settings and each profile have a list of their own. */
   private transient java.util.Map<String, Ignored> ignoreIndex;
   private transient int ignoreIndexVersion = -1;
   private transient boolean ignoreAnySound;

   public java.util.Map<String, Ignored> ignoreIndex() {
      if (this.ignoreIndex == null || this.ignoreIndexVersion != this.ignoreVersion) {
         // names differ only by case on the same server; compare without allocating per lookup
         java.util.Map<String, Ignored> map = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
         boolean sound = false;
         for (Ignored i : this.ignored) {
            String key = i.name.trim();
            if (!key.isEmpty()) {
               map.putIfAbsent(key, i);
               sound |= i.sound;
            }
         }

         this.ignoreIndex = map;
         this.ignoreAnySound = sound;
         this.ignoreIndexVersion = this.ignoreVersion;
      }

      return this.ignoreIndex;
   }

   /** Someone on the list has their sound turned off. */
   public boolean ignoreAnySound() {
      this.ignoreIndex();
      return this.ignoreAnySound;
   }

   private static MacroConfig instance;
   /** Not saved: the server address this copy is the profile of; null for the shared settings. */
   public transient String profile;
   /** address -> its profile; a null value means "looked, there is none". */
   private static final java.util.Map<String, MacroConfig> PROFILES = new java.util.concurrent.ConcurrentHashMap<>();
   private static final MacroConfig NO_PROFILE = new MacroConfig();

   /** The last answer of get(): it is asked many times a frame (once per player drawn) for the same server. */
   private record Last(String address, MacroConfig config) {
   }

   private static volatile Last last;
   /** Recordings are written off the client thread (a long one is megabytes of numbers); one writer, so saves land in order. */
   private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "HandyKit recordings");
      thread.setDaemon(true);
      return thread;
   });

   static {
      // the game ends with System.exit: a save still under way gets the time to finish
      try {
         Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            WRITER.shutdown();
            try {
               WRITER.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
            }
         }, "HandyKit recordings flush"));
      } catch (IllegalStateException e) {
         // the game is closing already
      }
   }

   /** The settings shared by every server (and everything that is not per server). */
   public static MacroConfig base() {
      if (instance == null) {
         instance = load();
      }

      return instance;
   }

   /** The settings for the server things are being done for: its own profile when it has one. */
   public static MacroConfig get() {
      MacroConfig base = base();
      String address = mcport.tabs.Tabs.contextAddress();
      if (address == null) {
         return base;
      }

      Last kept = last;
      if (kept != null && kept.address().equals(address)) {
         return kept.config();
      }

      MacroConfig profile = PROFILES.computeIfAbsent(address, MacroConfig::loadProfile);
      MacroConfig config = profile == NO_PROFILE ? base : profile;
      last = new Last(address, config);
      return config;
   }

   private static Path profileFile(String address) {
      return DIR.resolve("servers").resolve(address.replaceAll("[^a-z0-9._-]", "_") + ".json");
   }

   /** Where a profile waits while it is turned off. */
   private static Path profileOff(String address) {
      Path file = profileFile(address);
      return file.resolveSibling(file.getFileName() + ".off");
   }

   private static MacroConfig loadProfile(String address) {
      Path file = profileFile(address);
      if (!Files.isRegularFile(file)) {
         return NO_PROFILE;
      }

      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
         MacroConfig config = GSON.fromJson(reader, MacroConfig.class);
         if (config != null) {
            config.adopt(address);
            config.fix();
            return config;
         }
      } catch (IOException | RuntimeException e) {
         LOGGER.warn("Could not read {}", file, e);
      }

      return NO_PROFILE;
   }

   /** A profile shares the recordings with the shared settings; only what is set up per server is its own. */
   private void adopt(String address) {
      this.profile = address;
      this.recordings = base().recordings;
   }

   public static boolean hasProfile(String address) {
      return address != null && PROFILES.computeIfAbsent(address, MacroConfig::loadProfile) != NO_PROFILE;
   }

   /** Gives the server its own settings: the ones it had before they were turned off, else a copy of the shared ones. */
   public static MacroConfig createProfile(String address) {
      last = null;
      Path off = profileOff(address);
      if (Files.isRegularFile(off)) {
         try {
            Files.move(off, profileFile(address), StandardCopyOption.REPLACE_EXISTING);
            MacroConfig kept = loadProfile(address);
            if (kept != NO_PROFILE) {
               PROFILES.put(address, kept);
               return kept;
            }
         } catch (IOException e) {
            LOGGER.warn("Could not take back the profile of {}", address, e);
         }
      }

      MacroConfig copy = GSON.fromJson(GSON.toJson(base()), MacroConfig.class);
      copy.adopt(address);
      PROFILES.put(address, copy);
      copy.save();
      return copy;
   }

   /** Back to the shared settings. The server's own are set aside, not deleted: turning the profile on again brings them back. */
   public static void removeProfile(String address) {
      last = null;
      PROFILES.put(address, NO_PROFILE);
      Path file = profileFile(address);
      try {
         if (Files.isRegularFile(file)) {
            Files.move(file, profileOff(address), StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException e) {
         LOGGER.warn("Could not set aside the profile of {}", address, e);
      }
   }

   private static MacroConfig load() {
      MacroConfig config = null;
      if (Files.isRegularFile(FILE)) {
         try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            config = GSON.fromJson(reader, MacroConfig.class);
         } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}, starting from defaults", FILE, e);
         }
      }

      if (config == null) {
         config = new MacroConfig();
      }

      config.recordings = new ArrayList<>();
      if (Files.isRegularFile(RECORDINGS)) {
         try (Reader reader = Files.newBufferedReader(RECORDINGS, StandardCharsets.UTF_8)) {
            List<Recording> list = GSON.fromJson(reader, new TypeToken<List<Recording>>() {}.getType());
            if (list != null) {
               for (Recording r : list) {
                  if (r != null && r.valid()) {
                     config.recordings.add(r);
                  }
               }
            }
         } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}", RECORDINGS, e);
         }
      }

      config.fix();
      return config;
   }

   /** Fills what an older or hand-edited file left out. */
   private void fix() {
      if (this.tabWatchPlaces == null) {
         this.tabWatchPlaces = new java.util.HashMap<>();
      }

      this.tabWatchPlaces.values().removeIf(place -> place == null || place.length != 2);
      if (this.tabWatchSizes == null) {
         this.tabWatchSizes = new java.util.HashMap<>();
      }

      this.tabWatchSizes.values().removeIf(width -> width == null || width < 16);
      if (this.memoText == null) {
         this.memoText = "";
      }

      if (this.memoPlace == null || this.memoPlace.length != 2) {
         this.memoPlace = new int[]{4, 28};
      }

      if (this.memoSize == null || this.memoSize.length != 2) {
         this.memoSize = new int[]{160, 100};
      }

      if (this.left == null) {
         this.left = new Clicker(10);
      }

      if (this.right == null) {
         this.right = new Clicker(5);
      }

      for (Clicker c : new Clicker[]{this.left, this.right}) {
         if (c.mode == null) {
            c.mode = ClickMode.CLICK;
         }

         c.cps = Math.clamp(c.cps, 0.1, 20.0);
      }

      if (this.right.mode == ClickMode.COOLDOWN) {
         this.right.mode = ClickMode.CLICK;
      }

      if (this.presets == null) {
         this.presets = new ArrayList<>();
      }

      this.presets.replaceAll(s -> s == null ? "" : s);
      while (this.presets.size() < PRESETS) {
         this.presets.add("");
      }

      while (this.presets.size() > PRESETS) {
         this.presets.removeLast();
      }

      if (this.rules == null) {
         this.rules = new ArrayList<>();
      }

      this.rules.removeIf(r -> r == null);
      for (Rule r : this.rules) {
         r.keyword = r.keyword == null ? "" : r.keyword;
         r.reply = r.reply == null ? "" : r.reply;
      }

      this.replyDelay = Math.clamp(this.replyDelay, 0.0, 60.0);
      this.replyCooldown = Math.clamp(this.replyCooldown, 0.0, 3600.0);
      this.selected = Math.clamp(this.selected, 0, Math.max(0, this.recordings.size() - 1));
      if (this.ignored == null) {
         this.ignored = new ArrayList<>();
      }

      this.ignored.removeIf(i -> i == null);
      for (Ignored i : this.ignored) {
         i.name = i.name == null ? "" : i.name;
      }

      if (this.tabSound == null) {
         this.tabSound = TabSound.CURRENT;
      }

      this.tabPacks = TabPacks.OWN;

      this.tabQuietVolume = Math.clamp(this.tabQuietVolume, 0.0F, 1.0F);
      this.tabBackgroundView = Math.max(0, this.tabBackgroundView);
      this.tabReconnectTries = Math.max(1, this.tabReconnectTries);
      this.tabReconnectDelay = Math.max(1, this.tabReconnectDelay);
      if (this.tabAlertWords == null) {
         this.tabAlertWords = "";
      }

      if (this.joinCommands == null) {
         this.joinCommands = new java.util.HashMap<>();
      }

      this.joinCommands.values().removeIf(v -> v == null);
      if (this.tabSets == null) {
         this.tabSets = new ArrayList<>();
      }

      this.tabSets.removeIf(s -> s == null);
      for (TabSet set : this.tabSets) {
         set.name = set.name == null ? "" : set.name;
         if (set.tabs == null) {
            set.tabs = new ArrayList<>();
         }

         set.tabs.removeIf(t -> t == null || t.address == null || t.address.isBlank());
         for (TabEntry entry : set.tabs) {
            entry.name = entry.name == null ? "" : entry.name;
         }
      }

      // the list of the old Ignoring mod goes to the shared settings, once; a server's profile starts from those
      if (!this.ignoringImported && this.profile == null && Features.IGNORE) {
         this.ignoringImported = true;
         this.importIgnoring();
      }
   }

   /** config/ignoring.json of the Ignoring mod: two switches for the whole list, and the names. */
   private void importIgnoring() {
      Path old = FabricLoader.getInstance().getConfigDir().resolve("ignoring.json");
      if (!Files.isRegularFile(old)) {
         return;
      }

      try (Reader reader = Files.newBufferedReader(old, StandardCharsets.UTF_8)) {
         com.google.gson.JsonObject json = GSON.fromJson(reader, com.google.gson.JsonObject.class);
         if (json == null || !json.has("ignoredPlayerList")) {
            return;
         }

         boolean chat = json.has("ignoreChat") && json.get("ignoreChat").getAsBoolean();
         boolean render = json.has("ignoreRender") && json.get("ignoreRender").getAsBoolean();
         for (com.google.gson.JsonElement element : json.getAsJsonArray("ignoredPlayerList")) {
            String name = element.getAsString().trim();
            if (name.isEmpty() || name.equals("Insert name") || this.ignored.stream().anyMatch(i -> i.name.equalsIgnoreCase(name))) {
               continue;
            }

            Ignored entry = new Ignored();
            entry.name = name;
            entry.chat = chat;
            entry.render = render;
            entry.sound = false;
            this.ignored.add(entry);
         }

         LOGGER.info("Took over {} names from the Ignoring mod", this.ignored.size());
         this.save();
      } catch (IOException | RuntimeException e) {
         LOGGER.warn("Could not read {}", old, e);
      }
   }

   public void save() {
      if (this.profile != null) {
         write(profileFile(this.profile), GSON.toJson(this));
      } else {
         write(FILE, GSON.toJson(this));
      }
   }

   public void saveRecordings() {
      // a copy of each, taken now: the list and the arrays go on changing while the writer works
      List<Recording> copies = new ArrayList<>(this.recordings.size());
      for (Recording r : this.recordings) {
         copies.add(r.copy());
      }

      try {
         WRITER.execute(() -> writeRecordings(copies));
      } catch (java.util.concurrent.RejectedExecutionException e) {
         // the game is closing and the writer has stopped taking work
         writeRecordings(copies);
      }
   }

   private static void writeRecordings(List<Recording> recordings) {
      // one recording per line: readable list, arrays kept on one line
      StringBuilder sb = new StringBuilder("[\n");
      for (int i = 0; i < recordings.size(); i++) {
         sb.append("  ").append(COMPACT.toJson(recordings.get(i))).append(i + 1 < recordings.size() ? ",\n" : "\n");
      }

      write(RECORDINGS, sb.append("]\n").toString());
   }

   private static void write(Path file, String text) {
      try {
         Files.createDirectories(file.getParent());
         Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
         try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            writer.write(text);
         }

         Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException e) {
         LOGGER.warn("Could not save {}", file, e);
      }
   }

   /** The recording the play key starts: one choice for every server, like the list itself. */
   public Recording selectedRecording() {
      MacroConfig shared = base();
      return shared.recordings.isEmpty() ? null : shared.recordings.get(Math.clamp(shared.selected, 0, shared.recordings.size() - 1));
   }
}
