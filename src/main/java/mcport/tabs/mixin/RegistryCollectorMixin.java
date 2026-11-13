package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import mcport.tabs.Tabs;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Finishing configuration binds the tags of the server and the data-driven item components onto the built-in
 * registries, which every world shares. A background tab keeps its own until it is on screen (Tabs.applyShared).
 */
@Mixin(RegistryDataCollector.class)
public abstract class RegistryCollectorMixin {
   @Inject(method = "appendTags", at = @At("HEAD"))
   private void handykit$recordTags(Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> data, CallbackInfo ci) {
      Tabs.recordTags(data);
   }

   @WrapWithCondition(method = "loadNewElementsAndTags", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
   private boolean handykit$bindStaticTags(List<?> pending, Consumer<?> apply) {
      return !Tabs.inBackground();
   }

   @ModifyArg(
      method = "collectGameRegistries",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/multiplayer/RegistryDataCollector;loadOnlyTags(Lnet/minecraft/client/multiplayer/RegistryDataCollector$TagCollector;Lnet/minecraft/core/RegistryAccess$Frozen;Z)V"
      ),
      index = 2
   )
   private boolean handykit$sharedTags(boolean includeShared) {
      return includeShared && !Tabs.inBackground();
   }

   @ModifyArg(
      method = "collectGameRegistries",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/multiplayer/RegistryDataCollector;updateComponents(Lnet/minecraft/core/RegistryAccess$Frozen;Z)V"
      ),
      index = 1
   )
   private boolean handykit$sharedComponents(boolean includeShared) {
      return includeShared && !Tabs.inBackground();
   }
}
