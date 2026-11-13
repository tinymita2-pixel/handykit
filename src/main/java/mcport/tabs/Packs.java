package mcport.tabs;

import com.google.common.hash.HashCode;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import mcport.tabs.mixin.PackSourceAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.server.PackDownloader;
import net.minecraft.client.resources.server.PackLoadFeedback;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.jspecify.annotations.Nullable;

/**
 * The server resource packs of each tab.
 *
 * <p>The game has one list of server packs and one conversation about them with one server. Here every tab has a
 * pack manager of its own (the game's own class, one object a tab): it takes what that tab's server sends, has it
 * downloaded through the game's downloader, and tells that server how it went, word for word as the game would.
 * The one thing done differently is "load": instead of the game loading all its resources anew, the tab's packs
 * are made into a resource set beside the game ({@link Assets}), and the server hears "loaded" when that set is
 * there. So a tab behind the screen gets its packs for real, and the game's own resources never change.
 */
public final class Packs {
   private static final Pattern SHA1 = Pattern.compile("^[a-fA-F0-9]{40}$");

   /** What a tab has of its own for its packs. */
   static final class State {
      final Tab tab;
      final ServerPackManager manager;
      /** The connection that hears how the packs fared; null for a world of this computer. */
      volatile @Nullable Connection connection;
      /** The packs that make up the tab's look right now: bottom first. */
      List<PackReloadConfig.IdAndPath> loaded = List.of();

      State(Tab tab, Minecraft mc) {
         this.tab = tab;
         AtomicBoolean scheduled = new AtomicBoolean();
         Runnable[] tick = new Runnable[1];
         Runnable update = () -> {
            if (scheduled.compareAndSet(false, true)) {
               mc.schedule(tick[0]);
            }
         };
         this.manager = new ServerPackManager(downloader(mc), new PackLoadFeedback() {
            @Override
            public void reportUpdate(UUID id, PackLoadFeedback.Update update) {
               State.this.send(id, switch (update) {
                  case ACCEPTED -> ServerboundResourcePackPacket.Action.ACCEPTED;
                  case DOWNLOADED -> ServerboundResourcePackPacket.Action.DOWNLOADED;
               });
            }

            @Override
            public void reportFinalResult(UUID id, PackLoadFeedback.FinalResult result) {
               State.this.send(id, switch (result) {
                  case APPLIED -> ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED;
                  case DOWNLOAD_FAILED -> ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD;
                  case DECLINED -> ServerboundResourcePackPacket.Action.DECLINED;
                  case DISCARDED -> ServerboundResourcePackPacket.Action.DISCARDED;
                  case ACTIVATION_FAILED -> ServerboundResourcePackPacket.Action.FAILED_RELOAD;
               });
            }
         }, callbacks -> request(mc, this, callbacks), update, ServerPackManager.PackPromptStatus.PENDING);
         tick[0] = () -> {
            scheduled.set(false);
            if (!tab.closed || Tabs.tabs().contains(tab)) {
               this.manager.tick();
            }
         };
      }

      /**
       * Only a connection that is past the login can take a pack answer; one that is still logging in (the second
       * leg of a transfer) has not asked for anything yet, and the packet does not even exist there.
       */
      private void send(UUID id, ServerboundResourcePackPacket.Action action) {
         Connection to = this.connection;
         if (to != null && to.isConnected() && to.getPacketListener() instanceof ClientCommonPacketListenerImpl) {
            Tabs.trace("pack " + id + " of tab " + this.tab.title() + ": " + action);
            to.send(new ServerboundResourcePackPacket(id, action));
         }
      }
   }

   private static @Nullable PackDownloader downloader;

   private Packs() {
   }

   /** The game's own downloader (its cache folder, its queue, its progress toast), shared by every tab. */
   private static PackDownloader downloader(Minecraft mc) {
      if (downloader == null) {
         try {
            Field field = ServerPackManager.class.getDeclaredField("downloader");
            field.setAccessible(true);
            downloader = (PackDownloader)field.get(((PackSourceAccess)mc.getDownloadedPackSource()).handykit$manager());
         } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
         }
      }

