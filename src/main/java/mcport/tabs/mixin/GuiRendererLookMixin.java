package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mcport.tabs.Assets;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The GUI draws its item icons later than it collects them, into a sheet of its own. An icon of another tab (the
 * hotbar of a small view) is drawn there with that tab's models, textures and shaders in place.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererLookMixin {
   @WrapOperation(
      method = "lambda$prepareItemElements$0",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/render/GuiItemAtlas;getOrUpdate(Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;)Lnet/minecraft/client/gui/render/GuiItemAtlas$SlotView;"
      )
   )
   private GuiItemAtlas.SlotView handykit$itemOfLook(GuiItemAtlas atlas, TrackingItemStackRenderState item, Operation<GuiItemAtlas.SlotView> original) {
      return Assets.itemDuring(item, () -> original.call(atlas, item));
   }
}
