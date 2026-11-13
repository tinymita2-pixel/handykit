package mcport.transfer.mixin;

import mcport.transfer.TransferTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
   @Shadow
   @Final
   protected Connection connection;

   @ModifyArg(
      method = "handleTransfer",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/ConnectScreen;startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V"
      ),
      index = 3
   )
   private ServerData handykit$serverDataPerTarget(
      Screen parent, Minecraft minecraft, ServerAddress address, ServerData server, boolean quickPlay, TransferState transferState
   ) {
      return TransferTargets.forTransfer(server, address, this.connection);
   }
}
