package mcport.tabs.mixin;

import mcport.tabs.Assets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The post effects that are replaced while one tab's shaders are made are another tab's: kept, not closed. */
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$PostChainCache")
public abstract class PostChainCacheMixin {
   @Inject(method = "close", at = @At("HEAD"), cancellable = true)
   private void handykit$keep(CallbackInfo ci) {
      if (Assets.keeping()) {
         ci.cancel();
      }
   }
}