      return downloader;
   }

   static State state(Tab tab) {
      State state = tab.packState;
      if (state == null) {
         state = tab.packState = new State(tab, Minecraft.getInstance());
      }

      return state;
   }

   private static boolean mine(@Nullable Tab tab) {
      return tab != null && !tab.local && Assets.works();
   }

   /** DownloadedPackSource hands a server's push, pop or the player's answer to "the" pack manager: the tab's own. */
   public static ServerPackManager managerNow(ServerPackManager original) {
      Tab tab = Tabs.contextTab();
      return mine(tab) ? state(tab).manager : original;
   }

   /**
    * DownloadedPackSource.configureForServerControl, on the thread that connects: a connection starts and this is
    * what the server list says about its packs. True when a tab took it.
    */
   public static boolean control(Connection connection, ServerPackManager.PackPromptStatus status) {
      Tab tab = Tabs.connectingTab();
      if (!mine(tab)) {
         return false;
      }

      control(tab, connection, status);
      return true;
   }

   static void control(Tab tab, Connection connection, ServerPackManager.PackPromptStatus status) {
      State state = state(tab);
      state.connection = connection;
      switch (status) {
         case ALLOWED -> state.manager.allowServerPacks();
         case DECLINED -> state.manager.rejectServerPacks();
         case PENDING -> state.manager.resetPromptStatus();
      }
   }

   /** A tab behind the screen connects (nobody calls the game's own setup for it). Nobody can be asked there either: a server that would ask is taken as allowed. */
   static void controlBehind(Tab tab, Connection connection, @Nullable ServerData data) {
      if (!mine(tab)) {
         return;
      }

      boolean declined = data != null && data.getResourcePackStatus() == ServerData.ServerPackStatus.DISABLED;
      control(tab, connection, declined ? ServerPackManager.PackPromptStatus.DECLINED : ServerPackManager.PackPromptStatus.ALLOWED);
   }

   /**
    * A server sends a pack to a tab behind the screen. True: it was taken here (the game would open its question
    * on the screen of another tab).
    */
   static boolean pushBehind(Tab tab, Connection connection, @Nullable ServerData data, ClientboundResourcePackPushPacket packet) {
      if (!mine(tab)) {
         return false;
      }

      State state = state(tab);
      state.connection = connection;
      if (data != null && data.getResourcePackStatus() == ServerData.ServerPackStatus.DISABLED) {
         connection.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.DECLINED));
         return true;
      }

      URL url;
      try {
         url = new URL(packet.url());
         if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
            url = null;
         }
      } catch (MalformedURLException e) {
         url = null;
      }

      if (url == null) {
         connection.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.INVALID_URL));
         return true;
      }

      String hash = packet.hash();
      state.manager.allowServerPacks();
      state.manager.pushPack(packet.id(), url, hash != null && SHA1.matcher(hash).matches() ? HashCode.fromString(hash.toLowerCase(java.util.Locale.ROOT)) : null);
      return true;
   }

   /** The tab's session ended (not a transfer): the server packs go, as the game drops them on leaving. */
   public static void cleanup(@Nullable Tab tab) {
      if (tab == null || tab.packState == null) {
         return;
      }

      tab.packState.manager.popAll();
      tab.packState.manager.resetPromptStatus();
   }

   /** The tab is closed for good. */
   static void forget(Tab tab) {
      State state = tab.packState;
      if (state != null) {
         state.connection = null;
         state.loaded = List.of();
      }

      tab.look = null;
      tab.nextLook = null;
   }

   /** The tab's pack manager wants its packs "loaded": the set of exactly these packs is made (or is there already). */
   private static void request(Minecraft mc, State state, PackReloadConfig.Callbacks callbacks) {
      List<PackReloadConfig.IdAndPath> wanted = List.copyOf(callbacks.packsToLoad());
      take(mc, state, wanted, made -> {
         if (made) {
            callbacks.onSuccess();
            return;
         }

         // as the game does when loading fails: the new packs are given up, what was in use before stays
         callbacks.onFailure(false);
         List<PackReloadConfig.IdAndPath> before = List.copyOf(callbacks.packsToLoad());
         take(mc, state, before, again -> {
            if (again) {
               callbacks.onSuccess();
            } else {
               state.loaded = List.of();
               state.tab.look = null;
               callbacks.onFailure(true);
            }
         });
      });
   }

   private static void take(Minecraft mc, State state, List<PackReloadConfig.IdAndPath> packs, Consumer<Boolean> then) {
      Tab tab = state.tab;
      List<UUID> ids = new ArrayList<>(packs.size());
      List<Path> files = new ArrayList<>(packs.size());
      for (PackReloadConfig.IdAndPath pack : packs) {
         ids.add(pack.id());
         files.add(pack.path());
      }

      Assets.Look look = Assets.obtain(mc, ids, files);
      tab.nextLook = look;
      Assets.whenMade(look, made -> {
         if (tab.nextLook == look) {
            tab.nextLook = null;
         }

         if (made != null) {
            state.loaded = packs;
            tab.look = made == Assets.BASE ? null : made;
            if (tab == Tabs.active()) {
               Assets.show(mc, Assets.of(tab));
            }
         }

         then.accept(made != null);
      });
   }

   /** The game loaded its own resources anew and every set made from the old ones is gone: the tabs get theirs made again. */
   static void again(Minecraft mc) {
      for (Tab tab : Tabs.tabs()) {
         State state = tab.packState;
         if (state == null || state.loaded.isEmpty() || tab.nextLook != null) {
            continue;
         }

         tab.look = null;
         take(mc, state, state.loaded, made -> {
         });
      }
   }

   /** For the test hook: the pack files each tab is drawn with. */
   static String describe(Tab tab) {
      State state = tab.packState;
      if (state == null || state.loaded.isEmpty()) {
         return "-";
      }

      StringBuilder out = new StringBuilder();
      for (PackReloadConfig.IdAndPath pack : state.loaded) {
         String name = pack.path().getFileName().toString();
         out.append(out.length() == 0 ? "" : "+").append(name, 0, Math.min(8, name.length()));
      }

      Assets.Look look = tab.look;
      return out + (look == null ? "(none)" : look.ready ? "(set " + look.no + ")" : "(making)");
   }
}
