package mcport.transfer.mixin;

import mcport.transfer.TransferTargets;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla only shows login-phase kicks ("Outdated server" and the like) on screen; keep them in the log too. */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerImplMixin {
   @Inject(method = "onDisconnect", at = @At("HEAD"))
   private void handykit$logLoginKick(DisconnectionDetails details, CallbackInfo ci) {
      TransferTargets.LOGGER.warn("Disconnected while logging in: {}", details.reason().getString());
   }
}
