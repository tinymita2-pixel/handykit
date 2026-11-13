package mcport.tabs;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import mcport.tabs.mixin.LevelRendererAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The small view: what a tab behind the screen would show, drawn for real into a small picture.
 *
 * <p>Every tab has a renderer of its own ({@link Renderers}). Once a frame, before the game draws the screen, one
 * watched tab's world is put in Minecraft's fields (as for its packets and ticks), its renderer in the renderer
 * fields, and the game's own code draws the world, hand included, with that tab's camera into a small target. The
 * watched tabs take turns, so the cost stays at one more world per frame. Nothing here runs unless a tab is being
 * watched.
 */
public final class Pip {
   /** GameRenderer, through its mixin: draws the world in Minecraft's fields with this camera into this target. */
   public interface Host {
      void handykit$drawWorld(
         Camera camera,
         RenderTarget target,
         net.minecraft.client.renderer.Lightmap light,
         net.minecraft.client.renderer.LightmapRenderStateExtractor lightExtractor,
         net.minecraft.client.renderer.state.LightmapRenderState lightState,
         DeltaTracker deltaTracker
      );
   }

   /** How many tabs can be watched at once. */
   static final int MOST = 3;

   private static final class View {
      final Tab tab;
      final Camera camera = new Camera();
      final TextureTarget target;
      /** The glowing outlines' own target has to be the size of the picture. */
      final TextureTarget outline;
      /** This world's light map: its own, or the screen would be lit with it. */
      final net.minecraft.client.renderer.Lightmap light = new net.minecraft.client.renderer.Lightmap();
      final net.minecraft.client.renderer.LightmapRenderStateExtractor lightExtractor;
      final net.minecraft.client.renderer.state.LightmapRenderState lightState = new net.minecraft.client.renderer.state.LightmapRenderState();
      final int windowWidth;
      final int windowHeight;
      @Nullable ClientLevel level;
      /** The frame it was last drawn in (0: never). */
      int drawn;

      View(Tab tab, TextureTarget target, TextureTarget outline, int windowWidth, int windowHeight) {
         Minecraft mc = Minecraft.getInstance();
         this.lightExtractor = new net.minecraft.client.renderer.LightmapRenderStateExtractor(mc.gameRenderer, mc);
         this.lightExtractor.tick();
         this.tab = tab;
         this.target = target;
         this.outline = outline;
         this.windowWidth = windowWidth;
         this.windowHeight = windowHeight;
      }
   }

   private static final List<View> VIEWS = new ArrayList<>();
   /** A watched world is being drawn. */
   private static boolean drawing;
   /** Drawing threw once: off until the game is restarted. */
   private static boolean failed;
   private static int frame;
   private static int turn;

   private Pip() {
   }

   public static boolean drawing() {
      return drawing;
   }

   private static @Nullable View of(@Nullable Tab tab) {
      for (View v : VIEWS) {
         if (v.tab == tab) {
            return v;
         }
      }

      return null;
   }

   /** The picture of this tab, or null when it has none (not watched, not being drawn, shaders on, failed). */
   static @Nullable GpuTextureView picture(Tab tab) {
      View v = of(tab);
      return v != null && v.drawn > 0 && frame - v.drawn <= 2 * MOST + 2 ? v.target.getColorTextureView() : null;
   }

   /** Width over height of the pictures. */
   static float aspect(Tab tab) {
      View v = of(tab);
      return v == null ? 16.0F / 9.0F : (float)v.target.width / v.target.height;
   }

   private static boolean shaders() {
      try {
         return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris") && IrisState.shaderPackInUse();
      } catch (Throwable t) {
         return false;
      }
   }

   /** Kept apart so the Iris class is only touched when Iris is there. */
   private static final class IrisState {
      static boolean shaderPackInUse() throws ReflectiveOperationException {
         Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
         Object instance = api.getMethod("getInstance").invoke(null);
         return (Boolean)api.getMethod("isShaderPackInUse").invoke(instance);
      }
   }

   /** The panels are on screen now: in the game itself, or under the chat line being typed. */
   static boolean shown(Minecraft mc) {
      return mc.level != null && mc.player != null && mc.gui.overlay() == null
         && (mc.gui.screen() == null || mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen);
   }

   /** The tabs whose view is drawn: the watched ones behind the screen. */
   private static List<Tab> wanted(Minecraft mc) {
      List<Tab> out = new ArrayList<>(MOST);
      if (mc.level == null || mc.player == null) {
         return out;
      }

      Tab active = Tabs.active();
      for (Tab tab : Tabs.tabs()) {
         // always live (the player's choice): a tab whose packs are not loaded is drawn with the ones that are
         if (tab.watch && tab != active && !tab.closed && tab.world.inPlay() && out.size() < MOST) {
            out.add(tab);
         }
      }

      return out;
   }

