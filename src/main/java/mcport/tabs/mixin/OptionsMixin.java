package mcport.tabs.mixin;

import mcport.tabs.Tabs;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The view distance of the server caps the render distance; a background server must not cap the one on screen. */
@Mixin(value = Options.class, priority = 2000)
public abstract class OptionsMixin {
   @Inject(method = "setServerRenderDistance", at = @At("HEAD"), cancellable = true)
   private void handykit$serverRenderDistance(int distance, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         Tabs.setBackgroundRenderDistance(distance);
         ci.cancel();
      }
   }
}
