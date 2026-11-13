package mcport.transfer;

import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation;
import com.viaversion.viafabricplus.injection.access.core.IConnection;
import com.viaversion.viafabricplus.injection.access.core.IServerData;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import io.netty.channel.Channel;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;

/**
 * Every reference to ViaFabricPlus and ViaVersion lives here, so nothing loads them unless the mod is present.
 * Callers check {@link Via#usable()} first and catch whatever comes out (a ViaFabricPlus update may move things).
 *
 * <p>ViaFabricPlus has one version for the whole client. It takes the version of a connection the connect screen
 * makes and puts back "the version before" when that connection closes, which is one remembered value: enough
 * for one server at a time. With tabs this class keeps apart what ViaFabricPlus mixes up: the version the player
 * picked ({@link #pick}), the version a connection that is logging in on screen needs ({@link #joining}), and the
 * version of the world on screen (the connection's own).
 */
final class ViaBridge {
   /** What the player picked in ViaFabricPlus (its selection screen, or the pick it loaded at startup). */
   private static volatile @Nullable ProtocolVersion pick;
   /** The version ViaFabricPlus took for a connection that is being made on screen and has not reached its world yet. */
   private static volatile @Nullable ProtocolVersion joining;
   /** The channel of that connection once the connect screen has opened it (null before, and for a singleplayer world). */
   private static volatile @Nullable Channel joiningChannel;
   /** Set on a thread while this class changes the version itself. */
   private static final ThreadLocal<Boolean> OURS = new ThreadLocal<>();
   /** transfer copy -> the version of the connection the player was sent on from */
   private static final Map<ServerData, ProtocolVersion> CAME_AS = Collections.synchronizedMap(new WeakHashMap<>());

   /**
    * The last server asked what it speaks. protocol is the version the question was asked as, or -1 when the
    * answer does not depend on that (no answer at all, or the version an open connection to it speaks).
    */
   private record Asked(String address, int protocol, @Nullable ProtocolVersion answer, long at) {
   }

   private static volatile @Nullable Asked asked;

   private ViaBridge() {
   }

   private static ProtocolTranslation via() {
      return ViaFabricPlus.api().protocolTranslation();
   }

   static boolean hasForcedVersion(ServerData server) {
      return ((IServerData)server).viaFabricPlus$forcedVersion() != null;
   }

   static void copyForcedVersion(ServerData from, ServerData to) {
      ((IServerData)to).viaFabricPlus$forceVersion(((IServerData)from).viaFabricPlus$forcedVersion());
   }

   /** Forces the version the server reported; returns its name, or null when ViaFabricPlus cannot join as it. */
   static @Nullable String force(ServerData server, int protocol, String name) {
      ProtocolVersion version = resolve(protocol, name);
      if (!joinable(version)) {
         return null;
      }

      ((IServerData)server).viaFabricPlus$forceVersion(version);
      return version.getName();
   }

   /** Forces the version this connection speaks (a transfer to the address it is on already); null when it has none to give. */
   static @Nullable String forceSameAs(ServerData server, Connection connection) {
      ProtocolVersion version = via().targetVersion(connection);
      if (!joinable(version)) {
         return null;
      }

      ((IServerData)server).viaFabricPlus$forceVersion(version);
      return version.getName();
   }

   /** A transfer target whose version still has to be asked for: keep what the connection it came from speaks. */
   static void rememberCameAs(ServerData server, Connection connection) {
      ProtocolVersion version = via().targetVersion(connection);
      if (joinable(version)) {
         CAME_AS.put(server, version);
      }
   }

   /** The target did not say what it speaks: join it as the server the player came from; null when that is not known. */
   static @Nullable String forceCameAs(ServerData server) {
      ProtocolVersion version = CAME_AS.remove(server);
      if (version == null) {
         return null;
      }

      ((IServerData)server).viaFabricPlus$forceVersion(version);
      return version.getName();
   }

   /**
    * Does ViaFabricPlus look at the client-wide version for a connection to this server? Not when a version is
    * forced on the entry (and it did not come through the direct connect screen, which has its own rule).
    */
   static boolean readsCurrent(ServerData server) {
      IServerData data = (IServerData)server;
      return data.viaFabricPlus$forcedVersion() == null || data.viaFabricPlus$passedDirectConnectScreen();
   }

   /** The version the client has to behave as for this connection, or null when ViaFabricPlus has none for it. */
   private static @Nullable ProtocolVersion needed(ProtocolTranslation via, Connection connection) {
      ProtocolVersion version = via.targetVersion(connection);
      // a singleplayer world never gets one: it speaks the game's own version
      return version == null && connection.isMemoryConnection() ? nativeVersion() : version;
   }

