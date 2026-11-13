package mcport.tabs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.WeakHashMap;
import mcport.transfer.Via;
import mcport.tabs.mixin.ClientLevelAccess;
import mcport.tabs.mixin.CommonListenerAccess;
import mcport.tabs.mixin.GuiAccess;
import mcport.tabs.mixin.HudAccess;
import mcport.tabs.mixin.LoginListenerAccess;
import mcport.tabs.mixin.MinecraftAccess;
import mcport.tabs.mixin.PlayListenerAccess;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Several servers open at once, one on screen. Vanilla keeps "the" world in a handful of fields on Minecraft;
 * a tab that is not on screen keeps its own copies of those objects and they are swapped in only for the
 * moment its packets are handled or its player is ticked ("background context"). Everything a world does to the
 * shared client while it is in the background (sounds, particles, screens, renderer updates, resource packs,
 * registry tags) is stopped by the mixins in mcport.tabs.mixin, which all ask {@link #inBackground()}.
 */
public final class Tabs {
   public static final Logger LOGGER = LoggerFactory.getLogger("handykit/tabs");
   static final boolean DEBUG = Boolean.getBoolean("handykit.debug");

   /** The foreground's own state while a background tab is swapped in. */
   private static final World SLOT = new World();
   private static World current = SLOT;
   private static @Nullable Tab currentTab;
   private static final List<Tab> TABS = new ArrayList<>();
   /** Weak: a closed connection stays known (its late packets are dropped) until nothing refers to it. */
   private static final Map<Connection, Tab> BY_CONNECTION = Collections.synchronizedMap(new WeakHashMap<>());
   /** The tab that lives in Minecraft's own fields, if any. */
   private static @Nullable Tab active;
   private static @Nullable Hud baseHud;
   /** Who hears about the packs the server on screen sent. */
   private static final ArrayDeque<Runnable> REQUESTS = new ArrayDeque<>();
   /** Lines for the chat of whichever tab is on screen, written once the foreground is back. */
   private static final ArrayDeque<Component> NOTICES = new ArrayDeque<>();
   /** Set on the connector thread of a background reconnect, so the new connection is tied to its tab. */
   static final ThreadLocal<Tab> BACKGROUND_CONNECT = new ThreadLocal<>();
   /** The tab the connect screen is connecting right now; its connector thread logs in as that tab's account. */
   private static volatile @Nullable Tab connecting;
   /** On a connector thread: the account that thread logs in with. */
   private static final ThreadLocal<User> THREAD_USER = new ThreadLocal<>();
   private static @Nullable Thread clientThread;

   private Tabs() {
   }

   // ---------------------------------------------------------------- queries

   /** Test runs only (-Dhandykit.debug=true). */
   public static void trace(String line) {
      if (DEBUG) {
         LOGGER.info("[tabs] {}", line);
      }
   }

   // ---------------------------------------------------------------- accounts

   /**
    * Minecraft.getUser(): the account of the tab things are being done for, when that is not the account the game
    * was started with. On the client thread that is the tab in context (or on screen); connector threads carry
    * theirs along.
    */
   public static @Nullable User userOverride() {
      User thread = THREAD_USER.get();
      if (thread != null) {
         return thread;
      }

      if (TABS.isEmpty() || !onClientThread()) {
         return null;
      }

      Tab tab = currentTab != null ? currentTab : active;
      return tab != null ? tab.user : null;
   }

   /** The name the next new tab logs in with. */
   public static String nextAccountName() {
      Accounts.Account next = Accounts.next();
      return next != null ? next.name : ((MinecraftAccess)Minecraft.getInstance()).handykit$launcherUser().getName();
   }

   public static @Nullable User threadUser() {
      return THREAD_USER.get();
   }

   /**
    * Start of a connector thread (the connect screen's when tab is null). A Microsoft account gets a login token
    * that is good right now; this may talk to Microsoft, which is why it happens here and not on the client thread.
    */
   public static void onConnectorThread(@Nullable Tab tab) {
      Tab target = tab != null ? tab : connecting;
      if (target == null) {
         return;
      }

      Accounts.Account account = target.account;
      if (account != null) {
         try {
            target.user = Accounts.fresh(account);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         } catch (Exception e) {
            // the join then fails with "invalid session", and this line says why
            LOGGER.warn("Could not renew the login of {}: {}", account.name, e.getMessage() == null ? e.toString() : e.getMessage());
         }
      }

      User user = target.user;
      if (user != null) {
         THREAD_USER.set(user);
      }
   }

   /** Players that are me in some tab: their chat is my own chat coming back. */
   public static boolean isOwnPlayer(UUID id) {
      Minecraft mc = Minecraft.getInstance();
      for (Tab tab : TABS) {
         LocalPlayer player = tab == active || tab == currentTab ? null : tab.world.player;
         if (player != null && player.getUUID().equals(id)) {
            return true;
         }
      }

      return mc.player != null && mc.player.getUUID().equals(id);
   }

   /** The names I play under in the open tabs, lower case. */
   public static List<String> ownNames() {
      Minecraft mc = Minecraft.getInstance();
      List<String> names = new ArrayList<>(TABS.size() + 1);
      if (mc.player != null) {
         names.add(mc.player.getGameProfile().name().toLowerCase(java.util.Locale.ROOT));
      }

      for (Tab tab : TABS) {
         LocalPlayer player = tab.world.player;
         if (player != null) {
            names.add(player.getGameProfile().name().toLowerCase(java.util.Locale.ROOT));
         }
      }

      return names;
   }

   /** The server things are being done for, lower case, or null (no tab, or a singleplayer world). */
   public static @Nullable String contextAddress() {
      Tab tab = currentTab != null ? currentTab : active;
      return tab == null || tab.local ? null : tab.addressKey();
   }

   /** The player of a tab, wherever its world is kept right now (call from outside a tab's context). */
   static @Nullable LocalPlayer playerOf(Tab tab) {
      return tab == active ? Minecraft.getInstance().player : tab.world.player;
   }

   static int loadedChunks(Tab tab) {
      ClientLevel level = tab == active ? Minecraft.getInstance().level : tab.world.level;
      return level == null ? 0 : level.getChunkSource().getLoadedChunksCount();
   }

   /** The tab things are being done for: the one in context, else the one on screen. */
   public static @Nullable Tab contextTab() {
      return currentTab != null ? currentTab : active;
   }

   /** An id for "which tab is this happening in" (0 without tabs). */
   public static int contextId() {
      Tab tab = currentTab != null ? currentTab : active;
      return tab == null ? 0 : tab.id;
   }

   // ---------------------------------------------------------------- sound of a tab behind the screen

   /**
    * A world behind the screen wants to play a sound. With "every tab" chosen, one-shot sounds go through: placed
    * where the listener would hear them if it stood where that tab's player stands (the two worlds share nothing,
    * so the sound's own coordinates mean nothing to the listener). Looping and moving sounds, and music, stay with
    * the tab on screen.
    */
   public static boolean letsBackgroundSound(net.minecraft.client.resources.sounds.SoundInstance sound) {
      mcport.macro.MacroConfig config = mcport.macro.MacroConfig.base();
      if (config.tabSound == mcport.macro.MacroConfig.TabSound.CURRENT) {
         return false;
      }

      float scale = config.tabSound == mcport.macro.MacroConfig.TabSound.ALL ? 1.0F : Math.clamp(config.tabQuietVolume, 0.0F, 1.0F);
      Tab tab = currentTab;
      if (tab != null) {
         scale = tab.muted ? 0.0F : scale * tab.volume;
      }

      if (scale <= 0.0F
         || !(sound instanceof net.minecraft.client.resources.sounds.AbstractSoundInstance)
         || sound.isLooping()
         || sound.getSource() == net.minecraft.sounds.SoundSource.MUSIC) {
         return false;
      }

      Minecraft mc = Minecraft.getInstance();
      mcport.tabs.mixin.SoundInstanceAccess access = (mcport.tabs.mixin.SoundInstanceAccess)sound;
      if (sound instanceof net.minecraft.client.resources.sounds.TickableSoundInstance) {
         if (sound instanceof net.minecraft.client.resources.sounds.EntityBoundSoundInstance) {
            // a one-shot sound that follows an entity: played where the entity is now (following it would put it
            // back at that world's coordinates on the next tick)
            mc.getSoundManager().play(new net.minecraft.client.resources.sounds.SimpleSoundInstance(
               sound.getIdentifier(), sound.getSource(), access.handykit$volume(), access.handykit$pitch(),
               net.minecraft.client.resources.sounds.SoundInstance.createUnseededRandom(), false, 0, sound.getAttenuation(),
               access.handykit$x(), access.handykit$y(), access.handykit$z(), false
            ));
         }

         return false;
      }
      LocalPlayer player = mc.player;
      if (player != null && !sound.isRelative() && sound.getAttenuation() != net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE) {
         com.mojang.blaze3d.audio.ListenerTransform listener = mc.getSoundManager().getListenerTransform();
         double yaw = Math.toRadians(player.getYRot());
         // forward and right of the tab's player, and of the listener, on the ground plane
         double fbx = -Math.sin(yaw);
         double fbz = Math.cos(yaw);
         double rbx = -fbz;
         double rbz = fbx;
         double flx = listener.forward().x;
         double flz = listener.forward().z;
         double length = Math.sqrt(flx * flx + flz * flz);
         if (length < 1.0E-4) {
            flx = fbx;
            flz = fbz;
         } else {
            flx /= length;
            flz /= length;
         }

         double rlx = -flz;
         double rlz = flx;
         double ox = access.handykit$x() - player.getX();
         double oy = access.handykit$y() - player.getEyeY();
         double oz = access.handykit$z() - player.getZ();
         double forward = ox * fbx + oz * fbz;
         double right = ox * rbx + oz * rbz;
         access.handykit$setX(listener.position().x + forward * flx + right * rlx);
         access.handykit$setY(listener.position().y + oy);
         access.handykit$setZ(listener.position().z + forward * flz + right * rlz);
      }

      access.handykit$setVolume(access.handykit$volume() * scale);
      return true;
   }

   /** The tab on screen: the player may have turned it off or down. Menus and music are not the server's. */
   public static boolean letsForegroundSound(net.minecraft.client.resources.sounds.SoundInstance sound) {
      Tab tab = active;
      if (tab == null || !tab.muted && tab.volume >= 1.0F) {
         return true;
      }

      net.minecraft.sounds.SoundSource source = sound.getSource();
      if (source == net.minecraft.sounds.SoundSource.MUSIC || source == net.minecraft.sounds.SoundSource.MASTER || source == net.minecraft.sounds.SoundSource.UI) {
         return true;
      }

      if (tab.muted) {
         return false;
      }

      if (sound instanceof net.minecraft.client.resources.sounds.AbstractSoundInstance) {
         mcport.tabs.mixin.SoundInstanceAccess access = (mcport.tabs.mixin.SoundInstanceAccess)sound;
         access.handykit$setVolume(access.handykit$volume() * tab.volume);
      }

      return true;
   }

   // ---------------------------------------------------------------- input for a tab behind the screen

   /**
    * The new input object starts from what the old one last held. Without this a playback that changes sides of
    * the screen looks to the game like the forward key let go and pressed again, which is a double tap: sprint.
    */
   public static void carryInput(ClientInput from, ClientInput to) {
      to.keyPresses = from.keyPresses;
      ((mcport.tabs.mixin.ClientInputAccess)to).handykit$setMoveVector(from.getMoveVector());
   }

   /** The screen the tab in context has open (a chest the server opened), which is not the one on the monitor. */
   public static @Nullable Screen contextScreen() {
      Tab tab = currentTab;
      return tab != null ? tab.screen : Minecraft.getInstance().gui.screen();
   }

   /**
    * What Minecraft.handleKeybinds does for the attack, use, pick, drop and swap keys, done for the tab in
    * context from recorded input instead of the keyboard. Vanilla reads the real keys, the real mouse grab and
    * the screen on the monitor there, none of which belong to a tab behind the screen.
    */
   public static void backgroundKeys(Minecraft mc, boolean attackDown, boolean useDown, int attack, int use, int pick, int drop, int swap) {
      Tab tab = currentTab;
      LocalPlayer player = mc.player;
      ClientPacketListener listener = mc.getConnection();
      if (tab == null || player == null || listener == null || mc.gameMode == null || mc.level == null) {
         return;
      }

      MinecraftAccess access = (MinecraftAccess)mc;
      int delay = access.handykit$rightClickDelay();
      int miss = access.handykit$missTime();
      access.handykit$setRightClickDelay(Math.max(0, tab.rightClickDelay - 1));
      access.handykit$setMissTime(Math.max(0, tab.missTime - 1));
      try {
         Entity camera = mc.getCameraEntity();
         if (camera != null) {
            mc.hitResult = player.raycastHitResult(1.0F, camera);
            mc.crosshairPickEntity = mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit ? hit.getEntity() : null;
         }

         for (int i = 0; i < swap; i++) {
            if (!player.isSpectator()) {
               listener.send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                  net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                  net.minecraft.core.BlockPos.ZERO,
                  net.minecraft.core.Direction.DOWN
               ));
            }
         }

         for (int i = 0; i < drop; i++) {
            if (!player.isSpectator()) {
               mc.gameMode.dropItem(player, false);
            }
         }

         boolean instant = false;
         if (player.isUsingItem()) {
            if (!useDown) {
               mc.gameMode.releaseUsingItem(player);
            }
         } else if (mc.hitResult != null) {
            for (int i = 0; i < attack; i++) {
               instant |= access.handykit$startAttack();
            }

            for (int i = 0; i < use; i++) {
               access.handykit$startUseItem();
            }

            for (int i = 0; i < pick; i++) {
               access.handykit$pickBlockOrEntity();
            }
         }

         if (mc.hitResult != null) {
            if (useDown && access.handykit$rightClickDelay() == 0 && !player.isUsingItem()) {
               access.handykit$startUseItem();
            }

            access.handykit$continueAttack(!instant && attackDown);
         }
      } finally {
         tab.rightClickDelay = access.handykit$rightClickDelay();
         tab.missTime = access.handykit$missTime();
         access.handykit$setRightClickDelay(delay);
         access.handykit$setMissTime(miss);
      }
   }

   public static boolean inBackground() {
      return current != SLOT;
   }

   /** End of a frame (test runs time them). */
   public static void frame() {
      Perf.frame();
      Assets.frame(Minecraft.getInstance());
   }

   public static List<Tab> tabs() {
      return TABS;
   }

   public static @Nullable Tab active() {
      return active;
   }

   static @Nullable Tab backgroundTab() {
      return currentTab;
   }

   private static boolean onClientThread() {
      Thread thread = clientThread;
      if (thread == null) {
         Minecraft mc = Minecraft.getInstance();
         if (mc == null) {
            return false;
         }

         thread = clientThread = mc.getRunningThread();
      }

      return Thread.currentThread() == thread;
   }

   // ---------------------------------------------------------------- context

   /** Runs the action with this tab's world in Minecraft's fields; null or the active tab means the foreground. */
   static void runIn(@Nullable Tab tab, Runnable action) {
      World target = tab == null || tab == active ? SLOT : tab.world;
      World previous = current;
      if (previous == target) {
         action.run();
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      Tab previousTab = currentTab;
      previous.capture(mc);
      if (target.hud == null) {
         // never share a hud between worlds: chat and boss bars would leak across tabs
         target.hud = newHud(mc);
      }

      target.install(mc);
      current = target;
      currentTab = target == SLOT ? null : tab;
      try {
         action.run();
      } finally {
         // handlers replace the level and player on login, respawn and reconfiguration: keep what they left
         target.capture(mc);
         previous.install(mc);
         current = previous;
         currentTab = previousTab;
      }
   }

   /** No tab is open: packets need no sorting by world. */
   public static boolean idle() {
      return TABS.isEmpty();
   }

   /** Every packet handled on the client thread comes through here (PacketEntryMixin). */
   public static void handlePacket(PacketListener listener, Object packet, Runnable original) {
      if (TABS.isEmpty() || !onClientThread()) {
         original.run();
         return;
      }

      Tab tab = tabOf(listener);
      if (tab != null && tab.closed) {
         return;
      }

      Bgm.enterPacket();
      try {
         if (Perf.ON && tab != null && tab != active) {
            long started = Perf.now();
            runIn(tab, original);
            Perf.packet(tab, started, packet);
            return;
         }

         runIn(tab, original);
      } finally {
         Bgm.leavePacket();
      }
   }

   static @Nullable Tab tabOf(PacketListener listener) {
      Connection connection;
      if (listener instanceof CommonListenerAccess common) {
         connection = common.handykit$connection();
      } else if (listener instanceof LoginListenerAccess login) {
         connection = login.handykit$connection();
      } else {
         return null;
      }

      return BY_CONNECTION.get(connection);
   }

   static void bind(Tab tab, @Nullable Connection connection) {
      if (connection != null) {
         BY_CONNECTION.put(connection, tab);
         Packs.State packs = tab.packState;
         if (packs != null) {
            packs.connection = connection;
         }
      }

      tab.connection = connection;
   }

   // ---------------------------------------------------------------- tab lifecycle

   private static Tab open(String name, String address) {
      Tab tab = new Tab(name, address);
      TABS.add(tab);
      if (DEBUG) {
         LOGGER.info("[tabs] opened {}", tab);
      }

      return tab;
   }

   /** A singleplayer world behind the screen goes away: its server saves and stops (nobody else would stop it). */
   private static void stopServer(Tab tab, boolean wait) {
      net.minecraft.client.server.IntegratedServer server = tab.world.server;
      if (server != null && tab != active) {
         tab.world.server = null;
         LOGGER.info("Saving and closing the singleplayer world of tab {}", tab.title());
         server.halt(wait);
      }
   }

   /** A singleplayer world is about to start: the one waiting behind the screen makes room. */
   public static void onSingleplayerStart() {
      for (Tab tab : List.copyOf(TABS)) {
         if (tab != active && tab.world.server != null) {
            stopServer(tab, true);
            remove(tab);
         }
      }
   }

   private static void remove(Tab tab) {
      mcport.macro.MacroClient.forget(tab);
      stopServer(tab, false);
      Renderers.forget(tab);
      tab.tracks.clear();
      TABS.remove(tab);
      Packs.forget(tab);
      tab.closed = true;
      tab.screen = null;
      tab.world.clear();
      if (active == tab) {
         active = null;
      }

      if (DEBUG) {
         LOGGER.info("[tabs] removed {}", tab);
      }
   }

   /**
    * ConnectScreen.startConnecting, before vanilla does anything. Returns true when the connection was taken
    * over (a background tab following a transfer).
    */
   public static boolean onStartConnecting(Minecraft mc, ServerAddress address, ServerData data, @Nullable TransferState transfer) {
      if (inBackground()) {
         Tab tab = currentTab;
         if (tab != null) {
            LOGGER.info("Tab {} is sent on to {}, joining it behind the screen", tab.title(), address);
            BackgroundConnector.start(tab, address, data, transfer);
         }

         return true;
      }

      if (transfer == null) {
         // a second server while one is on screen: keep the first one as a tab instead of leaving it
         if (mc.player != null && mc.level != null && !mc.isLocalServer()) {
            detach(mc);
         }

         if (active != null && mc.player == null) {
            remove(active);
         }

         if (mc.player == null) {
            active = open(data.name, data.ip);
            active.data = data;
            Accounts.Account account = Accounts.next();
            if (account != null) {
               active.account = account;
               active.user = Accounts.user(account);
               LOGGER.info("Tab {} logs in as {}", active.title(), account.name);
            }
         }

         // the same server again (another account): the version the open tab speaks is the one this server takes
         for (Tab other : TABS) {
            if (other != active && !other.closed && other.address != null && other.address.equalsIgnoreCase(data.ip)) {
               Via.alreadyOn(address, other.connection);
               break;
            }
         }

      } else if (active != null) {
         active.address = address.toString();
      }

      connecting = active;
      return false;
   }

   /** ClientHandshakePacketListenerImpl constructor: a login is starting on this connection. */
   public static void onLoginListener(Connection connection, @Nullable ServerData data) {
      Tab background = BACKGROUND_CONNECT.get();
      if (background != null) {
         bind(background, connection);
         // from here on a dead connection means the reconnect failed
         background.transferring = false;
         background.connecting = false;
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      mc.execute(() -> {
         if (active == null || active.closed) {
            boolean local = data == null;
            active = open(local ? Component.translatable("handykit.tabs.singleplayer").getString() : data.name, local ? "" : data.ip);
            active.local = local;
         }

         bind(active, connection);
         active.transferring = false;
         active.connecting = false;
      });
   }

   /** Minecraft.disconnect in the foreground: the session on screen is ending. */
   public static void onForegroundDisconnect(Minecraft mc, boolean transferring) {
      ClientPacketListener listener = mc.getConnection();
      if (listener != null && active != null && transferring) {
         active.transferring = true;
         active.transferWait = 0;
      }

      if (listener == null || active == null || transferring) {
         return;
      }

      if (active.connection == null || active.connection == listener.getConnection()) {
         Component lost = foregroundLostOn == listener.getConnection() ? foregroundLost : null;
         foregroundLost = null;
         foregroundLostOn = null;
         Tab tab = active;
         if (lost != null && canRejoin(tab)) {
            active = null;
            kill(tab, lost);
         } else {
            remove(tab);
         }
      }
   }

   private static @Nullable Component foregroundLost;
   private static @Nullable Connection foregroundLostOn;

   /** onDisconnect of the connection on screen, just before Minecraft.disconnect. */
   public static void onForegroundLost(DisconnectionDetails details, Connection connection) {
      foregroundLost = details.reason();
      foregroundLostOn = connection;
   }

   private static boolean canRejoin(Tab tab) {
      mcport.macro.MacroConfig config = mcport.macro.MacroConfig.base();
      return config.tabReconnect && tab.data != null && !tab.local && tab.address != null && tab.reconnects < config.tabReconnectTries;
   }

   // ---------------------------------------------------------------- leaving and entering the screen

   private static Hud newHud(Minecraft mc) {
      if (baseHud == null) {
         baseHud = mc.gui.hud;
      }

      Hud hud = new Hud(mc);
      // loaded (waypoint styles) or wired up (debug charts) once at startup, for the first hud only
      ((HudAccess)hud).handykit$setWaypointStyles(((HudAccess)baseHud).handykit$waypointStyles());
      ((HudAccess)hud).handykit$setDebugOverlay(((HudAccess)baseHud).handykit$debugOverlay());
      return hud;
   }

   /** Screens the server opened and expects an answer to; they wait with the tab. */
   private static boolean keeps(@Nullable Screen screen) {
      if (screen == null) {
         return false;
      }

      if (screen instanceof AbstractContainerScreen<?> || screen instanceof DeathScreen) {
         return true;
      }

      String name = screen.getClass().getName();
      return name.contains("Dialog") || name.contains("CodeOfConduct") || name.contains("BookViewScreen") || name.contains("SignEditScreen");
   }

   /** A screen that is more than "nothing is going on": during a transfer, the question a mod asks before it lets it happen. */
   private static boolean asks(@Nullable Screen screen) {
      return screen != null
         && !(screen instanceof TitleScreen)
         && !(screen instanceof JoinMultiplayerScreen)
         && !(screen instanceof DisconnectedScreen)
         && !(screen instanceof WaitingScreen)
         && !(screen instanceof ConnectScreen);
   }

   /** Moves the session on screen into the background and leaves the foreground empty (no world, no screen). */
   private static @Nullable Tab detach(Minecraft mc) {
      ClientPacketListener listener = mc.getConnection();
      if (listener == null || mc.level == null || mc.player == null) {
         return null;
      }

      Tab tab = active;
      if (tab == null || tab.closed) {
         ServerData data = listener.getServerData();
         tab = open(data != null ? data.name : "", data != null ? data.ip : "");
      }

      bind(tab, listener.getConnection());
      // its music is due again when it is back
      Bgm.lost(tab);
      tab.serverRenderDistance = ((PlayListenerAccess)listener).handykit$serverChunkRadius();
      if (mc.gameMode != null) {
         mc.gameMode.stopDestroyBlock();
         if (mc.player.isUsingItem()) {
            mc.gameMode.releaseUsingItem(mc.player);
         }
      }

      KeyMapping.releaseAll();
      mcport.macro.MacroClient.onTabHidden(mc);
      // lines still waiting for the "chat delay" belong to this tab's chat: show them before it leaves
      mc.gui.chatListener().flushQueue();
      if (!mc.isLocalServer() && !tab.watch) {
         // nobody looks at this world for now: fewer chunks to receive and keep (a watched tab keeps its view)
         sendView(mc, listener, false);
      }
      Screen screen = mc.gui.screen();
      if (screen != null) {
         if (keeps(screen)) {
            tab.screen = screen;
         } else {
            tab.screen = null;
            screen.removed();
         }

         mc.textInputManager().stopTextInput();
         ((GuiAccess)mc.gui).handykit$setScreenRaw(null);
      } else {
         tab.screen = null;
      }

      tab.camera = mc.getCameraEntity();
      tab.world.capture(mc);
      tab.world.hit = null;
      tab.world.crosshair = null;
      // the keyboard belongs to whichever tab is on screen; in the background the player stands idle,
      // or goes on with the recording that is playing in this tab
      mc.player.input = mcport.macro.MacroClient.backgroundInput(tab, mc.player.input);
      mc.player = null;
      mc.level = null;
      mc.gameMode = null;
      mc.hitResult = null;
      mc.crosshairPickEntity = null;
      // a singleplayer world keeps its integrated server running behind the screen; the screen itself has none now
      ((MinecraftAccess)mc).handykit$setServer(null);
      ((MinecraftAccess)mc).handykit$setLocalServer(false);
      ((GuiAccess)mc.gui).handykit$setHud(newHud(mc));
      // the tab keeps its renderer and the terrain built in it; a spare one takes its place on screen
      Renderers.leave(mc, tab);
      Bgm.holding(true);
      try {
         ((MinecraftAccess)mc).handykit$updateLevelInEngines(null, true);
      } finally {
         Bgm.holding(false);
      }
      mc.gameRenderer.resetData();
      active = null;
      tab.unread = 0;
      tab.lastSeen = System.currentTimeMillis();
      if (DEBUG) {
         LOGGER.info("[tabs] detached {}", tab);
      }

      return tab;
   }

   /**
    * Tells a server how far its player wants to see: the player's own setting, or, for a tab nobody looks at, the
    * smaller "view of hidden tabs" (fewer chunks to receive and keep).
    */
   private static void sendView(Minecraft mc, ClientPacketListener listener, boolean looked) {
      net.minecraft.server.level.ClientInformation mine = mc.options.buildPlayerInformation();
      int view = mcport.macro.MacroConfig.base().tabBackgroundView;
      if (view <= 0 || view >= mine.viewDistance()) {
         // nothing is ever made smaller
         return;
      }

      if (looked) {
         listener.send(new net.minecraft.network.protocol.common.ServerboundClientInformationPacket(mine));
      } else {
         listener.send(new net.minecraft.network.protocol.common.ServerboundClientInformationPacket(new net.minecraft.server.level.ClientInformation(
            mine.language(), view, mine.chatVisibility(), mine.chatColors(), mine.modelCustomisation(), mine.mainHand(), mine.textFilteringEnabled(),
            mine.allowsListing(), mine.particleStatus()
         )));
      }
   }

   /** The player turned the small view of a tab on or off. */
   static void onWatchChanged(Tab tab) {
      if (tab.watch) {
         tab.watchSince = System.nanoTime();
         // three small views at most: a fourth takes the place of the one watched longest
         while (true) {
            int watched = 0;
            Tab oldest = null;
            for (Tab other : TABS) {
               if (other.watch) {
                  watched++;
                  if (other != tab && (oldest == null || other.watchSince < oldest.watchSince)) {
                     oldest = other;
                  }
               }
            }

            if (watched <= Pip.MOST || oldest == null) {
               break;
            }

            oldest.watch = false;
            onWatchChanged(oldest);
         }
      }

      if (tab == active || tab.closed || tab.local || !tab.world.inPlay() || mcport.macro.MacroConfig.base().tabBackgroundView <= 0) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      runIn(tab, () -> {
         ClientPacketListener listener = mc.getConnection();
         if (listener != null) {
            sendView(mc, listener, tab.watch);
         }
      });
   }

   /** Puts a background tab on screen. The foreground must be empty. */
   private static void attach(Minecraft mc, Tab tab) {
      World world = tab.world;
      Entity camera = tab.camera;
      Screen screen = tab.screen;
      tab.screen = null;
      tab.camera = null;
      world.install(mc);
      mc.hitResult = null;
      mc.crosshairPickEntity = null;
      LocalPlayer player = mc.player;
      if (player != null) {
         ClientInput before = player.input;
         player.input = new KeyboardInput(mc.options);
         if (mcport.macro.MacroClient.playing(tab)) {
            carryInput(before, player.input);
         }
      }

      world.clear();
      active = tab;
      tab.unread = 0;
      tab.lastSeen = System.currentTimeMillis();
      // its own resource set goes in before anything of its world is drawn or built; nothing is loaded for that
      Assets.show(mc, Assets.of(tab));
      Renderers.enter(mc, tab);
      // what its server was playing when it left, or started since, is due again
      Bgm.lost(tab);
      if (player != null) {
         mc.setCameraEntity(camera != null && !camera.isRemoved() ? camera : player);
      }

      if (tab.serverRenderDistance > 0) {
         mc.options.setServerRenderDistance(tab.serverRenderDistance);
      }

      ClientPacketListener listener = mc.getConnection();
      if (listener != null) {
         applyShared(tab, listener.registryAccess());
         if (mcport.macro.MacroConfig.base().tabBackgroundView > 0) {
            listener.send(new net.minecraft.network.protocol.common.ServerboundClientInformationPacket(mc.options.buildPlayerInformation()));
         }
      }

      tab.alert = false;

      if (mc.level != null) {
         ((ClientLevelAccess)mc.level).handykit$allMapData().forEach((id, data) -> mc.getMapTextureManager().update(id, data));
      }

      Via.follow(tab.connection);
      if (mc.level == null) {
         // still logging in after a transfer: wait on a screen that keeps its connection ticking
         mc.gui.setScreen(screen != null ? screen : new WaitingScreen(tab));
      } else {
         mc.gui.setScreen(screen);
      }

      // the log viewer splits a day by server on this line
      LOGGER.info("Tab on screen: {}", tab.address == null || tab.address.isBlank() ? "-" : logName(tab));
      if (DEBUG) {
         LOGGER.info("[tabs] attached {}", tab);
      }
   }

   /** Empties the foreground: the session on screen (a server, or a singleplayer world with its integrated server) goes behind it. */
   private static void leaveForeground(Minecraft mc) {
      if (mc.level != null && mc.player != null) {
         detach(mc);
      } else if (active != null && active.transferring && (active.connecting || asks(mc.gui.screen()))) {
         // a transfer under way or not answered yet: it goes back behind the screen as it is, question included
         Tab tab = active;
         Screen question = asks(mc.gui.screen()) ? mc.gui.screen() : null;
         tab.screen = question;
         active = null;
         if (question != null) {
            mc.textInputManager().stopTextInput();
            ((GuiAccess)mc.gui).handykit$setScreenRaw(null);
         }
      } else if (active != null) {
         // a connection attempt that never reached the world
         Connection pending = active.connection;
         if (pending != null && pending.isConnected()) {
            pending.disconnect(ConnectScreen.ABORT_CONNECTION);
         }

         remove(active);
      }

      Screen screen = mc.gui.screen();
      if (screen != null) {
         screen.removed();
         mc.textInputManager().stopTextInput();
         ((GuiAccess)mc.gui).handykit$setScreenRaw(null);
      }
   }

   /** False while something is loading that must not be interrupted. */
   static boolean canSwitch(Minecraft mc) {
      if (mc.gui.overlay() != null || inBackground()) {
         return false;
      }

      Screen screen = mc.gui.screen();
      if (screen instanceof ConnectScreen) {
         return false;
      }

      // a world on screen, or nothing at all (title, server list, disconnected)
      return mc.level != null && mc.player != null || active == null || active.connection == null || !active.connection.isConnected();
   }

   // ---------------------------------------------------------------- requests from the bar and the keys

   public static void requestSwitch(Tab tab) {
      REQUESTS.add(() -> switchTo(Minecraft.getInstance(), tab));
   }

   private static long lastCycle;

   public static void requestCycle(int direction) {
      // a held key repeats; one press is one tab
      long now = System.currentTimeMillis();
      if (now - lastCycle < 150L) {
         lastCycle = now;
         return;
      }

      lastCycle = now;
      REQUESTS.add(() -> {
         List<Tab> open = TABS.stream().filter(t -> !t.closed).toList();
         if (open.size() < 2 && !(open.size() == 1 && active == null)) {
            return;
         }

         int at = active == null ? (direction > 0 ? -1 : 0) : open.indexOf(active);
         switchTo(Minecraft.getInstance(), open.get(Math.floorMod(at + direction, open.size())));
      });
   }

   public static void requestNew() {
      REQUESTS.add(() -> {
         Minecraft mc = Minecraft.getInstance();
         if (!canSwitch(mc)) {
            return;
         }

         boolean hadWorld = mc.level != null;
         leaveForeground(mc);
         if (hadWorld) {
            LOGGER.info("Tab on screen: -");
         }

         mc.gui.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
      });
   }

   public static void requestClose(Tab tab) {
      REQUESTS.add(() -> close(Minecraft.getInstance(), tab));
   }

   private static void switchTo(Minecraft mc, Tab tab) {
      if (tab == active || tab.closed || !TABS.contains(tab) || !canSwitch(mc)) {
         return;
      }

      long started = Perf.now();
      leaveForeground(mc);
      long left = Perf.now();
      attach(mc, tab);
      if (Perf.ON) {
         LOGGER.info("[tabs] perf switch to {}: leaving {} ms, entering {} ms", tab, Perf.ms(left - started), Perf.ms(Perf.now() - left));
         Perf.switched(tab.toString());
      }
   }

   private static void close(Minecraft mc, Tab tab) {
      if (!TABS.contains(tab) || inBackground()) {
         return;
      }

      if (tab == active) {
         if (!canSwitch(mc)) {
            return;
         }

         if (mc.level != null) {
            mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
         } else {
            leaveForeground(mc);
            mc.gui.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
         }

         if (active == tab) {
            remove(tab);
         }

         // like closing a browser tab: the neighbour comes forward
         TABS.stream().filter(t -> !t.closed && t.world.inPlay()).reduce((a, b) -> a.lastSeen >= b.lastSeen ? a : b).ifPresent(next -> {
            leaveForeground(mc);
            attach(mc, next);
         });
         return;
      }

      Connection connection = tab.connection;
      remove(tab);
      if (connection != null && connection.isConnected()) {
         connection.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
      }
   }

   // ---------------------------------------------------------------- every tick

   /** End of Minecraft.tick: serve the background tabs, then act on what the bar and keys asked for. */
   private static int assetTicks;

   public static void endTick(Minecraft mc) {
      if (inBackground()) {
         return;
      }

      // the tab on screen is drawn with its own resource set (its packs may have come just now)
      Assets.init(mc);
      Assets.show(mc, Assets.of(active));
      Renderers.match(mc);
      Assets.animate(mc);
      if ((++assetTicks & 127) == 0) {
         Assets.tick(mc);
      }

      Bgm.tick(mc);
      if (!TABS.isEmpty()) {
         for (Tab tab : List.copyOf(TABS)) {
            if (tab != active && !tab.closed) {
               tickBackground(mc, tab);
            }

            housekeeping(mc, tab);
         }

         cleanForeground(mc);
      }

      // also without tabs: the version shown on the server list goes back to the player's own pick
      Via.keep(mc);

      if (alertDue) {
         ringAlert(mc);
      }

      stepOpening(mc);
      // The loading screen leaves when the renderer reports the chunk under the player as drawn; the game gives up
      // waiting after 30 seconds. Seen once in testing with a second tab open: the report never came. Eight seconds
      // of a loaded world behind a loading screen is long enough.
      ClientPacketListener loading = mc.getConnection();
      Runnable drawn = loading == null || mc.player == null || mc.level == null ? null : loading.getPlayerCompiledSectionCallback();
      if (drawn == null) {
         loadingTicks = 0;
      } else if (++loadingTicks > (mc.level.getChunkSource().hasChunk(mc.player.chunkPosition().x(), mc.player.chunkPosition().z()) ? 40 : 160)) {
         // two seconds when the chunk under the player is there (only the report is missing), eight when it is not
         LOGGER.info("The world on screen did not report its first chunk as drawn for {} ticks; going in", loadingTicks);
         loadingTicks = 0;
         drawn.run();
      }

      Component notice;
      while ((notice = NOTICES.poll()) != null) {
         if (mc.player != null) {
            mc.gui.hud.getChat().addClientSystemMessage(notice);
         }
      }

      Runnable request;
      while ((request = REQUESTS.poll()) != null) {
         try {
            request.run();
         } catch (RuntimeException e) {
            LOGGER.error("Tab request failed", e);
         }
      }
   }

   /** Once per tick for every tab: joining again after a loss, and what is sent after joining. */
   private static void housekeeping(Minecraft mc, Tab tab) {
      if (tab.closed) {
         if (tab.reconnectAt != 0 && System.currentTimeMillis() >= tab.reconnectAt && TABS.contains(tab)) {
            tab.reconnectAt = 0;
            tab.reconnects++;
            tab.closed = false;
            tab.closeReason = null;
            LOGGER.info("Tab {} joins {} again (try {} of {})", tab.title(), tab.address, tab.reconnects, mcport.macro.MacroConfig.base().tabReconnectTries);
            // a new session: its packs are the ones the server sends this time
            Packs.cleanup(tab);
            BackgroundConnector.start(tab, ServerAddress.parseString(tab.address), tab.data, null);
         }

         return;
      }

      boolean inPlay = tab == active ? mc.player != null && mc.level != null : tab.world.inPlay();
      if (inPlay && !tab.wasInPlay) {
         tab.playTicks = 0;
         tab.afterJoin.clear();
         String lines = tab.address == null ? null : mcport.macro.MacroConfig.base().joinCommands.get(tab.address.toLowerCase(java.util.Locale.ROOT));
         if (lines != null) {
            for (String line : lines.split(";")) {
               if (!line.isBlank()) {
                  tab.afterJoin.addLast(line.trim());
               }
            }

            tab.afterJoinWait = 60;
         }
      }

      tab.wasInPlay = inPlay;
      if (!inPlay) {
         return;
      }

      if (++tab.playTicks == 1200) {
         // it held for a minute: the next loss starts counting tries from zero
         tab.reconnects = 0;
      }

      if (!tab.afterJoin.isEmpty() && --tab.afterJoinWait <= 0) {
         String line = tab.afterJoin.pollFirst();
         tab.afterJoinWait = 20;
         // not typed by the player: not recorded, not passed on to linked tabs
         runIn(tab, () -> mcport.macro.MacroClient.automated(() -> say(mc, line)));
      }
   }

   /** Sends a line as the player of the tab in context: "/..." as a command, anything else as chat. */
   private static void say(Minecraft mc, String line) {
      ClientPacketListener listener = mc.getConnection();
      if (listener != null) {
         if (line.startsWith("/")) {
            listener.sendCommand(line.substring(1));
         } else {
            listener.sendChat(line);
         }
      }
   }

   // ---------------------------------------------------------------- several tabs at once

   private static boolean broadcasting;

   /** The player sent chat or a command in the tab on screen; tabs linked with it send the same. */
   public static void broadcast(String text, boolean command) {
      Tab from = active;
      if (broadcasting || inBackground() || from == null || !from.linked) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      broadcasting = true;
      try {
         for (Tab tab : List.copyOf(TABS)) {
            if (tab != from && tab.linked && !tab.closed && tab.world.inPlay()) {
               runIn(tab, () -> mcport.macro.MacroClient.automated(() -> say(mc, command ? "/" + text : text)));
            }
         }
      } finally {
         broadcasting = false;
      }
   }

   // ---------------------------------------------------------------- tab sets

   private static final ArrayDeque<mcport.macro.MacroConfig.TabEntry> OPENING = new ArrayDeque<>();
   private static int openWait;
   private static boolean autoOpened;

   /** The open tabs as a set that can be opened again in one go. */
   public static mcport.macro.MacroConfig.TabSet snapshot(String name) {
      mcport.macro.MacroConfig.TabSet set = new mcport.macro.MacroConfig.TabSet();
      set.name = name;
      for (Tab tab : TABS) {
         if (tab.closed || tab.local || tab.address == null || tab.address.isBlank()) {
            continue;
         }

         mcport.macro.MacroConfig.TabEntry entry = new mcport.macro.MacroConfig.TabEntry();
         entry.name = tab.title();
         entry.address = tab.address;
         Accounts.Account account = tab.account;
         if (account != null) {
            entry.account = account.name;
            entry.microsoft = account.microsoft();
         }

         set.tabs.add(entry);
      }

      return set;
   }

   public static void openSet(mcport.macro.MacroConfig.TabSet set) {
      for (mcport.macro.MacroConfig.TabEntry entry : set.tabs) {
         boolean open = false;
         for (Tab tab : TABS) {
            open |= !tab.closed && entry.address.equalsIgnoreCase(tab.address) && java.util.Objects.equals(entry.account, tab.accountName());
         }

         if (!open) {
            OPENING.addLast(entry);
         }
      }
   }

   /** Opens the next tab of a set once the one before it has reached its world (or failed). */
   private static void stepOpening(Minecraft mc) {
      if (!autoOpened && mc.gui.screen() instanceof TitleScreen && TABS.isEmpty() && mc.gui.overlay() == null) {
         autoOpened = true;
         for (mcport.macro.MacroConfig.TabSet set : mcport.macro.MacroConfig.base().tabSets) {
            if (set.autoOpen) {
               openSet(set);
               break;
            }
         }
      }

      if (OPENING.isEmpty()) {
         return;
      }

      if (openWait > 0) {
         openWait--;
         return;
      }

      Screen screen = mc.gui.screen();
      if (screen instanceof ConnectScreen || mc.gui.overlay() != null) {
         return;
      }

      // logging in or loading the world: wait for it
      if (mc.player == null && active != null && active.connection != null && active.connection.isConnected()) {
         return;
      }

      if (!canSwitch(mc)) {
         return;
      }

      mcport.macro.MacroConfig.TabEntry entry = OPENING.pollFirst();
      Accounts.choose(entry.account == null ? null : Accounts.find(entry.account, entry.microsoft));
      if (entry.account != null && Accounts.next() == null) {
         LOGGER.warn("The account {} of the tab set is not known here any more; {} is skipped", entry.account, entry.address);
         return;
      }

      leaveForeground(mc);
      ServerData data = new ServerData(entry.name, entry.address, ServerData.Type.OTHER);
      data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
      ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(entry.address), data, false, null);
      openWait = 40;
   }

   // ---------------------------------------------------------------- what the tab menu does

   public static void requestRename(Tab tab) {
      Minecraft mc = Minecraft.getInstance();
      mc.gui.setScreen(new PromptScreen(mc.gui.screen(), Component.translatable("handykit.tabs.menu.rename"), tab.title(), 48, name -> tab.customName = name.isBlank() ? null : name.trim()));
   }

   public static void requestJoinCommands(Tab tab) {
      if (tab.address == null) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      mcport.macro.MacroConfig config = mcport.macro.MacroConfig.base();
      String key = tab.address.toLowerCase(java.util.Locale.ROOT);
      mc.gui.setScreen(new PromptScreen(mc.gui.screen(), Component.translatable("handykit.tabs.menu.after_join"), config.joinCommands.getOrDefault(key, ""), 256, lines -> {
         if (lines.isBlank()) {
            config.joinCommands.remove(key);
         } else {
            config.joinCommands.put(key, lines.trim());
         }

         config.save();
      }));
   }

   /** The same server again, as another account: pick the account, then a new tab joins. */
   public static void requestDuplicate(Tab tab) {
      if (tab.address == null || tab.local) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      String name = tab.name;
      String address = tab.address;
      ServerData from = tab.data;
      mc.gui.setScreen(new AccountScreen(mc.gui.screen(), () -> REQUESTS.add(() -> {
         if (canSwitch(mc)) {
            leaveForeground(mc);
            ServerData data = new ServerData(name, address, ServerData.Type.OTHER);
            data.setResourcePackStatus(from != null ? from.getResourcePackStatus() : ServerData.ServerPackStatus.ENABLED);
            ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(address), data, false, null);
         }
      })));
   }

   /** The player changed how tabs share packs. */
   public static void packsChanged() {
   }

   /** Loads everything again with the packs of the tab on screen above the other tabs' (where two packs change the same thing, the upper one wins). */
   public static void requestPacks() {

   }

   /** Drops the foreground's tab when its connection attempt died before reaching the world. */
   private static void cleanForeground(Minecraft mc) {
      Tab tab = active;
      if (tab == null || mc.player != null) {
         return;
      }

      // while the connect screen is up a connection is on its way (also the second leg of a transfer,
      // when the old connection is already closed and the new one is not there yet)
      if (mc.gui.screen() instanceof ConnectScreen) {
         return;
      }

      if (tab.transferring) {
         // a mod may ask the player before it lets the transfer go on; the tab waits with that question
         if (tab.connecting || asks(mc.gui.screen())) {
            return;
         }

         tab.transferring = false;
      }

      Connection connection = tab.connection;
      if (connection == null || !connection.isConnected() && !connection.isConnecting()) {
         remove(tab);
      }
   }

   private static void tickBackground(Minecraft mc, Tab tab) {
      long started = Perf.now();
      try {
         runIn(tab, () -> {
            ClientLevel level = mc.level;
            LocalPlayer player = mc.player;
            if (level != null && player != null && mc.gameMode != null) {
               level.tickRateManager().tick();
               // a recording played in this tab goes on behind the screen: its input for this tick
               mcport.macro.MacroClient.backgroundStartTick(mc, tab);
               // connection tick: flushes, runs the listener's own tick, notices a closed connection
               mc.gameMode.tick();
               if (mc.level != level || mc.player != player) {
                  return;
               }

               ClientPacketListener listener = player.connection;
               Runnable loaded = listener.getPlayerCompiledSectionCallback();
               if (loaded != null) {
                  // nothing renders this world, so "the chunk under the player is drawn" is never reported
                  loaded.run();
               }

               if (tab.watch) {
                  // watched: everything in its world moves, as it would on screen
                  level.tickEntities();
                  Pip.tick(mc, tab);
               } else {
                  Entity root = player.getRootVehicle();
                  if (!root.isRemoved() && !level.tickRateManager().isEntityFrozen(root)) {
                     level.guardEntityTick(level::tickNonPassenger, root);
                  }
               }

               if (!player.isRemoved()) {
                  player.sendChanges();
               }

               level.tick(() -> true);
               level.update();
               mcport.macro.MacroClient.backgroundEndTick(mc, tab);
               listener.send(ServerboundClientTickEndPacket.INSTANCE);
            } else {
               Connection connection = tab.connection;
               if (connection != null) {
                  if (connection.isConnected()) {
                     connection.tick();
                  } else if (!tab.transferring) {
                     connection.handleDisconnection();
                  }
               }
            }
         });
      } catch (Throwable t) {
         LOGGER.error("Background tick of {} failed, closing it", tab, t);
         kill(tab, Component.literal(String.valueOf(t)));
         return;
      }

      Perf.tick(tab, started);
      if (tab.transferring && !tab.connecting && tab.screen == null && ++tab.transferWait > 100) {
         // nothing took the transfer up: no reconnect, no question
         tab.transferring = false;
      }

      Connection connection = tab.connection;
      if (!tab.closed && !tab.transferring && connection != null && !connection.isConnected() && !connection.isConnecting()) {
         DisconnectionDetails details = connection.getDisconnectionDetails();
         if (DEBUG) {
            LOGGER.info("[tabs] {} has a dead connection and no reconnect under way", tab);
         }

         kill(tab, details != null ? details.reason() : Component.translatable("disconnect.lost"));
      }
   }

   /** The tab's connection is gone: keep it in the bar, crossed out, until the player dismisses it. */
   static void kill(Tab tab, Component reason) {
      if (tab.closed) {
         return;
      }

      Connection connection = tab.connection;
      if (connection != null && connection.isConnected()) {
         connection.disconnect(reason);
      }

      mcport.macro.MacroClient.forget(tab);
      stopServer(tab, false);
      Renderers.forget(tab);
      tab.tracks.clear();
      Packs.cleanup(tab);
      tab.closed = true;
      tab.closeReason = reason;
      tab.screen = null;
      tab.wasInPlay = false;
      tab.afterJoin.clear();
      if (canRejoin(tab)) {
         tab.reconnectAt = System.currentTimeMillis() + Math.max(1, mcport.macro.MacroConfig.base().tabReconnectDelay) * 1000L;
      }
      tab.world.clear();
      LOGGER.info("Tab {} lost its connection: {}", tab.title(), reason.getString());
      NOTICES.add(Component.translatable("handykit.tabs.lost", tab.title(), reason));
   }

   // ---------------------------------------------------------------- what a background world may not do to the shared client

   /** A background listener's onDisconnect: tear down that world only. */
   public static void onBackgroundDisconnect(DisconnectionDetails details, boolean transferring) {
      Tab tab = currentTab;
      Minecraft mc = Minecraft.getInstance();
      if (DEBUG) {
         LOGGER.info("[tabs] background disconnect of {} transferring={} reason={}", tab, transferring, details.reason().getString());
      }

      clearBackgroundWorld(mc);
      if (tab != null && transferring) {
         // what continues the transfer (the reconnect, or a mod asking first) finds the tab waiting
         tab.transferring = true;
         tab.transferWait = 0;
      } else if (tab != null) {
         kill(tab, details.reason());
      }
   }

   /** Minecraft.disconnect or clearClientLevel called for a background world (reconfiguration, transfer). */
   public static void clearBackgroundWorld(Minecraft mc) {
      ClientPacketListener listener = mc.getConnection();
      if (listener != null) {
         listener.clearLevel();
      }

      mc.gameMode = null;
      if (mc.level != null) {
         mc.gui.hud.onDisconnected();
      }

      mc.level = null;
      mc.player = null;
      Tab tab = currentTab;
      if (tab != null) {
         tab.camera = null;
      }
   }

   public static void onBackgroundScreen(@Nullable Screen screen) {
      Tab tab = currentTab;
      if (tab == null) {
         return;
      }

      boolean question = tab.transferring && !tab.connecting && asks(screen);
      if (screen == null || keeps(screen) || question) {
         Screen old = tab.screen;
         tab.screen = screen;
         if (old != null && old != screen) {
            old.removed();
         }

         if (question) {
            LOGGER.info("Tab {} is asked before its transfer goes on: {}", tab.title(), screen.getClass().getSimpleName());
            NOTICES.add(Component.translatable("handykit.tabs.asks", tab.title()));
         }
      }
   }

   public static void setBackgroundCamera(@Nullable Entity entity) {
      if (currentTab != null) {
         currentTab.camera = entity;
      }
   }

   public static @Nullable Entity backgroundCamera() {
      return currentTab != null ? currentTab.camera : null;
   }

   public static void setBackgroundRenderDistance(int distance) {
      Tab tab = currentTab;
      if (tab != null) {
         // a hidden tab asked its server for a short view (the setting); that is not the distance its terrain was
         // built for, and must not become it: the terrain would be built anew on return, and again a moment later
         boolean shortened = !tab.watch && mcport.macro.MacroConfig.base().tabBackgroundView > 0;
         tab.serverRenderDistance = shortened ? Math.max(tab.serverRenderDistance, distance) : distance;
      }
   }

   private static final java.util.regex.Pattern HEAD_MARK = java.util.regex.Pattern.compile("\\[[^\\]]{1,24} head\\]");

   public static void onChatLine(Component message) {
      Tab tab = currentTab != null ? currentTab : active;
      if (tab == null) {
         return;
      }

      // the Chat Heads mod puts a head in front of names; as plain text it reads "[name head]"
      String text = HEAD_MARK.matcher(message.getString()).replaceAll("");
      tab.chat.addLast(text);
      while (tab.chat.size() > WatchPanel.MOST_LINES) {
         tab.chat.removeFirst();
      }

      if (currentTab != null) {
         currentTab.unread++;
      }
   }

   /**
    * A chat line arrived in the tab in context, with who sent it when the server says so. For a tab behind the
    * screen: does it call for the player (a whisper, their name, one of their words)?
    */
   public static void chatArrived(Component message, @Nullable UUID sender, boolean whisper) {
      Tab tab = currentTab;
      if (tab == null || tab.alert || sender != null && isOwnPlayer(sender)) {
         return;
      }

      String text = HEAD_MARK.matcher(message.getString()).replaceAll("");
      if (callsForAttention(message, text, whisper)) {
         tab.alert = true;
         alertDue = true;
         LOGGER.info("Tab {} calls for attention: {}", tab.title(), text);
      }
   }

   private static boolean alertDue;
   private static int loadingTicks;
   private static long lastAlert;

   /** A line in a tab behind the screen the player would want to know about: a whisper, their name, one of their words. */
   private static boolean callsForAttention(Component message, String text, boolean whisper) {
      mcport.macro.MacroConfig config = mcport.macro.MacroConfig.base();
      whisper |= message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().equals("commands.message.display.incoming");
      if (config.tabAlertWhisper && whisper) {
         return true;
      }

      String low = text.toLowerCase(java.util.Locale.ROOT);
      // servers that format chat themselves send no sender: a line that starts with one of my names is mine
      String start = null;
      for (String sep : new String[]{": ", "> ", "» "}) {
         int at = low.indexOf(sep);
         if (at >= 0) {
            start = low.substring(0, at);
            break;
         }
      }

      for (String mine : ownNames()) {
         // a line of mine, or a line about me (an advancement, a death message): nobody is calling
         if (!mine.isEmpty() && (start != null && start.contains(mine) || low.startsWith("* " + mine) || low.startsWith(mine))) {
            return false;
         }
      }

      LocalPlayer player = Minecraft.getInstance().player;
      if (config.tabAlertMention && player != null) {
         String me = player.getGameProfile().name().toLowerCase(java.util.Locale.ROOT);
         if (!me.isEmpty() && low.contains(me)) {
            return true;
         }
      }

      for (String word : config.tabAlertWords.split(",")) {
         String w = word.trim().toLowerCase(java.util.Locale.ROOT);
         if (!w.isEmpty() && low.contains(w)) {
            return true;
         }
      }

      return false;
   }

   /** On screen again: ring once and flash the task bar button (when the game window is not the one in front). */
   private static void ringAlert(Minecraft mc) {
      alertDue = false;
      long now = System.currentTimeMillis();
      if (now - lastAlert < 3000) {
         return;
      }

      lastAlert = now;
      if (mcport.macro.MacroConfig.base().tabAlertSound) {
         mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4F, 0.6F));
      }

      if (!mc.isWindowActive()) {
         try {
            org.lwjgl.sdl.SDLVideo.SDL_FlashWindow(mc.getWindow().handle(), org.lwjgl.sdl.SDLVideo.SDL_FLASH_UNTIL_FOCUSED);
         } catch (Throwable t) {
            // no task bar to flash
         }
      }
   }

   private record Logged(String text, String address, int tab, long at) {
   }

   private static final ArrayDeque<Logged> LOGGED = new ArrayDeque<>();

   /** The same line, on the same server, already went into the log from another of my tabs a moment ago. */
   public static boolean loggedByOtherTab(String text) {
      Tab tab = currentTab != null ? currentTab : active;
      if (tab == null || tab.address == null || TABS.size() < 2) {
         return false;
      }

      long now = System.currentTimeMillis();
      while (!LOGGED.isEmpty() && now - LOGGED.peekFirst().at() > 1500) {
         LOGGED.removeFirst();
      }

      for (Logged l : LOGGED) {
         if (l.tab() != tab.id && l.address().equals(tab.address) && l.text().equals(text)) {
            return true;
         }
      }

      LOGGED.addLast(new Logged(text, tab.address, tab.id, now));
      return false;
   }

   /** Chat of a background tab is written to the game log with its address in front, so the log viewer can file it. */
   public static String logTag(String text) {
      Tab tab = currentTab;
      return tab == null || tab.address == null || tab.address.isBlank() ? text : "⟦" + logName(tab) + "⟧ " + text;
   }

   /** How a tab is named in the log: its address, with the account when it is not the game's own. */
   private static String logName(Tab tab) {
      String account = tab.accountName();
      return account == null ? tab.address : tab.address + " (" + account + ")";
   }

   // ---------------------------------------------------------------- registry tags and item components

   private static @Nullable Tab recordingTab() {
      return inBackground() ? currentTab : active;
   }

   /** Tags as the server sent them, kept so the shared registries can be rebound when the tab is on screen. */
   public static void recordTags(Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags) {
      Tab tab = recordingTab();
      if (tab != null && onClientThread()) {
         tab.tags.putAll(tags);
      }
   }

   /**
    * Tags of the built-in registries (blocks, items, ...) and the item components derived from data are one global
    * set, bound by whichever server configured last. Bind the ones of the tab that is on screen.
    */
   private static void applyShared(Tab tab, RegistryAccess.Frozen registries) {
      try {
         tab.tags.forEach((key, payload) -> {
            if (!RegistrySynchronization.isNetworkable(key)) {
               registries.lookup(key).ifPresent(registry -> bindTags(registry, payload));
            }
         });
         BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pending -> {
            if (!RegistrySynchronization.isNetworkable(pending.key())) {
               pending.apply();
            }
         });
      } catch (RuntimeException e) {
         LOGGER.warn("Could not rebind tags for {}", tab, e);
      }
   }

   private static <T> void bindTags(Registry<T> registry, TagNetworkSerialization.NetworkPayload payload) {
      registry.prepareTagReload(payload.resolve(registry)).apply();
   }

   // ---------------------------------------------------------------- server resource packs

   /** The tab a connection that is starting on this thread belongs to. */
   static @Nullable Tab connectingTab() {
      Tab behind = BACKGROUND_CONNECT.get();
      if (behind != null) {
         return behind;
      }

      Tab tab = connecting;
      return tab != null ? tab : active;
   }

   /**
    * A server sends a resource pack. True when it was taken here and the game must not act on it: for a tab behind
    * the screen (the game would ask the player on the screen of another tab). The tab on screen goes the game's own
    * way, question and all, with the tab's own pack manager in place of the game's (PackSourceMixin).
    */
   public static boolean onPackPush(Connection connection, @Nullable ServerData data, ClientboundResourcePackPushPacket packet) {
      Tab tab = currentTab;
      return inBackground() && tab != null && Packs.pushBehind(tab, connection, data, packet);
   }

   /** A server takes a pack back: the game's own way, with the tab's own pack manager. */
   public static boolean onPackPop(ClientboundResourcePackPopPacket packet) {
      return false;
   }

   // ---------------------------------------------------------------- shutdown

   public static void shutdown() {
      for (Tab tab : List.copyOf(TABS)) {
         Connection connection = tab.connection;
         if (tab != active && connection != null && connection.isConnected()) {
            connection.disconnect(Component.translatable("multiplayer.status.quitting"));
         }

         // the game is closing: wait until the world behind the screen is written
         stopServer(tab, true);
      }
   }

   // ---------------------------------------------------------------- test hook

   /** For the verify harness: "new", "switch N", "next", "close N", "list". */
   public static void debug(String command) {
      String[] p = command.trim().split(" ");
      switch (p[0]) {
         case "new" -> requestNew();
         case "next" -> requestCycle(1);
         case "prev" -> requestCycle(-1);
         case "switch" -> TABS.stream().filter(t -> t.id == Integer.parseInt(p[1])).findFirst().ifPresent(Tabs::requestSwitch);
         case "close" -> TABS.stream().filter(t -> t.id == Integer.parseInt(p[1])).findFirst().ifPresent(Tabs::requestClose);
         case "account" -> Accounts.choose(p.length > 1 && !p[1].equals("-") ? Accounts.addOffline(p[1]) : null);
         case "accounts" -> REQUESTS.add(() -> Minecraft.getInstance().gui.setScreen(new AccountScreen(Minecraft.getInstance().gui.screen())));
         case "play" -> REQUESTS.add(() -> mcport.macro.MacroClient.debugPlay(p.length > 1 ? Integer.parseInt(p[1]) : -1));
         case "packs" -> requestPacks();
         case "reload" -> REQUESTS.add(() -> Minecraft.getInstance().reloadResourcePacks());
         case "perf" -> LOGGER.info("[tabs] perf {}", Perf.report());
         case "sounds" -> TABS.forEach(t -> LOGGER.info("[tabs] sounds {} {}", t.id, Bgm.describe(t)));
         case "saveset" -> {
            mcport.macro.MacroConfig shared = mcport.macro.MacroConfig.base();
            shared.tabSets.add(snapshot("test"));
            shared.save();
         }
         case "openset" -> REQUESTS.add(() -> openSet(mcport.macro.MacroConfig.base().tabSets.getLast()));
         case "flag" -> TABS.stream().filter(t -> t.id == Integer.parseInt(p[1])).findFirst().ifPresent(t -> {
            switch (p[2]) {
               case "link" -> t.linked = !t.linked;
               case "watch" -> {
                  t.watch = !t.watch;
                  onWatchChanged(t);
               }
               case "mute" -> t.muted = !t.muted;
               default -> {
               }
            }
         });
         case "connect" -> {
            Minecraft mc = Minecraft.getInstance();
            REQUESTS.add(() -> {
               if (canSwitch(mc)) {
                  leaveForeground(mc);
                  ServerData data = new ServerData(p.length > 2 ? p[2] : p[1], p[1], ServerData.Type.OTHER);
                  data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
                  ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(p[1]), data, false, null);
               }
            });
         }
         default -> {
         }
      }

      Minecraft mc = Minecraft.getInstance();
      StringBuilder sb = new StringBuilder();
      for (Tab tab : TABS) {
         World w = tab == active ? null : tab.world;
         LocalPlayer player = tab == active ? mc.player : w.player;
         sb.append(String.format(" #%d %s%s%s%s%s pos=%s unread=%d packs=%s chunks=%d;", tab.id, tab.title(), tab.account == null ? "" : "(" + tab.account.name + ")",
            tab == active ? "*" : "", tab.closed ? "(closed)" : "", mcport.macro.MacroClient.playing(tab) ? "(playing)" : "",
            player == null ? "-" : String.format("%.2f,%.2f,%.2f", player.getX(), player.getY(), player.getZ()), tab.unread, Packs.describe(tab), loadedChunks(tab)));
         if (tab.alert || tab.linked || tab.watch || tab.muted || tab.reconnects > 0) {
            sb.append(String.format(" [%s%s%s%s tries=%d]", tab.alert ? "alert " : "", tab.linked ? "link " : "", tab.watch ? "watch " : "", tab.muted ? "mute " : "", tab.reconnects));
         }

      }

      String profile = mcport.macro.MacroConfig.get().profile;
      LOGGER.info("[tabs] {} ->{} applied={} screen={} opening={} settings={} terrain={}", command, sb, Assets.describe(),
         mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(), OPENING.size(), profile == null ? "shared" : profile,
         Renderers.describe(mc));
   }
}
