package mcport.legacypackfix.mixin;

import java.util.List;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Before 26.x item and block textures shared one atlas, so 1.21.x item models freely mix
 * textures/item and textures/block. Every quad carries its own render type, so a model that only
 * mixes those two atlases still draws correctly; let it bake instead of rejecting it.
 */
@Mixin(CuboidItemModelWrapper.class)
public abstract class CuboidItemModelWrapperMixin {
   @Inject(method = "validateAtlasUsage", at = @At("HEAD"), cancellable = true)
   private static void legacypackfix$allowItemAndBlockAtlases(List<BakedQuad> quads, CallbackInfo ci) {
      for (BakedQuad quad : quads) {
         Identifier atlas = quad.materialInfo().sprite().atlasLocation();
         if (!atlas.equals(TextureAtlas.LOCATION_ITEMS) && !atlas.equals(TextureAtlas.LOCATION_BLOCKS)) {
            return;
         }
      }

      ci.cancel();
   }
}
