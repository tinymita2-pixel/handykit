package mcport.macro;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Records the movement, jump, sneak, sprint, attack, use, pick, drop and swap keys, the look direction and the
 * hotbar slot once per tick, and plays them back through the same key mappings. Things that do not go through
 * those keys are recorded as events tied to a tick: chat and commands the player sent, opening the inventory,
 * every click inside a chest or other container, and closing it.
 *
 * Timing inside one client tick: at its start (before vanilla handles keys and moves the player) the recorder reads
 * held keys, pending presses and the look, and playback writes them; at its end the recorder reads the hotbar slot,
 * which by then includes the number keys handled in that tick, and playback runs the events of that tick. Ticks
 * with a menu open are skipped both ways, except containers: time keeps running while a chest is open.
 */
final class Recorder {
   /** The longest recording: an hour. */
   static final int MAX_TICKS = 20 * 60 * 60;
   /** Commands that carry a password. Recordings are saved as plain text and can be played on any server. */
   private static final java.util.Set<String> SECRET = java.util.Set.of(
      "login", "l", "log", "register", "reg", "changepassword", "changepw", "changepass", "unregister", "unreg"
   );
   private static final boolean DEBUG = Boolean.getBoolean("handykit.debug");
   /** From this index on, KEYS are pressed (vanilla consumes their clicks); before it they are only held. */
   private static final int FIRST_CLICK_KEY = 7;
   /** How long playback waits for the container a recorded click belongs to (the server opens it, with some delay). */
   private static final int WAIT_TICKS = 60;

