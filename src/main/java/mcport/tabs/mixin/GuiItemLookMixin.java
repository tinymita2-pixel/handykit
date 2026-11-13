package mcport.tabs.mixin;

import mcport.tabs.Assets;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An item put on the GUI while another tab's resource set is in place (the HUD of a small view) is that tab's item. */
@Mixin(GuiItemRenderState.class)
public abstract class GuiItemLookMixin {
   @Shadow
   @Final
   private TrackingItemStackRenderState itemStackRenderState;

   @Inject(method = "<init>", at = @At("RETURN"))
   private void handykit$tag(CallbackInfo ci) {
      Assets.tagItem(this.itemStackRenderState);
   }
}
