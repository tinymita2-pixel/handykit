package mcport.tabs.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientCommonPacketListenerImpl.class)
public interface CommonListenerAccess {
   @Accessor("connection")
   Connection handykit$connection();

   @Accessor("isTransferring")
   boolean handykit$isTransferring();
}
