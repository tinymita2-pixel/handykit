package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The sky renderer remembers the target it was made for and always draws there. A tab's renderer draws both on
 * screen and into a small view, so the sky goes to whatever the game is drawing into at that moment.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
   @ModifyExpressionValue(
      method = "render",
      at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")
   )
   private RenderTarget handykit$currentTarget(RenderTarget made) {
      return Minecraft.getInstance().gameRenderer.mainRenderTarget();
   }
}
