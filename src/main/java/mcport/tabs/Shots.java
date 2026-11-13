package mcport.tabs;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import mcport.tabs.mixin.GraphicsAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import org.joml.Matrix3x2f;

/**
 * The picture of a tab as it last looked on screen.
 *
 * <p>The game has one set of resources loaded, so a tab can only be drawn with its own resource packs while it is
 * the one on screen. Its last frame is kept when it leaves: that is what covers the screen while its packs are
 * loaded again on return ({@link SwitchCover}), and what its small view shows while another tab's packs are the ones
 * loaded (drawing it live would show it with those).
 */
final class Shots {
   private static boolean failed;

   private Shots() {
   }

   /** The screen as it is now becomes this tab's picture. Between two frames, with the tab still on screen. */
   static void take(Minecraft mc, Tab tab) {
      if (failed) {
         return;
      }

      try {
         RenderTarget main = mc.gameRenderer.mainRenderTarget();
         GpuTexture color = main.getColorTexture();
         if (color == null || main.width <= 0 || main.height <= 0) {
            return;
         }

         TextureTarget shot = tab.shot;
         if (shot == null || shot.width != main.width || shot.height != main.height) {
            drop(tab);
            shot = tab.shot = new TextureTarget("HandyKit tab picture", main.width, main.height, color.getFormat(), null);
         }

         shot.copyColorFrom(main);
         tab.shotAt = System.currentTimeMillis();
      } catch (RuntimeException e) {
         failed = true;
         drop(tab);
         Tabs.LOGGER.warn("The picture of a tab could not be kept ({}); tabs change without it from now on", e.toString());
      }
   }

   static void drop(Tab tab) {
      TextureTarget shot = tab.shot;
      tab.shot = null;
      if (shot != null) {
         shot.destroyBuffers();
      }
   }

   /**
    * Draws a picture that was rendered into a target (its first row is the bottom) without blending: where no sky
    * was drawn the target's alpha is 0, and a blended picture would let what is behind it through.
    */
   static void blit(GuiGraphicsExtractor g, GpuTextureView view, int x0, int y0, int x1, int y1, int color) {
      ((GraphicsAccess)g).handykit$state().addGuiElement(new BlitRenderState(
         RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND,
         TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
         new Matrix3x2f(g.pose()),
         x0,
         y0,
         x1,
         y1,
         0.0F,
         1.0F,
         1.0F,
         0.0F,
         color,
         null
      ));
   }
}
