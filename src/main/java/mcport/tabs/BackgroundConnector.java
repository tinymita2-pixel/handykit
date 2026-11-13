package mcport.tabs;

import io.netty.channel.ChannelFuture;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import mcport.transfer.TransferTargets;
import mcport.transfer.Via;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.jspecify.annotations.Nullable;

/**
 * The connector thread of ConnectScreen without the screen: a background tab the server transferred logs in to
 * the new address by itself. From the login on, the game's own listeners run; their packets are handled in the
 * tab's context like any other.
 */
final class BackgroundConnector {
   private static final AtomicInteger THREADS = new AtomicInteger();

   private BackgroundConnector() {
   }

   static void start(Tab tab, ServerAddress address, ServerData data, @Nullable TransferState transfer) {
      Minecraft mc = Minecraft.getInstance();
      tab.transferring = true;
      tab.connecting = true;
      tab.address = address.toString();
      Connection previous = tab.connection;
      Thread thread = new Thread(() -> {
         try {
            Tabs.onConnectorThread(tab);
            Optional<InetSocketAddress> resolved = ServerNameResolver.DEFAULT.resolveAddress(address).map(ResolvedServerAddress::asInetSocketAddress);
            if (resolved.isEmpty()) {
               fail(mc, tab, ConnectScreen.UNKNOWN_HOST_MESSAGE);
               return;
            }

            InetSocketAddress inet = resolved.get();
            // same detection a transfer on screen gets from the connect screen thread
            TransferTargets.detect(data, address);
            Connection connection = new Connection(PacketFlow.CLIENTBOUND);
            Via.prepare(connection, data, previous);
            ChannelFuture future = Connection.connect(inet, EventLoopGroupHolder.remote(mc.options.useNativeTransport()), connection);
            future.syncUninterruptibly();
            Tabs.BACKGROUND_CONNECT.set(tab);
            Packs.controlBehind(tab, connection, data);
            try {
               connection.initiateServerboundPlayConnection(
                  inet.getHostName(),
                  inet.getPort(),
                  LoginProtocols.SERVERBOUND,
                  LoginProtocols.CLIENTBOUND,
                  new ClientHandshakePacketListenerImpl(connection, mc, data, null, false, null, status -> {}, new LevelLoadTracker(), transfer),
                  transfer != null
               );
            } finally {
               Tabs.BACKGROUND_CONNECT.remove();
            }

            connection.send(new ServerboundHelloPacket(mc.getUser().getName(), mc.getUser().getProfileId()));
         } catch (Exception e) {
            Throwable cause = e.getCause() instanceof Exception inner ? inner : e;
            Tabs.LOGGER.warn("Tab {} could not connect to {}", tab.title(), address, cause);
            fail(mc, tab, Component.translatable("disconnect.genericReason", cause.getMessage() == null ? cause.toString() : cause.getMessage()));
         }
      }, "HandyKit Tab Connector #" + THREADS.incrementAndGet());
      thread.setDaemon(true);
      thread.start();
   }

   private static void fail(Minecraft mc, Tab tab, Component reason) {
      mc.execute(() -> {
         tab.transferring = false;
         tab.connecting = false;
         Tabs.kill(tab, reason);
      });
   }
}
