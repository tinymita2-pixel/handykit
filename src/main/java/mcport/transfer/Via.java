package mcport.transfer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;

/**
 * ViaFabricPlus keeps one "current server version" for the whole client and changes how the game behaves by it.
 * With several servers open that version has to follow the tab on screen, and a new connection has to start from
 * the version the player picked in ViaFabricPlus, not from the one the tab last on screen happens to speak.
 * Nothing here loads a ViaFabricPlus class unless the mod is present, and when ViaFabricPlus turns out not to be
 * in the shape this was written for (an update moved things) it is left alone from then on.
 */
public final class Via {
   static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("viafabricplus");
   private static volatile boolean broken;

   private Via() {
   }

   /** ViaFabricPlus is installed and has not let this class down yet. */
   static boolean usable() {
      return PRESENT && !broken;
   }

   /** A call into ViaFabricPlus failed in a way that will fail again: say so once, then keep away from it. */
   static void broke(String what, Throwable t) {
      if (!broken) {
         broken = true;
         TransferTargets.LOGGER.warn("{}; the ViaFabricPlus version is left alone from here on", what, t);
      }
   }

   /** The tab on screen changed: make the client behave for that server version. */
   public static void follow(@Nullable Connection connection) {
      if (usable() && connection != null) {
         try {
            String version = ViaBridge.follow(connection);
            if (version != null) {
               TransferTargets.LOGGER.info("ViaFabricPlus version follows the tab: {}", version);
            }
         } catch (Throwable t) {
            broke("Could not switch the ViaFabricPlus version", t);
         }
      }
   }

   /**
    * Every tick. With a world on screen the version is that connection's (ViaFabricPlus reverts it whenever a
    * connection closes, also one behind the screen). On the title screen and the server list, where nothing is on
    * screen or on its way there, it is the player's own pick again: that is what the ViaFabricPlus button shows
    * and what the next connection starts from.
    */
   public static void keep(Minecraft mc) {
      if (!usable()) {
         return;
      }

      ClientPacketListener listener = mc.getConnection();
      if (listener != null) {
         follow(listener.getConnection());
         return;
      }

      Screen screen = mc.gui.screen();
      if (screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen) {
         rest("nothing is on screen");
      }
   }

   /**
    * Start of the connect screen's connector thread, before ViaFabricPlus decides what the connection speaks: it
    * reads the current version there, which with tabs is the version of whatever tab was on screen last. A server
    * on another version then answers "Outdated client". The version goes back to the player's pick first; and when
    * that pick is one fixed version, the server is asked whether it takes it, and a server that speaks another
    * version gets that one for this connection (see ViaBridge.joinAs). This may wait for the server's answer, up
    * to the status query's limit; the thread is the connector thread.
    */
   public static void beforeConnect(@Nullable ServerData server, @Nullable ServerAddress address) {
      if (!usable()) {
         return;
      }

      String joinAs = null;
      try {
         ViaBridge.learnPick();
         // with a version forced on the entry (by the player, or found for a transfer target) ViaFabricPlus does
         // not look at the current one, and changing it twice in a row only costs time
         if (server != null && !ViaBridge.readsCurrent(server)) {
            return;
         }

         if (address != null) {
            joinAs = ViaBridge.joinAs(address);
         }
      } catch (Throwable t) {
         broke("Could not prepare the ViaFabricPlus version for a connection", t);
         return;
      }

      if (joinAs == null) {
         rest("a new connection starts");
      }
   }

   /**
    * Optional, before a connect screen connection to an address an open tab is on already (the same server again
    * as another account): that connection's version is what the server takes, so the server is not asked.
    */
   public static void alreadyOn(ServerAddress address, @Nullable Connection connection) {
      if (usable() && connection != null) {
         try {
            ViaBridge.alreadyOn(address, connection);
         } catch (Throwable t) {
            broke("Could not read the version of an open connection", t);
         }
      }
   }

   /** The connect screen has opened its connection (ConnectScreenThreadMixin): the one that is logging in on screen. */
   public static void joining(io.netty.channel.ChannelFuture opened) {
      if (usable()) {
         try {
            ViaBridge.joining(opened.channel());
         } catch (Throwable t) {
            broke("Could not note the connection being made", t);
         }
      }
   }

   private static void rest(String why) {
      try {
         String version = ViaBridge.rest();
         if (version != null) {
            TransferTargets.LOGGER.info("ViaFabricPlus version is the player's pick again ({}): {}", why, version);
         }
      } catch (Throwable t) {
         broke("Could not put the ViaFabricPlus version back", t);
      }
   }

   /**
    * Before a connection opens behind the one on screen: it speaks the version {@link TransferTargets#detect}
    * found for the server, or else the one the tab spoke until now.
    */
   public static void prepare(Connection connection, ServerData data, @Nullable Connection previous) {
      if (usable()) {
         try {
            TransferTargets.LOGGER.info("Background connection to {} joins as {}", data.ip, ViaBridge.prepare(connection, data, previous));
         } catch (Throwable t) {
            broke("Could not pick a version for " + data.ip, t);
         }
      }
   }

   /**
    * ViaFabricPlus is about to change its version (ViaTargetVersionMixin). Returns the version it may change to,
    * or null when the change must not happen. The types are Object so that this class never names a ViaFabricPlus
    * type.
    */
   public static @Nullable Object guardTargetVersion(@Nullable Object version, boolean revertOnDisconnect) {
      if (!usable()) {
         return version;
      }

      try {
         return ViaBridge.onSetTargetVersion(version, revertOnDisconnect);
      } catch (Throwable t) {
         broke("Could not follow a ViaFabricPlus version change", t);
         return version;
      }
   }

   /** The version ViaFabricPlus should write to its settings file: the player's pick when it is known (ViaSettingsMixin). */
   public static @Nullable Object pick(@Nullable Object current) {
      if (!usable()) {
         return current;
      }

      try {
         return ViaBridge.pickOr(current);
      } catch (Throwable t) {
         broke("Could not tell ViaFabricPlus which version to save", t);
         return current;
      }
   }
}
