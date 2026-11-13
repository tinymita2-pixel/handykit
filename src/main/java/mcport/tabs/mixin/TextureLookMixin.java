package mcport.tabs.mixin;

import mcport.tabs.Assets;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A texture a tab's server pack holds is looked up in that tab's resource set, not among the game's own. */
@Mixin(TextureManager.class)
public abstract class TextureLookMixin {
   @Inject(method = "getTexture", at = @At("HEAD"), cancellable = true)
   private void handykit$ofLook(Identifier location, CallbackInfoReturnable<AbstractTexture> cir) {
      AbstractTexture own = Assets.texture(location);
      if (own != null) {
         cir.setReturnValue(own);
      }
   }
}