   /** The client behaves for the version of this connection from now on; returns its name when that changed. */
   static @Nullable String follow(Connection connection) {
      // a world is on screen: whatever was logging in has arrived
      joining = null;
      ProtocolTranslation via = via();
      ProtocolVersion wanted = needed(via, connection);
      if (wanted == null || wanted.equals(via.targetVersion())) {
         return null;
      }

      set(via, wanted);
      return wanted.getName();
   }

   /**
    * A pick that is not known yet is what the version is now. Only for places where nothing of ours can have
    * changed it: the first connection the connect screen makes in a session.
    */
   static void learnPick() {
      if (pick == null) {
         ProtocolVersion current = via().targetVersion();
         if (current != null) {
            pick = current;
         }
      }
   }

   /** Back to the player's pick; returns its name when that changed the version. */
   static @Nullable String rest() {
      joining = null;
      ProtocolTranslation via = via();
      ProtocolVersion mine = pick;
      if (mine == null || mine.equals(via.targetVersion())) {
         return null;
      }

      set(via, mine);
      return mine.getName();
   }

   private static void set(ProtocolTranslation via, ProtocolVersion version) {
      OURS.set(Boolean.TRUE);
      try {
         via.setTargetVersion(version, false);
      } finally {
         OURS.remove();
      }
   }

   /**
    * ViaFabricPlus is about to change its version. Returns the version it may change to, or null for "leave it".
    *
    * <p>Three kinds of callers: this class (passes through); the game thread without "revert" (the selection
    * screen, or the saved pick loaded at startup: that is the player's pick from now on); a connection being made
    * ("revert on disconnect": the version that connection speaks). Whatever is left is ViaFabricPlus putting back
    * the version it remembered from before a connection, now that the connection closed, from a network thread.
    * It remembers one version only: with two connections the second close hands over null, and the first puts
    * back a version while another server may be on screen or logging in. So a close changes nothing while a world
    * is on screen or a connection is on its way there, and otherwise goes back to the player's pick.
    */
   static @Nullable Object onSetTargetVersion(@Nullable Object requested, boolean revertOnDisconnect) {
      ProtocolVersion version = (ProtocolVersion)requested;
      ProtocolVersion current = via().targetVersion();
      if (pick == null && current != null) {
         // the first change seen: what was there until now is the pick (the default, or what was loaded)
         pick = current;
      }

      if (OURS.get() != null) {
         return version;
      }

      Minecraft mc = Minecraft.getInstance();
      if (revertOnDisconnect) {
         joining = version;
         joiningChannel = null;
         return version;
      }

      if (version != null && mc != null && mc.isSameThread()) {
         pick = version;
         return version;
      }

      if (inUse(mc)) {
         return null;
      }

      ProtocolVersion mine = pick;
      return mine != null ? mine : version;
   }

   /** Something on screen, or on its way there, has the version it needs: a connection closing elsewhere changes nothing. */
   private static boolean inUse(@Nullable Minecraft mc) {
      if (joining != null) {
         Channel channel = joiningChannel;
         if (channel == null || channel.isOpen()) {
            // the connection on its way to the screen is alive: it was another one that closed
            return true;
         }

         // it is the one that closed (the join failed)
         joining = null;
      }

      if (mc != null) {
         ClientPacketListener listener = mc.getConnection();
         // a world is on screen and keeps the version it speaks
         return listener != null && listener.getConnection().isConnected() && needed(via(), listener.getConnection()) != null;
      }

      return false;
   }

   /**
    * The connect screen has opened the connection that is logging in on screen. When it closes, whenever that is,
    * the version goes back to the player's pick unless something else on screen needs its own. ViaFabricPlus does
    * the same only when it changed the version for the connection, which it does not when the version was set
    * here first ({@link #joinAs}).
    */
   static void joining(Channel channel) {
      joiningChannel = channel;
      channel.closeFuture().addListener(closed -> afterClose());
   }

   private static void afterClose() {
      try {
         if (inUse(Minecraft.getInstance())) {
            return;
         }

         ProtocolTranslation via = via();
         ProtocolVersion mine = pick;
         if (mine != null && !mine.equals(via.targetVersion())) {
            set(via, mine);
         }
      } catch (Throwable t) {
         Via.broke("Could not put the ViaFabricPlus version back after a connection closed", t);
      }
   }

   /** A server did not answer the status query a moment ago (a transfer target): the join does not ask it again. */
   static void noAnswer(ServerAddress address) {
      asked = new Asked(address.toString(), -1, null, System.currentTimeMillis());
   }

