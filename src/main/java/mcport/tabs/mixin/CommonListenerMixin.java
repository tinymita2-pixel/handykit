package mcport.tabs.mixin;

import mcport.tabs.Tabs;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientCommonPacketListenerImpl.class, priority = 2000)
public abstract class CommonListenerMixin {
   @Shadow
   @Final
   protected Connection connection;
   @Shadow
   @Final
   protected @Nullable ServerData serverData;
   @Shadow
   protected boolean isTransferring;

   /** A background connection ended: close the world of that tab, not the one on screen. */
   @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
   private void handykit$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         Tabs.onBackgroundDisconnect(details, this.isTransferring);
         ci.cancel();
      } else if (!this.isTransferring) {
         // the server (or the network) ended it, not the player leaving
         Tabs.onForegroundLost(details, this.connection);
      }
   }

   /** After the hop to the client thread: remember the pack for this tab, and answer for it when in the background. */
   @Inject(
      method = "handleResourcePackPush",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;id()Ljava/util/UUID;"),
      cancellable = true
   )
   private void handykit$packPush(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
      if (Tabs.onPackPush(this.connection, this.serverData, packet)) {
         ci.cancel();
      }
   }

   @Inject(
      method = "handleResourcePackPop",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/ClientboundResourcePackPopPacket;id()Ljava/util/Optional;"),
      cancellable = true
   )
   private void handykit$packPop(ClientboundResourcePackPopPacket packet, CallbackInfo ci) {
      if (Tabs.onPackPop(packet)) {
         ci.cancel();
      }
   }
}
