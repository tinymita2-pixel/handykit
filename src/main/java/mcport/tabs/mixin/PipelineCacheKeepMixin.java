package mcport.tabs.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import mcport.tabs.Assets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The compiled pipelines that are replaced while one tab's shaders are made are another tab's: kept, not closed. */
@Mixin(PipelineCache.class)
public abstract class PipelineCacheKeepMixin {
   @Inject(method = "close", at = @At("HEAD"), cancellable = true)
   private void handykit$keep(CallbackInfo ci) {
      if (Assets.keeping()) {
         ci.cancel();
      }
   }
}
