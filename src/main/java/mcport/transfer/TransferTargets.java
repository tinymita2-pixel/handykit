package mcport.transfer;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import mcport.transfer.mixin.ServerDataAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vanilla reconnects a transfer with the ServerData of the server that sent it, so ViaFabricPlus joins every
 * transfer target with the version picked for the first server. Each target gets its own copy instead, and the
 * version that target supports is read on the connector thread before ViaFabricPlus looks at the copy.
 */
public final class TransferTargets {
   public static final Logger LOGGER = LoggerFactory.getLogger("handykit/transfer");
   /** transfer copy -> the server list entry the chain of transfers started from */
   private static final Map<ServerData, ServerData> ORIGINS = Collections.synchronizedMap(new WeakHashMap<>());
   /** copies whose version still has to be detected */
   private static final Map<ServerData, Boolean> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

   private TransferTargets() {
   }

   /** Render thread, while handling the transfer packet; current is the connection the packet came in on. */
   public static ServerData forTransfer(ServerData origin, ServerAddress target, @Nullable Connection current) {
      mcport.tabs.Tabs.trace("forTransfer " + target + " via=" + Via.usable());
      if (!Via.usable()) {
         return origin;
      }

      try {
         ServerData entry = ORIGINS.getOrDefault(origin, origin);
         ServerData copy = new ServerData(origin.name, target.toString(), origin.type());
         // name, icon, pack prompt answer; ViaFabricPlus also copies its forced version along with the name
         copy.copyFrom(origin);
         copy.ip = target.toString();
         ((ServerDataAccessor)copy).handykit$setAcceptedCodeOfConduct(((ServerDataAccessor)origin).handykit$getAcceptedCodeOfConduct());
         // a version detected for an earlier hop must not stick; only a version the player forced on the entry does
         ViaBridge.copyForcedVersion(entry, copy);
         ORIGINS.put(copy, entry);
         if (ViaBridge.hasForcedVersion(copy)) {
            LOGGER.info("Transfer to {}: using the version forced for {}", target, entry.ip);
            return copy;
         }

         if (current != null && sameServer(origin.ip, target)) {
            // sent on to the address it is on already: the same server answers, in the version it speaks now
            String version = ViaBridge.forceSameAs(copy, current);
            if (version != null) {
               LOGGER.info("Transfer to {}: the address it is on already, joining as {} again", target, version);
               return copy;
            }
         }

         if (current != null) {
            // what to join as when the target does not say what it speaks
            ViaBridge.rememberCameAs(copy, current);
         }

         PENDING.put(copy, Boolean.TRUE);
         return copy;
      } catch (Throwable t) {
         // ViaFabricPlus is not in the shape this was written for: vanilla's way (the sender's entry) still connects
         Via.broke("Could not prepare the version for a transfer to " + target, t);
         return origin;
      }
   }

   private static boolean sameServer(String from, ServerAddress target) {
      try {
         ServerAddress address = ServerAddress.parseString(from);
         return address.getPort() == target.getPort() && address.getHost().equalsIgnoreCase(target.getHost());
      } catch (RuntimeException e) {
         return false;
      }
   }

   /** Connector thread, before ViaFabricPlus picks the protocol for the connection. */
   public static void detect(@Nullable ServerData server, ServerAddress target) {
      if (server == null || PENDING.remove(server) == null) {
         return;
      }

      try {
         StatusPing.Result status;
         try {
            status = StatusPing.query(target, SharedConstants.getProtocolVersion());
         } catch (Exception e) {
            // the join that follows does not ask the silent server a second time
            ViaBridge.noAnswer(target);
            String kept = ViaBridge.forceCameAs(server);
            if (kept != null) {
               LOGGER.warn("Transfer to {}: could not read the server version ({}), joining as {} like the server before it", target, e.toString(), kept);
            } else {
               LOGGER.warn("Transfer to {}: could not read the server version ({}), using the ViaFabricPlus setting", target, e.toString());
            }

            return;
         }

         String version = ViaBridge.force(server, status.protocol(), status.name());
         if (version == null) {
            LOGGER.warn("Transfer to {}: server reports '{}' (protocol {}), which ViaFabricPlus cannot join as; using its setting",
               target, status.name(), status.protocol());
         } else {
            LOGGER.info("Transfer to {}: server reports '{}' (protocol {}), joining as {}", target, status.name(), status.protocol(), version);
         }
      } catch (Throwable t) {
         // a ViaFabricPlus class that is not what it was: the connection goes on with ViaFabricPlus's own choice
         Via.broke("Could not set the version for a transfer to " + target, t);
      }
   }

   /** Answers given on a transfer target (pack prompt, code of conduct) belong to the list entry, as in vanilla. */
   public static ServerData listEntryFor(ServerData server) {
      ServerData entry = ORIGINS.get(server);
      if (entry == null) {
         return server;
      }

      entry.setResourcePackStatus(server.getResourcePackStatus());
      ((ServerDataAccessor)entry).handykit$setAcceptedCodeOfConduct(((ServerDataAccessor)server).handykit$getAcceptedCodeOfConduct());
      return entry;
   }
}
