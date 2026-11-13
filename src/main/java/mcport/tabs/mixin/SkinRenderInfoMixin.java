package mcport.tabs.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game keeps the texture of a player head drawn in text (chat heads, player sprites) for five minutes, but a
 * resource reload replaces that texture; drawing the head again within those minutes crashes with "Texture view
 * has been closed". Switching between server tabs with different resource packs reloads often, so the stale
 * texture is dropped here and looked up again.
 */
@Mixin(targets = "net.minecraft.client.renderer.PlayerSkinRenderCache$RenderInfo")
public abstract class SkinRenderInfoMixin {
   @Shadow
   private @Nullable GpuTextureView textureView;

   @Inject(method = "textureView", at = @At("HEAD"))
   private void handykit$dropClosedTexture(CallbackInfoReturnable<GpuTextureView> cir) {
      GpuTextureView view = this.textureView;
      if (view != null && view.isClosed()) {
         this.textureView = null;
      }
   }
}
