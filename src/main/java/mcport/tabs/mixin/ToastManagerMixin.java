package mcport.tabs.mixin;

import mcport.tabs.Tabs;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Toasts are one shared stack: no advancement or recipe pop-ups from a server that is not on screen. */
@Mixin(value = ToastManager.class, priority = 2000)
public abstract class ToastManagerMixin {
   @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
   private void handykit$add(Toast toast, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         ci.cancel();
      }
   }

   @Inject(method = "clear", at = @At("HEAD"), cancellable = true)
   private void handykit$clear(CallbackInfo ci) {
      if (Tabs.inBackground()) {
         ci.cancel();
      }
   }
}