   /** Once a frame, before the game draws the screen. */
   public static void frame(Minecraft mc, DeltaTracker deltaTracker) {
      if (VIEWS.isEmpty() && (failed || Tabs.tabs().isEmpty())) {
         return;
      }

      List<Tab> tabs = Tabs.inBackground() ? List.of() : wanted(mc);
      if (!VIEWS.isEmpty()) {
         for (View v : List.copyOf(VIEWS)) {
            // a watched tab that is on screen for now keeps its view: closing it would hold up the change of tab
            boolean kept = v.tab.watch && !v.tab.closed && Tabs.tabs().contains(v.tab);
            // a panel pulled larger or smaller gets a picture of its new size once the player lets go
            boolean resized = v.target.height != WatchPanel.pictureHeight(mc, v.tab) && !WatchPanel.resizing(v.tab);
            if (!kept || resized || v.windowWidth != mc.getWindow().getWidth() || v.windowHeight != mc.getWindow().getHeight()) {
               close(v);
            }
         }
      }

      frame++;
      // behind a menu nothing is drawn
      if (tabs.isEmpty() || failed || mc.getWindow().getHeight() <= 0 || !shown(mc) || shaders()) {
         return;
      }

      try {
         for (Tab tab : tabs) {
            if (of(tab) == null) {
               VIEWS.add(open(mc, tab));
            }
         }

         List<View> live = new ArrayList<>(VIEWS.size());
         for (View v : VIEWS) {
            if (tabs.contains(v.tab)) {
               live.add(v);
            }
         }

         // one world a frame at most, the views in turn; a single view every other frame
         if (live.isEmpty() || live.size() == 1 && (frame & 1) != 0 && live.get(0).drawn > 0) {
            return;
         }

         View use = live.get(Math.floorMod(turn++, live.size()));
         // with the watched tab's own resource set in place: its packs and nobody else's
         Assets.Look look = Assets.of(use.tab);
         Tabs.runIn(use.tab, () -> Assets.during(look, () -> draw(mc, use, look, deltaTracker)));
         ClientLevel shown = mc.level;
         if (shown != null) {
            // the shading of block faces is per dimension, and the watched world just set its own
            mc.gameRenderer.lighting().updateLevel(shown.dimensionType().cardinalLightType());
         }
      } catch (Throwable t) {
         failed = true;
         drawing = false;
         RenderSystem.getModelViewStack().clear();
         Tabs.LOGGER.error("The small view of a tab could not be drawn; it is off until the game restarts", t);
         for (View v : List.copyOf(VIEWS)) {
            close(v);
         }
      }
   }

   private static View open(Minecraft mc, Tab tab) {
      int windowWidth = mc.getWindow().getWidth();
      int windowHeight = mc.getWindow().getHeight();
      int height = WatchPanel.pictureHeight(mc, tab);
      int width = Math.max(16, Math.round(height * (float)windowWidth / windowHeight));
      TextureTarget target = new TextureTarget("HandyKit small view", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
      TextureTarget outline = new TextureTarget("HandyKit small view outline", width, height, GpuFormat.RGBA8_UNORM, null);
      Tabs.LOGGER.info("Small view of tab {}: {}x{}", tab.title(), width, height);
      return new View(tab, target, outline, windowWidth, windowHeight);
   }

   /** In the watched tab's context. */
   private static void draw(Minecraft mc, View v, Assets.Look look, DeltaTracker deltaTracker) {
      ClientLevel level = mc.level;
      LocalPlayer player = mc.player;
      if (level == null || player == null) {
         return;
      }

      Renderers.Pair pair = Renderers.forDrawing(mc, v.tab);
      Renderers.match(mc, pair, look);
      // its own server's view distance: with the one of the tab on screen its terrain would be built anew at every change
      mcport.tabs.mixin.OptionsAccess options = (mcport.tabs.mixin.OptionsAccess)mc.options;
      int distance = options.handykit$serverRenderDistance();
      if (v.tab.serverRenderDistance > 0) {
         options.handykit$setServerRenderDistance(v.tab.serverRenderDistance);
      }

      drawing = true;
      try {
         Renderers.within(mc, pair, () -> {
            LevelRendererAccess access = (LevelRendererAccess)pair.renderer;
            RenderTarget outline = access.handykit$outlineTarget();
            access.handykit$setOutlineTarget(v.outline);
            try {
               if (level != v.level) {
                  v.level = level;
                  v.camera.reset();
                  v.camera.setLevel(level);
               }

               Entity eye = v.tab.camera;
               v.camera.setEntity(eye != null && !eye.isRemoved() && eye.level() == level ? eye : player);
               mc.gameRenderer.lighting().updateLevel(level.dimensionType().cardinalLightType());
               ((Host)mc.gameRenderer).handykit$drawWorld(v.camera, v.target, v.light, v.lightExtractor, v.lightState, deltaTracker);
            } finally {
               access.handykit$setOutlineTarget(outline);
            }
         });
         v.drawn = frame;
      } finally {
         drawing = false;
         options.handykit$setServerRenderDistance(distance);
      }
   }

   /** A watched tab's world ticked: what the game does every tick for the camera of the world on screen. */
   static void tick(Minecraft mc, Tab tab) {
      View v = of(tab);
      if (v != null && v.level == mc.level && mc.level != null) {
         v.camera.tick();
         v.lightExtractor.tick();
      }
   }

   private static void close(View v) {
      VIEWS.remove(v);
      v.target.destroyBuffers();
      v.outline.destroyBuffers();
      v.light.close();
   }
}
