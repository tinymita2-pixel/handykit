package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import java.util.function.Consumer;
import mcport.tabs.Tabs;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public abstract class PlayListenerMixin {
   /** Tags resent during play: keep them for the tab; in the background bind only the registries the tab owns. */
   @WrapOperation(method = "handleUpdateTags", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
   private void handykit$tags(List<Registry.PendingTags<?>> pending, Consumer<?> apply, Operation<Void> original, ClientboundUpdateTagsPacket packet) {
      Tabs.recordTags(packet.tags());
      if (Tabs.inBackground()) {
         for (Registry.PendingTags<?> tags : pending) {
            if (RegistrySynchronization.isNetworkable(tags.key())) {
               tags.apply();
            }
         }
      } else {
         original.call(pending, apply);
      }
   }
}
