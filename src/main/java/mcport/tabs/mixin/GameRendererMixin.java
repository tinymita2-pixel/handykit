package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import mcport.tabs.Pip;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The small view of a tab behind the screen: the game's own "extract, then draw the level" for the world that is in
 * Minecraft's fields at that moment, with another camera and into another target: the world and that player's hand.
 * No post effects and no GUI.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin implements Pip.Host {
   @Shadow
   @Final
   private Minecraft minecraft;
   @Shadow
   @Final
   private GameRenderState gameRenderState;
   @Shadow
   @Final
   @Mutable
   private Camera mainCamera;
   @Shadow
   @Final
   @Mutable
   private RenderTarget mainRenderTarget;
   @Shadow
   @Final
   @Mutable
   private Lightmap lightmap;
   @Shadow
   @Final
   private LightmapRenderStateExtractor lightmapRenderStateExtractor;
   @Shadow
   @Final
   private GlobalSettingsUniform globalSettingsUniform;
   @Shadow
   @Final
   private FogRenderer fogRenderer;
   @Shadow
   @Final
   private CrossFrameResourcePool resourcePool;
   @Shadow
   @Final
   private RenderBuffers renderBuffers;

   @Shadow
   private void extractWindow() {
   }

   @Shadow
   private void extractOptions() {
   }

   @Shadow
   private void extractCamera(DeltaTracker deltaTracker, float worldPartialTicks) {
   }

   @Shadow
   public abstract void renderLevel();

   @Override
   public void handykit$drawWorld(
      Camera camera, RenderTarget target, Lightmap light, LightmapRenderStateExtractor lightExtractor, LightmapRenderState lightState, DeltaTracker deltaTracker
   ) {
      Camera screenCamera = this.mainCamera;
      RenderTarget screenTarget = this.mainRenderTarget;
      Lightmap screenLight = this.lightmap;
      this.mainCamera = camera;
      this.mainRenderTarget = target;
      // The light map is redrawn only when its extractor says something changed, once a tick. Sharing the one of
      // the screen would use that up, and the screen would be lit with this world's light: each view has its own.
      this.lightmap = light;
      try {
         camera.update(deltaTracker);
         if (!camera.isInitialized()) {
            return;
         }

         float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
         GameRenderState state = this.gameRenderState;
         state.shouldRenderLevel = true;
         this.extractWindow();
         this.extractOptions();
         WindowRenderState window = state.windowRenderState;
         window.width = target.width;
         window.height = target.height;
         lightExtractor.extract(lightState, 1.0F);
         this.extractCamera(deltaTracker, partialTicks);
         this.minecraft.levelExtractor.extract(deltaTracker, camera, partialTicks);
         RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 1.0F), target.getDepthTexture(), 0.0);
         OptionsRenderState options = state.optionsRenderState;
         this.globalSettingsUniform
            .update(
               window.width,
               window.height,
               options.glintStrength,
               state.levelRenderState.gameTime,
               state.levelRenderState.worldPartialTicks,
               options.menuBackgroundBlurriness,
               state.levelRenderState.cameraRenderState.pos,
               options.textureFiltering == TextureFilteringMethod.RGSS
            );
         light.render(lightState);
         this.renderLevel();
         this.minecraft.levelRenderer.blitEntityOutline();
         this.fogRenderer.endFrame();
         this.renderBuffers.endFrame();
         this.resourcePool.endFrame();
         this.minecraft.levelRenderer.endFrame();
      } finally {
         this.mainCamera = screenCamera;
         this.mainRenderTarget = screenTarget;
         this.lightmap = screenLight;
      }
   }

   /**
    * With post effects on screen the hand is drawn against a depth target of its own, which is as large as the
    * window; a small view draws into a small target and takes the plain path.
    */
   @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
   private boolean handykit$plainDepthInSmallView(boolean empty) {
      return empty || Pip.drawing();
   }
}
