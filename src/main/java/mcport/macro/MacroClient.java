package mcport.macro;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class MacroClient implements ClientModInitializer {
   private static final boolean DEBUG = Boolean.getBoolean("handykit.debug");
   public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("handykit", "main"));
   public static final KeyMapping SETTINGS = new KeyMapping("key.handykit.settings", InputConstants.KEY_M, CATEGORY);
   public static final KeyMapping LEFT = new KeyMapping("key.handykit.left", InputConstants.KEY_V, CATEGORY);
   public static final KeyMapping RIGHT = new KeyMapping("key.handykit.right", InputConstants.KEY_B, CATEGORY);
   public static final KeyMapping RECORD = new KeyMapping("key.handykit.record", InputConstants.KEY_F8, CATEGORY);
   public static final KeyMapping PLAY = new KeyMapping("key.handykit.play", InputConstants.KEY_F9, CATEGORY);
   /** Unbound until the player picks a key: hide every other player, and show them again. */
   public static final KeyMapping HIDE_ALL = new KeyMapping("key.handykit.hide_all", InputConstants.UNKNOWN.getValue(), CATEGORY);
   public static final KeyMapping[] PRESET = new KeyMapping[MacroConfig.PRESETS];

   static {
      int[] numpad = {InputConstants.KEY_NUMPAD1, InputConstants.KEY_NUMPAD2, InputConstants.KEY_NUMPAD3, InputConstants.KEY_NUMPAD4,
         InputConstants.KEY_NUMPAD5, InputConstants.KEY_NUMPAD6, InputConstants.KEY_NUMPAD7, InputConstants.KEY_NUMPAD8, InputConstants.KEY_NUMPAD9,
         InputConstants.KEY_NUMPAD0};
      for (int i = 0; i < PRESET.length; i++) {
         PRESET[i] = new KeyMapping("key.handykit.preset." + (i + 1), numpad[i], CATEGORY);
      }
   }

   static final AutoClicker CLICKER = new AutoClicker();
   /** Recording and playback belong to a tab: one recorder each, and one for a world without a tab. */
   private static final Recorder NO_TAB = new Recorder();
   private static final java.util.Map<Object, Recorder> RECORDERS = new java.util.WeakHashMap<>();

   /** A chat line arrived in the tab in context: a playback there may be waiting for it. */
   private static void heard(String line) {
      Object tab = mcport.tabs.Tabs.contextTab();
      Recorder r = tab == null ? NO_TAB : RECORDERS.get(tab);
      if (r != null) {
         r.onChatSeen(line);
      }
   }

   /** The recorder of the tab on screen. */
   static Recorder recorder() {
      Object tab = mcport.tabs.Tabs.active();
      return tab == null ? NO_TAB : RECORDERS.computeIfAbsent(tab, k -> new Recorder());
   }

   /** Whether a recording is being played in this tab (on screen or behind it). */
   public static boolean playing(Object tab) {
      Recorder r = RECORDERS.get(tab);
      return r != null && r.playing() != null;
   }

   /** The tab on screen goes behind it: the keyboard is free again, a playback there goes on by itself. */
   public static void onTabHidden(Minecraft mc) {
      CLICKER.stopAll(mc);
      Recorder r = recorder();
      if (r.recording()) {
         // what was recorded up to here is kept, not thrown away
         keep(r.finishRecording());
      }

      r.releaseKeys(mc);
   }

   /** What moves the player of a tab going behind the screen: nothing, or the recording playing there. */
   public static net.minecraft.client.player.ClientInput backgroundInput(Object tab, net.minecraft.client.player.ClientInput current) {
      Recorder r = RECORDERS.get(tab);
      if (r == null || r.playing() == null) {
         return new net.minecraft.client.player.ClientInput();
      }

      PlaybackInput input = new PlaybackInput();
      mcport.tabs.Tabs.carryInput(current, input);
      return input;
   }

   /** A recording is about to change under whoever plays it: stop them all. */
   static void stopPlaying(Recording recording) {
      Minecraft mc = Minecraft.getInstance();
      if (NO_TAB.playing() == recording) {
         NO_TAB.drop(mc);
      }

      for (Recorder r : RECORDERS.values()) {
         if (r.playing() == recording) {
            r.drop(mc);
         }
      }
   }

   /** The tab is closed or lost its server. */
   public static void forget(Object tab) {
      Recorder r = RECORDERS.remove(tab);
      if (r != null) {
         if (r.recording()) {
            keep(r.finishRecording());
         }

         r.drop(Minecraft.getInstance());
      }
   }

   /**
    * Runs something that sends chat or commands by itself (the lines a tab sends after joining). That is not the
    * player typing: it is not taken into a recording and not passed on to the tabs linked with this one.
    */
   public static void automated(Runnable action) {
      boolean before = ChatMacros.automatic;
      ChatMacros.automatic = true;
      try {
         action.run();
      } finally {
         ChatMacros.automatic = before;
      }
   }

   /** Tick of a tab behind the screen, before its player moves (its world is in Minecraft's fields). */
   public static void backgroundStartTick(Minecraft mc, Object tab) {
      // A player made behind the screen (the tab joined again, was sent on, or respawned) starts out reading the
      // keyboard, and the keyboard belongs to the tab on screen.
      if (mc.player != null && mc.player.input instanceof net.minecraft.client.player.KeyboardInput) {
         mc.player.input = backgroundInput(tab, mc.player.input);
      }

      Recorder r = RECORDERS.get(tab);
      if (r != null) {
         r.backgroundStartTick(mc);
      }
   }

   public static void backgroundEndTick(Minecraft mc, Object tab) {
      Recorder r = RECORDERS.get(tab);
      if (r != null) {
         r.backgroundEndTick(mc);
      }
   }

   /** Test hook: "edit N" opens the editor, "wait N text" puts a chat wait at a third of recording N, "until N item count" sets its goal. */
   public static void debug(String command) {
      Minecraft mc = Minecraft.getInstance();
      String[] p = command.trim().split(" ", 3);
      MacroConfig config = MacroConfig.base();
      int index = p.length > 1 ? Integer.parseInt(p[1]) : 0;
      if (index < 0 || index >= config.recordings.size()) {
         MacroConfig.LOGGER.info("[handykit] no recording {}", index);
         return;
      }

      Recording r = config.recordings.get(index);
      switch (p[0]) {
         case "edit" -> mc.gui.setScreen(new RecordingEditScreen(mc.gui.screen() == null ? new MacroScreen(null) : mc.gui.screen(), r));
         case "wait" -> {
            Recording.Event e = new Recording.Event();
            e.tick = r.length() / 3;
            e.kind = "wait_chat";
            e.text = p.length > 2 ? p[2] : "go";
            if (r.events == null) {
               r.events = new java.util.ArrayList<>();
            }

            r.events.add(e);
            r.events.sort(java.util.Comparator.comparingInt(v -> v.tick));
         }
         case "until" -> {
            String[] q = (p.length > 2 ? p[2] : "minecraft:stone 3").split(" ");
            r.untilItem = q[0];
            r.untilCount = q.length > 1 ? Integer.parseInt(q[1]) : 1;
         }
         case "speed" -> r.speed(p.length > 2 ? Double.parseDouble(p[2]) : 2.0);
         case "trim" -> {
            String[] q = p[2].split(" ");
            r.trim(Integer.parseInt(q[0]), Integer.parseInt(q[1]));
         }
         case "append" -> r.append(config.recordings.get(Integer.parseInt(p[2].trim())));
         default -> {
         }
      }

      MacroConfig.LOGGER.info("[handykit] {}: recording {} has {} ticks, {} events, until {} x{}", command, r.name, r.length(), r.events == null ? 0 : r.events.size(),
         r.untilItem, r.untilCount);
   }

   /** Test hook: plays recording number index in the tab on screen (the selected one for -1), or stops it. */
   public static void debugPlay(int index) {
      Minecraft mc = Minecraft.getInstance();
      Recorder r = recorder();
      MacroConfig config = MacroConfig.base();
      if (r.playing() != null) {
         r.stopPlayback(mc);
      } else {
         r.startPlayback(mc, index >= 0 && index < config.recordings.size() ? config.recordings.get(index) : config.selectedRecording());
      }
   }

   static final ChatMacros CHAT = new ChatMacros();
   /** A short line under the status, e.g. "nothing was recorded". */
   private static Component notice;
   private static long noticeUntil;
   /** The chat line last asked about and its plain text: the events one line goes through ask for it in turn. */
   private static Component lastLine;
   private static String lastText = "";
   /** Whether each of the mod's keys was physically down at the last look, to tell a press from the repeats of a held key. */
   private static final java.util.Map<KeyMapping, Boolean> HELD = new java.util.IdentityHashMap<>();

   public static String text(Component message) {
      if (message != lastLine) {
         lastText = message.getString();
         lastLine = message;
      }

      return lastText;
   }

   /**
    * True once per press. A key held down repeats, and the game counts every repeat as another click: without
    * this a held preset key sends its line ten times a second, and a held toggle flips back and forth. Ask once a
    * tick for each key (the next-tab key of the server tabs can use this too).
    */
   public static boolean pressed(KeyMapping key) {
      boolean clicked = false;
      while (key.consumeClick()) {
         clicked = true;
      }

      // the real key, not the mapping's own flag: that one is cleared whenever a menu opens or closes
      boolean held = Boolean.TRUE.equals(HELD.put(key, Keys.held(key)));
      return clicked && !held;
   }

   @Override
   public void onInitializeClient() {
      KeyMappingHelper.registerKeyMapping(SETTINGS);
      KeyMappingHelper.registerKeyMapping(LEFT);
      KeyMappingHelper.registerKeyMapping(RIGHT);
      KeyMappingHelper.registerKeyMapping(RECORD);
      KeyMappingHelper.registerKeyMapping(PLAY);
      if (Features.IGNORE) {
         KeyMappingHelper.registerKeyMapping(HIDE_ALL);
      }
      for (KeyMapping key : PRESET) {
         KeyMappingHelper.registerKeyMapping(key);
      }

      MacroConfig.get();
      ClientTickEvents.START_CLIENT_TICK.register(client -> {
         // recorder first: it must see the player's own input before playback or the clicker add theirs
         recorder().startTick(client);
         CLICKER.startTick(client);
      });
      ClientTickEvents.END_CLIENT_TICK.register(MacroClient::endTick);
      // before anything else sees the line: chat of hidden players never reaches the chat box, the log or the replies
      if (Features.IGNORE) {
         try {
            Class.forName("mcport.ignore.Ignore").getMethod("register").invoke(null);
         } catch (ReflectiveOperationException e) {
            MacroConfig.LOGGER.warn("The ignore list could not be set up: {}", e.toString());
         }
      }
      ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
         String text = text(message);
         heard(text);
         mcport.tabs.Tabs.chatArrived(message, sender == null ? null : sender.id(), params != null && params.chatType().is(net.minecraft.network.chat.ChatType.MSG_COMMAND_INCOMING));
         CHAT.onPlayerChat(Minecraft.getInstance(), text, signed, sender);
      });
      ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
         if (!overlay) {
            String text = text(message);
            heard(text);
            mcport.tabs.Tabs.chatArrived(message, null, false);
            CHAT.onGameMessage(Minecraft.getInstance(), text);
         }
      });
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
         // a server tab closing in the background is not the world the macros act on
         if (!mcport.tabs.Tabs.inBackground()) {
            client.execute(() -> onWorldLeft(client));
         }
      });
      HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("handykit", "status"), (g, delta) -> drawStatus(g));
   }

   /** The world on screen is going away (disconnect, or its tab moved to the background): stop acting on it. */
   public static void onWorldLeft(Minecraft mc) {
      CLICKER.stopAll(mc);
      NO_TAB.drop(mc);
   }

   private static void endTick(Minecraft mc) {
      recorder().endTick(mc);
      CHAT.tick(mc);
      if (pressed(SETTINGS) && mc.gui.screen() == null) {
         mc.gui.setScreen(new MacroScreen(null));
      }

      if (pressed(LEFT)) {
         CLICKER.toggle(mc, CLICKER.left);
      }

      if (pressed(RIGHT)) {
         CLICKER.toggle(mc, CLICKER.right);
      }

      Recorder recorder = recorder();
      if (pressed(RECORD)) {
         if (recorder.recording()) {
            finishRecording(mc);
         } else {
            recorder.startRecording(mc);
         }
      }

      if (pressed(PLAY)) {
         if (recorder.playing() != null) {
            recorder.stopPlayback(mc);
         } else if (recorder.recording()) {
            // F9 while recording: keep it and play it right away (nothing to play when nothing was recorded)
            if (finishRecording(mc)) {
               recorder.startPlayback(mc, MacroConfig.base().selectedRecording());
            }
         } else {
            Recording r = MacroConfig.base().selectedRecording();
            if (r == null) {
               notice(Component.translatable("handykit.notice.no_recording"));
            } else {
               recorder.startPlayback(mc, r);
            }
         }
      }

      if (Features.IGNORE && pressed(HIDE_ALL)) {
         MacroConfig config = MacroConfig.get();
         config.hideAllPlayers = !config.hideAllPlayers;
         config.save();
         notice(Component.translatable(config.hideAllPlayers ? "handykit.notice.hide_all_on" : "handykit.notice.hide_all_off"));
      }

      for (int i = 0; i < PRESET.length; i++) {
         if (pressed(PRESET[i])) {
            CHAT.sendPreset(mc, i);
         }
      }
   }

   /** ClientPacketListener.sendChat and sendCommand: whatever the player sends while recording becomes part of it. */
   public static void onChatSent(net.minecraft.client.multiplayer.ClientPacketListener listener, String text, boolean command) {
      // only what the player does on screen is recorded; a playback behind the screen sends chat of its own
      if (!mcport.tabs.Tabs.inBackground()) {
         Recorder r = recorder();
         r.onChat(Minecraft.getInstance(), listener, text, command);
         // tabs linked with this one get what the player typed (or a preset key): not a keyword reply, not what a
         // playback sends, not the lines sent after joining
         if (!ChatMacros.automatic && !r.replaying() && listener == Minecraft.getInstance().getConnection()) {
            mcport.tabs.Tabs.broadcast(text, command);
         }
      }
   }

   /** MultiPlayerGameMode.handleContainerInput: a slot click in a chest or the inventory. */
   public static void onContainerInput(int containerId, int slot, int button, net.minecraft.world.inventory.ContainerInput input) {
      if (!mcport.tabs.Tabs.inBackground()) {
         recorder().onContainerInput(Minecraft.getInstance(), containerId, slot, button, input);
      }
   }

   /** Ends the recording of the tab on screen and files it; false when nothing happened in it. */
   static boolean finishRecording(Minecraft mc) {
      return keep(recorder().finishRecording());
   }

   /** Files a finished recording under the next free name and selects it. The list is the same for every server. */
   private static boolean keep(Recording r) {
      if (r == null) {
         notice(Component.translatable("handykit.notice.empty"));
         return false;
      }

      MacroConfig config = MacroConfig.base();
      r.name = nextName(config);
      config.recordings.add(r);
      config.selected = config.recordings.size() - 1;
      config.save();
      config.saveRecordings();
      if (DEBUG) {
         MacroConfig.LOGGER.info("[handykit] recorded {}: {} ticks, {} clicks, {} events, look {}", r.name, r.length(), r.clicks.length / 3,
            r.events == null ? 0 : r.events.size(), r.hasLook());
      }

      return true;
   }

   private static String nextName(MacroConfig config) {
      String base = Component.translatable("handykit.record.default_name").getString();
      for (int n = config.recordings.size() + 1; ; n++) {
         String name = base + " " + n;
         if (config.recordings.stream().noneMatch(r -> r.name.equals(name))) {
            return name;
         }
      }
   }

   static void notice(Component text) {
      notice = text;
      noticeUntil = System.currentTimeMillis() + 2500;
   }

   /** Called once per frame from the mouse handler. */
   public static void onFrame() {
      recorder().frame(Minecraft.getInstance());
   }

   // ---------------------------------------------------------------- status in the top left corner

   private static void drawStatus(GuiGraphicsExtractor g) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || mc.getDebugOverlay().showDebugScreen()) {
         return;
      }

      Font font = mc.font;
      int x = 4;
      // below the server tabs when they are showing
      int y = mcport.tabs.TabBar.showsInGame() ? mcport.tabs.TabBar.HEIGHT + 4 : 4;
      Recorder recorder = recorder();
      if (recorder.recording()) {
         boolean blink = System.currentTimeMillis() / 500 % 2 == 0;
         y = line(g, font, x, y, blink ? 0xFFFF4040 : 0xFF802020, "●", Recording.time(recorder.recordedTicks()), 0xFFFFFFFF);
      }

      Recording playing = recorder.playing();
      if (playing != null) {
         int plays = playing.plays();
         Component count = plays == 0
            ? Component.literal(recorder.playNumber() + "/∞")
            : Component.literal(recorder.playNumber() + "/" + plays);
         Component text = Component.literal(playing.name + "  ").append(count);
         y = line(g, font, x, y, recorder.paused() ? 0xFF808080 : 0xFF50E070, recorder.paused() ? "Ⅱ" : "▶", text, 0xFFFFFFFF);
      }

      for (AutoClicker.Side side : new AutoClicker.Side[]{CLICKER.left, CLICKER.right}) {
         if (side.on) {
            MacroConfig.Clicker c = side.config();
            Component mode = c.mode == MacroConfig.ClickMode.CLICK
               ? Component.translatable("handykit.status.cps", cps(c.cps))
               : Component.translatable("handykit.mode." + c.mode.name().toLowerCase(java.util.Locale.ROOT));
            Component text = Component.translatable(side.left ? "handykit.status.left" : "handykit.status.right").append("  ").append(mode);
            y = line(g, font, x, y, 0xFFF0C24E, "◆", text, 0xFFFFFFFF);
         }
      }

      if (notice != null && System.currentTimeMillis() < noticeUntil) {
         g.text(font, notice, x, y, 0xFFC0C0C0, true);
      }
   }

   private static int line(GuiGraphicsExtractor g, Font font, int x, int y, int markColor, String mark, Component text, int color) {
      g.text(font, mark, x, y, markColor, true);
      g.text(font, text, x + font.width(mark) + 4, y, color, true);
      return y + 11;
   }

   static String cps(double cps) {
      return cps == Math.rint(cps) ? Integer.toString((int)cps) : Double.toString(cps);
   }
}