   static KeyMapping[] keys(Options o) {
      return new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint, o.keyAttack, o.keyUse,
         o.keyPickItem, o.keyDrop, o.keySwapOffhand};
   }

   // recording
   private boolean recording;
   private boolean look;
   private int startSlot;
   private float lastYaw;
   private float lastPitch;
   private double offYaw;
   private double offPitch;
   private boolean capturedThisTick;
   private boolean wasContainer;
   private final IntArrayList recKeys = new IntArrayList();
   private final IntArrayList recClicks = new IntArrayList();
   private final IntArrayList recSlot = new IntArrayList();
   private final FloatArrayList recYaw = new FloatArrayList();
   private final FloatArrayList recPitch = new FloatArrayList();
   private final List<Recording.Event> recEvents = new ArrayList<>();
   /** Pending press counts left after the previous tick, so only new presses are recorded. */
   private final int[] seenClicks = new int[12];

   // playback
   private Recording playing;
   private int frame;
   private int clickAt;
   private int eventAt;
   private int waited;
   private int played;
   private boolean paused;
   private boolean appliedThisTick;
   /** Playback is sending chat or clicking a slot itself; the recording hooks must not pick that up. */
   private boolean replaying;
   private double appliedYaw;
   private double appliedPitch;
   /** This recorder holds the real key mappings down right now (its tab is on screen and playing). */
   private boolean drivingKeys;
   /** How long the event that holds the playback may take (a container to open, a chat line to arrive). */
   private int waitLimit = WAIT_TICKS;
   /** Waiting for a chat line: the player stands still meanwhile. */
   private boolean waitingForChat;
   /** Chat lines this tab received, newest last, numbered; a wait takes lines after the number it started at. */
   private final java.util.ArrayDeque<String> heard = new java.util.ArrayDeque<>();
   private long heardCount;
   private long waitFrom = -1;
   /** The line number when the playback last sent chat or a command (-1: nothing yet this round). */
   private long sentAt = -1;

   /** A chat line arrived in this recorder's tab. */
   void onChatSeen(String line) {
      if (this.playing != null) {
         this.heard.addLast(line.toLowerCase(java.util.Locale.ROOT));
         this.heardCount++;
         while (this.heard.size() > 100) {
            this.heard.removeFirst();
         }
      }
   }

   /** Playback is sending chat or clicking a slot right now. */
   boolean replaying() {
      return this.replaying;
   }

   /** "login secret", "authme:register a a": a command with a password behind it. "l" alone (the lobby, on many servers) is not. */
   static boolean secret(String command) {
      String c = command.trim();
      int space = c.indexOf(' ');
      if (space <= 0) {
         return false;
      }

      String word = c.substring(0, space).toLowerCase(java.util.Locale.ROOT);
      return SECRET.contains(word.substring(word.lastIndexOf(':') + 1));
   }

   private boolean heardSince(long from, String text) {
      long number = this.heardCount - this.heard.size();
      for (String line : this.heard) {
         if (number++ >= from && line.contains(text)) {
            return true;
         }
      }

      return false;
   }

   /** How many of an item the player carries. */
   private static int carried(LocalPlayer player, String item) {
      int total = 0;
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
         if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) {
            total += stack.getCount();
         }
      }

      return total;
   }

   boolean recording() {
      return this.recording;
   }

   int recordedTicks() {
      return this.recKeys.size();
   }

   Recording playing() {
      return this.playing;
   }

   /** Which play this is, from 1. */
   int playNumber() {
      return this.played + 1;
   }

   boolean paused() {
      return this.paused;
   }

   /** Chests and the like keep the game running; other menus (chat, pause, settings) stop the clock. */
   private static boolean isContainer(Screen screen) {
      return screen instanceof AbstractContainerScreen<?> && !(screen instanceof CreativeModeInventoryScreen);
   }

   // ---------------------------------------------------------------- recording

   void startRecording(Minecraft mc) {
      if (mc.player == null) {
         return;
      }

      this.stopPlayback(mc);
      this.recKeys.clear();
      this.recClicks.clear();
      this.recSlot.clear();
      this.recYaw.clear();
      this.recPitch.clear();
      this.recEvents.clear();
      this.look = MacroConfig.get().recordLook;
      this.startSlot = mc.player.getInventory().getSelectedSlot();
      this.lastYaw = mc.player.getYRot();
      this.lastPitch = mc.player.getXRot();
      this.offYaw = 0;
      this.offPitch = 0;
      this.wasContainer = isContainer(mc.gui.screen());
      KeyMapping[] keys = keys(mc.options);
      for (int i = 0; i < keys.length; i++) {
         this.seenClicks[i] = Keys.clicks(keys[i]);
      }

      this.recording = true;
   }

   void cancelRecording() {
      this.recording = false;
      this.capturedThisTick = false;
   }

   /** The tick an event that happens right now belongs to: the last one captured. */
   private int now() {
      return Math.max(0, this.recKeys.size() - 1);
   }

   private Recording.Event event(String kind) {
      Recording.Event e = new Recording.Event();
      e.tick = this.now();
      e.kind = kind;
      this.recEvents.add(e);
      return e;
   }

   /** The player sent chat or a command (typed, or a preset key). */
   void onChat(Minecraft mc, ClientPacketListener listener, String text, boolean command) {
      if (this.recording && !this.replaying && !ChatMacros.automatic && listener == mc.getConnection()) {
         if (command && secret(text)) {
            MacroClient.notice(net.minecraft.network.chat.Component.translatable("handykit.notice.secret"));
            return;
         }

         this.event(command ? "command" : "chat").text = text;
      }
   }

   /** The player clicked a slot (any kind: pick up, shift click, number key, drop, drag). */
   void onContainerInput(Minecraft mc, int containerId, int slot, int button, ContainerInput input) {
      LocalPlayer player = mc.player;
      if (!this.recording || this.replaying || player == null || mc.gui.screen() instanceof CreativeModeInventoryScreen) {
         return;
      }

      if (player.containerMenu.containerId == containerId) {
         Recording.Event e = this.event("click");
         e.slot = slot;
         e.button = button;
         e.input = input.name();
         e.menu = menuKey(player);
      }
   }

   private static String menuKey(LocalPlayer player) {
      AbstractContainerMenu menu = player.containerMenu;
      if (menu == player.inventoryMenu) {
         return "inventory";
      }

      try {
         return BuiltInRegistries.MENU.getKey(menu.getType()) + "/" + menu.slots.size();
      } catch (UnsupportedOperationException e) {
         return "?/" + menu.slots.size();
      }
   }

   /** Ends the recording; returns it, or null when nothing happened in it. */
   Recording finishRecording() {
      this.recording = false;
      this.capturedThisTick = false;
      int n = this.recKeys.size();
      // drop the idle stretch before the first input, keeping one quiet tick as the starting point
      int first = -1;
      for (int t = 0; t < n && first < 0; t++) {
         int prevSlot = t == 0 ? this.startSlot : this.recSlot.getInt(t - 1);
         boolean turned = this.look && t > 0 && (this.recYaw.getFloat(t) != this.recYaw.getFloat(t - 1) || this.recPitch.getFloat(t) != this.recPitch.getFloat(t - 1));
         if (this.recKeys.getInt(t) != 0 || turned || this.recSlot.getInt(t) != prevSlot) {
            first = t;
         }
      }

      for (int i = 0; i < this.recClicks.size(); i += 3) {
         int t = this.recClicks.getInt(i);
         first = first < 0 ? t : Math.min(first, t);
      }

      for (Recording.Event e : this.recEvents) {
         first = first < 0 ? e.tick : Math.min(first, e.tick);
      }

      if (first < 0 || n == 0) {
         return null;
      }

      int base = Math.max(0, first - 1);
      Recording r = new Recording();
      r.startSlot = base == 0 ? this.startSlot : this.recSlot.getInt(base - 1);
      r.keys = this.recKeys.subList(base, n).toIntArray();
      r.slot = this.recSlot.subList(base, Math.min(n, this.recSlot.size())).toIntArray();
      if (r.slot.length != r.keys.length) {
         r.slot = null;
      }

      IntArrayList clicks = new IntArrayList();
      for (int i = 0; i < this.recClicks.size(); i += 3) {
         int t = this.recClicks.getInt(i);
         if (t >= base) {
            clicks.add(t - base);
            clicks.add(this.recClicks.getInt(i + 1));
            clicks.add(this.recClicks.getInt(i + 2));
         }
      }

      r.clicks = clicks.toIntArray();
      if (!this.recEvents.isEmpty()) {
         r.events = new ArrayList<>(this.recEvents.size());
         for (Recording.Event e : this.recEvents) {
            e.tick = Math.clamp(e.tick - base, 0, r.keys.length - 1);
            r.events.add(e);
         }
      }

      if (this.look) {
         float y0 = this.recYaw.getFloat(base);
         float p0 = this.recPitch.getFloat(base);
         r.yaw = new float[n - base];
         r.pitch = new float[n - base];
         for (int t = base; t < n; t++) {
            r.yaw[t - base] = this.recYaw.getFloat(t) - y0;
            r.pitch[t - base] = this.recPitch.getFloat(t) - p0;
         }
      }

      this.recKeys.clear();
      this.recClicks.clear();
      this.recSlot.clear();
      this.recYaw.clear();
      this.recPitch.clear();
      this.recEvents.clear();
      return r;
   }

   // ---------------------------------------------------------------- playback

   void startPlayback(Minecraft mc, Recording r) {
      if (mc.player == null || r == null || r.length() == 0) {
         return;
      }

      if (this.recording) {
         this.cancelRecording();
      }

      this.stopPlayback(mc);
      this.playing = r;
      this.played = 0;
      this.paused = false;
      this.rewind(mc);
   }

   private void rewind(Minecraft mc) {
      this.frame = 0;
      this.clickAt = 0;
      this.eventAt = 0;
      this.waited = 0;
      this.waitLimit = WAIT_TICKS;
      this.waitingForChat = false;
      this.waitFrom = -1;
      this.sentAt = -1;
      this.appliedYaw = 0;
      this.appliedPitch = 0;
      this.appliedThisTick = false;
      if (this.playing.startSlot >= 0 && this.playing.startSlot < 9 && mc.player != null) {
         mc.player.getInventory().setSelectedSlot(this.playing.startSlot);
      }
   }

   void stopPlayback(Minecraft mc) {
      if (this.playing == null) {
         return;
      }

      this.playing = null;
      this.appliedThisTick = false;
      this.releaseKeys(mc);
      this.idleBehind(mc);
   }

   /**
    * Behind the screen no key mapping is let go when a playback ends: the player's input goes back to idle, and
    * what the recording was holding (use, attack) is released, or a bow stays drawn and a block half broken.
    */
   private void idleBehind(Minecraft mc) {
      if (mc.player != null && mc.player.input instanceof PlaybackInput) {
         mc.player.input = new net.minecraft.client.player.ClientInput();
         if (mcport.tabs.Tabs.inBackground()) {
            mcport.tabs.Tabs.backgroundKeys(mc, false, false, 0, 0, 0, 0, 0);
         }
      }
   }

   /** Gives the keyboard back; the playback itself stays where it is (its tab goes behind the screen). */
   void releaseKeys(Minecraft mc) {
      if (this.drivingKeys) {
         this.drivingKeys = false;
         for (KeyMapping key : keys(mc.options)) {
            Keys.restore(key);
         }
      }
   }

   /** The tab is gone: nothing left to play for. */
   void drop(Minecraft mc) {
      this.recording = false;
      this.capturedThisTick = false;
      this.playing = null;
      this.appliedThisTick = false;
      this.releaseKeys(mc);
   }

   // ---------------------------------------------------------------- behind the screen

   /**
    * Start of a tick of this recorder's tab while it is not on screen (the tab's world is in Minecraft's fields).
    * No key mapping is touched: movement goes into the player's input, the action keys straight to what they do.
    */
   void backgroundStartTick(Minecraft mc) {
      if (this.playing == null) {
         // stopped from the screen side (the recording was edited or deleted) while this tab was behind it
         this.idleBehind(mc);
         return;
      }

      LocalPlayer player = mc.player;
      if (player == null) {
         this.playing = null;
         return;
      }

      Screen screen = mcport.tabs.Tabs.contextScreen();
      if (screen != null && !isContainer(screen)) {
         // a death screen or a dialog waits in this tab: stand still until it is answered
         this.paused = true;
         if (player.input instanceof PlaybackInput input) {
            input.mask = 0;
         }

         return;
      }

      this.paused = false;
      Recording r = this.playing;
      int t = this.frame;
      int mask = this.waitingForChat ? 0 : r.keys[t];
      PlaybackInput input;
      if (player.input instanceof PlaybackInput existing) {
         input = existing;
      } else {
         input = new PlaybackInput();
         mcport.tabs.Tabs.carryInput(player.input, input);
         player.input = input;
      }

      input.mask = mask;
      int[] presses = new int[12];
      while (this.clickAt + 2 < r.clicks.length && r.clicks[this.clickAt] <= t) {
         if (r.clicks[this.clickAt] == t) {
            int i = r.clicks[this.clickAt + 1];
            if (i >= 0 && i < presses.length) {
               presses[i] += r.clicks[this.clickAt + 2];
            }
         }

         this.clickAt += 3;
      }

      if (r.slot != null) {
         int prev = t == 0 ? r.startSlot : r.slot[t - 1];
         int s = r.slot[t];
         if (s != prev && s >= 0 && s < 9) {
            player.getInventory().setSelectedSlot(s);
         }
      }

      if (r.hasLook()) {
         this.lookAt(player, r.yaw[t], r.pitch[t]);
      }

      mcport.tabs.Tabs.backgroundKeys(mc, (mask & 1 << 7) != 0, (mask & 1 << 8) != 0, presses[7], presses[8], presses[9], presses[10], presses[11]);
      this.appliedThisTick = true;
   }

   void backgroundEndTick(Minecraft mc) {
      if (this.playing != null && this.appliedThisTick) {
         this.advance(mc);
      }
   }

   // ---------------------------------------------------------------- ticks

   void startTick(Minecraft mc) {
      LocalPlayer player = mc.player;
      Screen screen = mc.gui.screen();
      boolean container = isContainer(screen);
      boolean menu = screen != null && !container || mc.gui.overlay() != null;
      if (this.recording) {
         if (player == null) {
            // the world went away under the recording: what there is of it is kept
            MacroClient.finishRecording(mc);
         } else {
            if (container != this.wasContainer) {
               if (!container) {
                  this.event("close");
               } else if (player.containerMenu == player.inventoryMenu) {
                  // a chest opens because of a recorded right click; the player's own inventory needs its own event
                  this.event("inventory");
               }

               this.wasContainer = container;
            }

            if (!menu) {
               this.capture(mc, player);
            } else {
               // keep the look baseline current so turning inside a menu (or a teleport) is not recorded as a jump
               this.lastYaw = player.getYRot();
               this.lastPitch = player.getXRot();
            }
         }
      }

      if (this.playing != null) {
         if (player == null) {
            this.stopPlayback(mc);
         } else if (menu) {
            if (!this.paused) {
               this.paused = true;
               for (KeyMapping key : keys(mc.options)) {
                  Keys.setDown(key, false);
                  Keys.setClicks(key, 0);
               }
            }
         } else {
            this.paused = false;
            this.apply(mc, player);
         }
      }
   }

   void endTick(Minecraft mc) {
      if (this.recording) {
         KeyMapping[] keys = keys(mc.options);
         for (int i = 0; i < keys.length; i++) {
            this.seenClicks[i] = Keys.clicks(keys[i]);
         }
      }

      if (this.capturedThisTick) {
         this.capturedThisTick = false;
         this.recSlot.add(mc.player != null ? mc.player.getInventory().getSelectedSlot() : this.startSlot);
         if (this.recKeys.size() >= MAX_TICKS) {
            MacroClient.finishRecording(mc);
         }
      }

      if (this.playing != null && this.appliedThisTick) {
         this.advance(mc);
      }
   }

   /** After the tick just played: its events, then on to the next tick (or the next round, or the end). */
   private void advance(Minecraft mc) {
      this.appliedThisTick = false;
      this.waitLimit = WAIT_TICKS;
      if (!this.runEvents(mc)) {
         // the container of the next click is not open yet, or the awaited chat line has not come: stay on this
         // tick (applying it again changes nothing)
         if (++this.waited < this.waitLimit) {
            return;
         }

         this.skipBlocked();
      }

      this.waited = 0;
      this.waitingForChat = false;
      this.waitFrom = -1;
      if (this.playing == null) {
         return;
      }

      this.frame++;
      if (this.frame >= this.playing.length()) {
         this.played++;
         Recording r = this.playing;
         boolean goal = r.untilItem != null && !r.untilItem.isBlank();
         if (goal) {
            // round after round until the items are there (with a ceiling, should they never come)
            if (mc.player == null || carried(mc.player, r.untilItem.trim()) >= r.untilCount || this.played >= 9999) {
               this.stopPlayback(mc);
            } else {
               this.rewind(mc);
            }

            return;
         }

         int plays = r.plays();
         if (plays > 0 && this.played >= plays) {
            this.stopPlayback(mc);
         } else {
            this.rewind(mc);
         }
      }
   }

   /** What held the playback never came: drop it (the clicks meant for a container, or the wait) and go on. */
   private void skipBlocked() {
      Recording r = this.playing;
      if (r != null && r.events != null && this.eventAt < r.events.size() && "wait_chat".equals(r.events.get(this.eventAt).kind)) {
         this.eventAt++;
      } else {
         this.skipBlockedClicks();
      }
   }

   /** Runs the events of the tick just played; false when one has to wait for its container. */
   private boolean runEvents(Minecraft mc) {
      Recording r = this.playing;
      if (r.events == null) {
         return true;
      }

      while (this.eventAt < r.events.size() && r.events.get(this.eventAt).tick <= this.frame) {
         Recording.Event e = r.events.get(this.eventAt);
         if (e.tick == this.frame) {
            this.replaying = true;
            try {
               if (!this.run(mc, e)) {
                  return false;
               }
            } finally {
               this.replaying = false;
            }

            if (this.playing != r) {
               return true;
            }
         }

         this.eventAt++;
      }

      return true;
   }

   private boolean run(Minecraft mc, Recording.Event e) {
      LocalPlayer player = mc.player;
      ClientPacketListener listener = mc.getConnection();
      if (player == null || listener == null || e.kind == null) {
         return true;
      }

      switch (e.kind) {
         case "chat" -> {
            if (e.text != null) {
               listener.sendChat(e.text);
               this.sentAt = this.heardCount;
            }
         }
         case "command" -> {
            if (e.text != null) {
               listener.sendCommand(e.text);
               this.sentAt = this.heardCount;
            }
         }
         case "inventory" -> {
            if (mcport.tabs.Tabs.contextScreen() == null) {
               if (mc.gameMode != null && mc.gameMode.isServerControlledInventory()) {
                  player.sendOpenInventory();
               } else {
                  mc.gui.setScreen(new InventoryScreen(player));
               }
            }
         }
         case "close" -> {
            if (isContainer(mcport.tabs.Tabs.contextScreen())) {
               player.closeContainer();
            }
         }
         case "wait_chat" -> {
            if (e.text == null || e.text.isBlank()) {
               return true;
            }

            if (this.waitFrom < 0) {
               // lines since the playback last sent something count: a quick server has answered before the
               // wait is reached, and waiting for a second answer would never end
               this.waitFrom = this.sentAt >= 0 ? this.sentAt : this.heardCount;
            }

            if (!this.heardSince(this.waitFrom, e.text.trim().toLowerCase(java.util.Locale.ROOT))) {
               this.waitingForChat = true;
               this.waitLimit = e.timeout > 0 ? e.timeout * 20 : Integer.MAX_VALUE;
               return false;
            }
         }
         case "click" -> {
            if (e.input == null) {
               return true;
            }

            if (e.menu != null && !e.menu.equals(menuKey(player))) {
               return false;
            }

            try {
               if (mc.gameMode != null && e.slot < player.containerMenu.slots.size()) {
                  mc.gameMode.handleContainerInput(player.containerMenu.containerId, e.slot, e.button, ContainerInput.valueOf(e.input), player);
               }
            } catch (IllegalArgumentException ignored) {
               // a click kind this version does not know
            }
         }
         default -> {
         }
      }

      return true;
   }

   /** The container never came: drop the clicks that were meant for it and go on. */
   private void skipBlockedClicks() {
      Recording r = this.playing;
      if (r == null || r.events == null) {
         return;
      }

      while (this.eventAt < r.events.size() && "click".equals(r.events.get(this.eventAt).kind)) {
         this.eventAt++;
      }
   }

   private void capture(Minecraft mc, LocalPlayer player) {
      KeyMapping[] keys = keys(mc.options);
      int t = this.recKeys.size();
      int mask = 0;
      for (int i = 0; i < keys.length; i++) {
         if (keys[i].isDown()) {
            mask |= 1 << i;
         }

         int c = Keys.clicks(keys[i]) - this.seenClicks[i];
         if (i >= FIRST_CLICK_KEY && c > 0) {
            this.recClicks.add(t);
            this.recClicks.add(i);
            this.recClicks.add(c);
         }
      }

      this.recKeys.add(mask);
      float yaw = player.getYRot();
      float pitch = player.getXRot();
      this.offYaw += Mth.wrapDegrees(yaw - this.lastYaw);
      this.offPitch += pitch - this.lastPitch;
      this.lastYaw = yaw;
      this.lastPitch = pitch;
      this.recYaw.add((float)this.offYaw);
      this.recPitch.add((float)this.offPitch);
      this.capturedThisTick = true;
   }

   private void apply(Minecraft mc, LocalPlayer player) {
      Recording r = this.playing;
      int t = this.frame;
      KeyMapping[] keys = keys(mc.options);
      int mask = this.waitingForChat ? 0 : r.keys[t];
      this.drivingKeys = true;
      for (int i = 0; i < keys.length; i++) {
         Keys.setDown(keys[i], (mask & 1 << i) != 0);
         if (i >= FIRST_CLICK_KEY) {
            Keys.setClicks(keys[i], 0);
         }
      }

      while (this.clickAt + 2 < r.clicks.length && r.clicks[this.clickAt] <= t) {
         if (r.clicks[this.clickAt] == t) {
            int i = r.clicks[this.clickAt + 1];
            if (i >= 0 && i < keys.length) {
               Keys.addClicks(keys[i], r.clicks[this.clickAt + 2]);
            }
         }

         this.clickAt += 3;
      }

      if (r.slot != null) {
         int prev = t == 0 ? r.startSlot : r.slot[t - 1];
         int s = r.slot[t];
         if (s != prev && s >= 0 && s < 9) {
            player.getInventory().setSelectedSlot(s);
         }
      }

      if (r.hasLook()) {
         this.lookAt(player, r.yaw[t], r.pitch[t]);
      }

      this.appliedThisTick = true;
      if (DEBUG && t % 10 == 0 && this.waited == 0) {
         MacroConfig.LOGGER.info("[handykit] play {} tick {}/{}: keys {} up {} look {} -> yaw {} pitch {} slot {}", r.name, t, r.length(), mask,
            mc.options.keyUp.isDown(), r.hasLook() ? r.yaw[t] : "-", player.getYRot(), player.getXRot(), player.getInventory().getSelectedSlot());
      }
   }

   /** Per frame: ease the look from this tick's recorded direction toward the next one. */
   void frame(Minecraft mc) {
      Recording r = this.playing;
      // while the playback is held at a tick (waiting for a chest or a chat line) that tick is applied again and
      // again; easing toward it from the tick before would swing the look back and forth every tick
      if (r == null || this.paused || this.waited > 0 || !r.hasLook() || mc.player == null || mc.gui.screen() != null) {
         return;
      }

      // between ticks frame points at the tick to play next; frame - 1 is the one already applied
      int next = this.frame;
      if (next <= 0 || next >= r.length()) {
         return;
      }

      float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
      double yaw = Mth.lerp(pt, r.yaw[next - 1], r.yaw[next]);
      double pitch = Mth.lerp(pt, r.pitch[next - 1], r.pitch[next]);
      this.lookAt(mc.player, yaw, pitch);
   }

   /** Turns by the change since the last applied offset, so the player's own mouse still adds on top. */
   private void lookAt(LocalPlayer player, double yaw, double pitch) {
      double dy = yaw - this.appliedYaw;
      double dp = pitch - this.appliedPitch;
      this.appliedYaw = yaw;
      this.appliedPitch = pitch;
      if (dy != 0 || dp != 0) {
         // Entity.turn scales by 0.15 (mouse units) and keeps pitch in range, like a real mouse move
         player.turn(dy / 0.15, dp / 0.15);
      }
   }
}
