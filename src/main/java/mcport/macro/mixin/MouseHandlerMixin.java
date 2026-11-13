package mcport.macro.mixin;

import mcport.macro.MacroClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Once per frame, right where the mouse turns the player: smooth look between the 20 ticks of a playback. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
   @Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
   private void handykit$playbackLook(CallbackInfo ci) {
      MacroClient.onFrame();
   }
}
