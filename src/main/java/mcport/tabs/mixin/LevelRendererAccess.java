package mcport.tabs.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The target glowing outlines are drawn to is as large as the window; a small view lends the renderer a small one. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccess {
   @Accessor("entityOutlineTarget")
   RenderTarget handykit$outlineTarget();

   @Mutable
   @Accessor("entityOutlineTarget")
   void handykit$setOutlineTarget(RenderTarget target);
}
