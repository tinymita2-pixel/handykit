package mcport.tabs.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where the drawing calls of a frame are collected: a picture drawn without blending is added there directly. */
@Mixin(GuiGraphicsExtractor.class)
public interface GraphicsAccess {
   @Accessor("guiRenderState")
   GuiRenderState handykit$state();
}
