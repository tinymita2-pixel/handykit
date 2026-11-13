package mcport.tabs.mixin;

import mcport.tabs.Tabs;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A background world may not put a screen over the one the player is looking at. */
@Mixin(value = Gui.class, priority = 2000)
public abstract class GuiMixin {
   @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
   private void handykit$setScreen(@Nullable Screen screen, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         Tabs.onBackgroundScreen(screen);
         ci.cancel();
      }
   }
}