   /** An open connection speaks to this address already: what it speaks is what the server takes, no need to ask. */
   static void alreadyOn(ServerAddress address, Connection connection) {
      ProtocolVersion version = via().targetVersion(connection);
      if (joinable(version)) {
         asked = new Asked(address.toString(), -1, version, System.currentTimeMillis());
      }
   }

   /**
    * The pick is about to be used for a connection the connect screen makes (nothing is forced on the entry). When
    * the pick is one fixed version, the server is asked first, as that version. A server that takes it answers
    * with the same protocol (servers for several versions do) and gets the pick. A server that answers with
    * another version ViaFabricPlus can speak would turn the pick away with "Outdated client", so this one
    * connection speaks the server's version; the pick, and what ViaFabricPlus saves, stay as they are. No answer in
    * time: the pick. Returns the name of the version the connection joins as instead of the pick, or null.
    */
   static @Nullable String joinAs(ServerAddress address) {
      ProtocolVersion mine = pick;
      // auto detect asks by itself; versions from before the status query, and special ones, cannot be asked this way
      if (mine == null || !mine.isKnown() || mine.getVersionType() != VersionType.RELEASE) {
         return null;
      }

      ProtocolVersion theirs = ask(address, mine);
      if (theirs == null || theirs.equals(mine)) {
         return null;
      }

      // from here until the connection is open nothing else may put the pick back (a tab closing behind the screen)
      joining = theirs;
      joiningChannel = null;
      ProtocolTranslation via = via();
      if (!theirs.equals(via.targetVersion())) {
         set(via, theirs);
      }

      TransferTargets.LOGGER.info(
         "{} speaks {} and would turn away the ViaFabricPlus pick {}: this connection joins as {} (the pick stays)", address, theirs.getName(), mine.getName(), theirs.getName()
      );
      return theirs.getName();
   }

   /** What the server speaks when asked as the pick, if ViaFabricPlus can join as that; null when it did not say. */
   private static @Nullable ProtocolVersion ask(ServerAddress address, ProtocolVersion mine) {
      String key = address.toString();
      int protocol = mine.getOriginalVersion();
      long now = System.currentTimeMillis();
      Asked last = asked;
      if (last != null && last.address.equals(key) && now - last.at < 30_000L && (last.protocol == -1 || last.protocol == protocol)) {
         // asked a moment ago (a join tried again, a transfer target that stayed silent, a server another tab is on)
         return last.answer;
      }

      ProtocolVersion answer = null;
      try {
         StatusPing.Result status = StatusPing.query(address, protocol);
         ProtocolVersion version = resolve(status.protocol(), status.name());
         if (joinable(version)) {
            answer = version;
         }
      } catch (Exception e) {
         TransferTargets.LOGGER.info("Could not ask {} what it speaks ({}); joining as the ViaFabricPlus pick {}", address, e.toString(), mine.getName());
      }

      asked = new Asked(key, protocol, answer, now);
      return answer;
   }

   /** The player's pick when it is known, else what was handed in. */
   static @Nullable Object pickOr(@Nullable Object current) {
      ProtocolVersion mine = pick;
      return mine != null ? mine : current;
   }

   /**
    * A connection that is opened next to the one on screen. ViaFabricPlus uses the version set on the connection
    * itself when there is one before connect(): the one forced on the entry, else the one the tab spoke so far.
    */
   static String prepare(Connection connection, ServerData data, @Nullable Connection previous) {
      ProtocolVersion version = ((IServerData)data).viaFabricPlus$forcedVersion();
      if (!joinable(version) && previous != null) {
         version = via().targetVersion(previous);
      }

      if (!joinable(version)) {
         version = nativeVersion();
      }

      ((IConnection)connection).viaFabricPlus$setTargetVersion(version);
      return version.getName();
   }

   private static ProtocolVersion nativeVersion() {
      return ProtocolVersion.getProtocol(SharedConstants.getProtocolVersion());
   }

   private static boolean joinable(@Nullable ProtocolVersion version) {
      return version != null && version.isKnown() && !version.newerThan(nativeVersion());
   }

   // same matching ViaFabricPlus uses for its own auto detection
   private static @Nullable ProtocolVersion resolve(int protocol, String name) {
      if (ProtocolVersion.isRegistered(protocol)) {
         return ProtocolVersion.getProtocol(protocol);
      }

      for (ProtocolVersion version : ProtocolVersion.getReversedProtocols()) {
         for (String included : version.getIncludedVersions()) {
            if (name.contains(included)) {
               return version;
            }
         }
      }

      return null;
   }
}
