package mcport.legacypackfix.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.x throws when a model face's UVs reach past its texture, so the whole item model fails to
 * bake. 1.21.x tolerated those packs; measure only the part of the region inside the image.
 */
@Mixin(NativeImage.class)
public abstract class NativeImageMixin {
   @Shadow
   public abstract int getWidth();

   @Shadow
   public abstract int getHeight();

   @Shadow
   public abstract Transparency computeTransparency(int x0, int y0, int x1, int y1);

   @Inject(method = "computeTransparency(IIII)Lcom/mojang/blaze3d/platform/Transparency;", at = @At("HEAD"), cancellable = true)
   private void legacypackfix$clampRegion(int x0, int y0, int x1, int y1, CallbackInfoReturnable<Transparency> cir) {
      int width = this.getWidth();
      int height = this.getHeight();
      if (x0 >= 0 && y0 >= 0 && x1 <= width && y1 <= height) {
         return;
      }

      int cx0 = Math.clamp(x0, 0, width);
      int cy0 = Math.clamp(y0, 0, height);
      int cx1 = Math.clamp(x1, 0, width);
      int cy1 = Math.clamp(y1, 0, height);
      cir.setReturnValue(cx0 < cx1 && cy0 < cy1 ? this.computeTransparency(cx0, cy0, cx1, cy1) : Transparency.NONE);
   }
}
