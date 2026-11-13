package mcport.transfer.mixin;

import mcport.transfer.TransferTargets;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerList.class)
public abstract class ServerListMixin {
   @ModifyVariable(method = "saveSingleServer", at = @At("HEAD"), argsOnly = true)
   private static ServerData handykit$saveToListEntry(ServerData server) {
      return TransferTargets.listEntryFor(server);
   }
}
