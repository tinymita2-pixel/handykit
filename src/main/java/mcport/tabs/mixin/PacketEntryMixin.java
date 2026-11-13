package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcport.tabs.Tabs;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Each queued packet is handled with the world of its own connection in place. */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public abstract class PacketEntryMixin {
   @Shadow
   @Final
   private PacketListener listener;

   @Shadow
   @Final
   private net.minecraft.network.protocol.Packet<?> packet;

   @WrapMethod(method = "handle")
   private void handykit$inOwnWorld(Operation<Void> original) {
      if (Tabs.idle()) {
         original.call();
      } else {
         Tabs.handlePacket(this.listener, this.packet, () -> original.call());
      }
   }
}
