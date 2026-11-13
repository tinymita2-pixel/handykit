package mcport.tabs;

import java.util.function.Consumer;
import mcport.tabs.mixin.CloudRendererAccess;
import mcport.tabs.mixin.LevelExtractorAccess;
import mcport.tabs.mixin.MinecraftAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;

/**
 * Every tab keeps the renderer its world was drawn with.
 *
 * <p>The game has one level renderer and binds it to "the" world; binding it to another world throws all built
 * terrain away and builds the new world's from nothing, which is what a change of tab used to cost (a second or two
 * of terrain popping in). Here a tab that leaves the screen takes its renderer (level renderer and extractor, with
 * whatever a chunk-rendering mod attaches to them) along, built terrain included, and a spare one is put in
 * Minecraft's renderer fields; when the tab comes back its own is put in again, still bound to its world. Block
 * and light changes of a world behind the screen go to that tab's renderer, so what it shows on return is current.
 * The small views draw with the same renderers.
 */
public final class Renderers {
   static final class Pair {
      final LevelRenderer renderer;
      final LevelExtractor extractor;
      /** The game's own: the one resource reloads are announced to, never closed. */
      final boolean original;
      int width;
      int height;
      long used;
      /** The resource set its terrain, sky and clouds were made with (Assets.Look.stamp); 0: nothing made yet. */
      long stamp;

      Pair(LevelRenderer renderer, LevelExtractor extractor, boolean original, int width, int height) {
         this.renderer = renderer;
         this.extractor = extractor;
         this.original = original;
         this.width = width;
         this.height = height;
      }
   }

   /** Tabs behind the screen that keep their built terrain; beyond this the one unused longest starts over on return. */
   private static final int MOST = 4;
   /** What is in Minecraft's renderer fields (apart from the moments a small view draws). */
   private static @Nullable Pair installed;
   /** Bound to no world, ready for the next one. */
   private static @Nullable Pair spare;
   private static @Nullable LevelExtractor keepExtractor;
   private static @Nullable ClientLevel keepLevel;
   /** A change is being handed to the renderer it belongs to, or a renderer is being set up: the guards let it pass. */
   private static boolean routing;
   /** Resources were loaded anew and only the game's own renderer was told. */
   private static boolean reloaded;

   private Renderers() {
   }

   public static boolean passing() {
      return routing || Pip.drawing();
   }

   static @Nullable ClientLevel level(Pair pair) {
      return ((LevelExtractorAccess)pair.extractor).handykit$level();
   }

   private static Pair installed(Minecraft mc) {
      Pair now = installed;
      if (now == null) {
         now = installed = new Pair(mc.levelRenderer, mc.levelExtractor, true, mc.gameRenderer.mainRenderTarget().width, mc.gameRenderer.mainRenderTarget().height);
      }

      return now;
   }

   private static Pair create(Minecraft mc) {
      int width = mc.gameRenderer.mainRenderTarget().width;
      int height = mc.gameRenderer.mainRenderTarget().height;
      LevelRenderer renderer = new LevelRenderer(
         mc.getEntityRenderDispatcher(),
         mc.getBlockEntityRenderDispatcher(),
         mc.getModelManager(),
         mc.getTextureManager(),
         mc.getAtlasManager(),
         mc.getShaderManager(),
         mc.gameRenderer,
         width,
         height
      );
      LevelExtractor extractor = new LevelExtractor(mc, mc.gameRenderer.gameRenderState().levelRenderState, renderer);
      Pair pair = new Pair(renderer, extractor, false, width, height);
      clouds(mc, pair);
      Tabs.trace("renderer made");
      return pair;
   }

   /** The clouds' picture, which the game only hands to its own renderer. */
   private static void clouds(Minecraft mc, Pair pair) {
      if (!pair.original) {
         clouds(pair, mc.getResourceManager());
      }
   }

   private static void clouds(Pair pair, net.minecraft.server.packs.resources.ResourceManager resources) {
      CloudRendererAccess clouds = (CloudRendererAccess)pair.renderer.cloudRenderer();
      ProfilerFiller profiler = Profiler.get();
      clouds.handykit$apply(clouds.handykit$prepare(resources, profiler), resources, profiler);
   }

   /** Once a tick: the renderer on screen was made with the resource set on screen, or starts over with it. */
   static void match(Minecraft mc) {
      if (mc.level != null) {
         match(mc, installed(mc), Assets.shown());
      }
   }

   /**
    * With that set in the game's fields: a renderer whose terrain was built with another set (the tab's packs came
    * or went since) builds it again; its sky and clouds are read from the set's packs.
    */
   static void match(Minecraft mc, Pair pair, Assets.Look look) {
      if (pair.stamp == look.stamp) {
         return;
      }

      boolean unbuilt = pair.stamp == 0 || level(pair) == null;
      pair.stamp = look.stamp;
      net.minecraft.server.packs.resources.ResourceManager resources = Assets.resources(mc, look);
      boolean was = routing;
      routing = true;
      try {
         within(mc, pair, () -> {
            if (!pair.original || resources != mc.getResourceManager() || !unbuilt) {
               pair.extractor.onResourceManagerReload(resources);
               clouds(pair, resources);
            }

            if (!unbuilt) {
               pair.extractor.allChanged();
            }
         });
      } catch (RuntimeException e) {
         Tabs.LOGGER.warn("A tab's renderer could not take its resource set: {}", e.toString());
      } finally {
         routing = was;
      }

      if (!unbuilt) {
         Tabs.trace("renderer starts over with " + look);
      }
   }

   private static void install(Minecraft mc, Pair pair) {
      MinecraftAccess access = (MinecraftAccess)mc;
      access.handykit$setLevelRenderer(pair.renderer);
      access.handykit$setLevelExtractor(pair.extractor);
      installed = pair;
      int width = mc.gameRenderer.mainRenderTarget().width;
      int height = mc.gameRenderer.mainRenderTarget().height;
      if (pair.width != width || pair.height != height) {
         // the window changed size while this one was away
         pair.renderer.resize(width, height);
         pair.width = width;
         pair.height = height;
      }
   }

   /** Runs with this renderer in Minecraft's renderer fields (mods look their own part up through them). */
   static void within(Minecraft mc, Pair pair, Runnable action) {
      MinecraftAccess access = (MinecraftAccess)mc;
      LevelRenderer renderer = mc.levelRenderer;
      LevelExtractor extractor = mc.levelExtractor;
      if (renderer == pair.renderer) {
         action.run();
         return;
      }

      access.handykit$setLevelRenderer(pair.renderer);
      access.handykit$setLevelExtractor(pair.extractor);
      try {
         action.run();
      } finally {
         access.handykit$setLevelRenderer(renderer);
         access.handykit$setLevelExtractor(extractor);
      }
   }

   /** Unbinds a renderer from its world and lets it go (the game's own is kept as the spare). */
   private static void release(Minecraft mc, Pair pair) {
      boolean was = routing;
      routing = true;
      try {
         within(mc, pair, () -> {
            pair.extractor.setLevel(null);
            if (!pair.original) {
               pair.renderer.close();
            }
         });
      } catch (RuntimeException e) {
         Tabs.LOGGER.warn("A tab's renderer could not be closed cleanly: {}", e.toString());
      } finally {
         routing = was;
      }

      if (pair.original) {
         Pair other = spare;
         spare = pair;
         if (other != null && other != pair) {
            release(mc, other);
         }
      }

      Tabs.trace("renderer released");
   }

   /** The world on screen goes behind it: its tab keeps the renderer, a spare one takes its place. Before the level is taken out of the engines. */
   static void leave(Minecraft mc, Tab tab) {
      Pair now = installed(mc);
      if (level(now) == null) {
         return;
      }

      tab.pair = now;
      now.used = System.currentTimeMillis();
      Pair next = spare != null ? spare : create(mc);
      spare = null;
      install(mc, next);
      trim(mc);
   }

   /** A tab comes on screen: its own renderer goes in, and the engines are told of its world without the renderer starting over. */
   static void enter(Minecraft mc, Tab tab) {
      Pair now = installed(mc);
      Pair mine = tab.pair;
      if (mine != null && mine != now) {
         install(mc, mine);
         if (level(now) != null || spare != null && spare != now) {
            release(mc, now);
         } else {
            spare = now;
         }
      }

      Pair use = installed(mc);
      ClientLevel level = mc.level;
      if (level != null && level(use) == level) {
         keepExtractor = use.extractor;
         keepLevel = level;
      }

      Bgm.holding(true);
      try {
         ((MinecraftAccess)mc).handykit$updateLevelInEngines(level, true);
      } finally {
         Bgm.holding(false);
         keepExtractor = null;
         keepLevel = null;
      }

      use.used = System.currentTimeMillis();
   }

   /** LevelExtractor.setLevel: this renderer is bound to that world already; binding again would only throw its terrain away. */
   public static boolean keeps(LevelExtractor extractor, @Nullable ClientLevel level) {
      return level != null && extractor == keepExtractor && level == keepLevel;
   }

   /** The tab is gone: so is its renderer (unless it is the one on screen, which the game itself unbinds). */
   static void forget(Tab tab) {
      Pair pair = tab.pair;
      tab.pair = null;
      if (pair != null && pair != installed) {
         release(Minecraft.getInstance(), pair);
      }
   }

   /** Too many kept: the tab unused longest (and not watched) starts over when it returns. */
   private static void trim(Minecraft mc) {
      while (true) {
         int kept = 0;
         Tab oldest = null;
         for (Tab tab : Tabs.tabs()) {
            Pair pair = tab.pair;
            if (pair == null || pair == installed) {
               continue;
            }

            kept++;
            if (!tab.watch && (oldest == null || pair.used < oldest.pair.used)) {
               oldest = tab;
            }
         }

         if (kept <= MOST || oldest == null) {
            return;
         }

         forget(oldest);
      }
   }

   /** For a small view, in the tab's context: its renderer, made and bound to its world as needed. */
   static Pair forDrawing(Minecraft mc, Tab tab) {
      Pair pair = tab.pair;
      if (pair == null) {
         pair = tab.pair = create(mc);
      }

      ClientLevel level = mc.level;
      if (level(pair) != level) {
         Pair bind = pair;
         boolean was = routing;
         routing = true;
         try {
            within(mc, bind, () -> bind.extractor.setLevel(level));
         } finally {
            routing = was;
         }
      }

      pair.used = System.currentTimeMillis();
      return pair;
   }

   /**
    * A world tells "the" renderer of a change (a block, a section, light). True when it was taken over here: for a
    * world behind the screen it goes to that tab's own renderer (or nowhere when it has none), and a world on screen
    * whose level was made while another renderer was in place is pointed at the one that is in place now.
    */
   public static boolean redirect(LevelExtractor receiver, Consumer<LevelExtractor> change) {
      if (routing || Pip.drawing()) {
         return false;
      }

      Minecraft mc = Minecraft.getInstance();
      if (Tabs.inBackground()) {
         Tab tab = Tabs.backgroundTab();
         Pair pair = tab == null ? null : tab.pair;
         if (pair != null && pair != installed && mc.level != null && level(pair) == mc.level) {
            routing = true;
            try {
               within(mc, pair, () -> change.accept(pair.extractor));
            } catch (RuntimeException e) {
               Tabs.LOGGER.warn("A change in a world behind the screen did not reach its renderer: {}", e.toString());
            } finally {
               routing = false;
            }
         }

         return true;
      }

      if (receiver != mc.levelExtractor) {
         routing = true;
         try {
            change.accept(mc.levelExtractor);
         } finally {
            routing = false;
         }

         return true;
      }

      return false;
   }

   /** For the test hook: which renderer is on screen and what it has built. */
   static String describe(Minecraft mc) {
      Pair now = installed;
      String stats;
      try {
         stats = mc.level == null ? "-" : String.valueOf(mc.levelExtractor.sectionStatistics());
      } catch (RuntimeException e) {
         stats = e.toString();
      }

      return (now == null ? "untouched" : now.original ? "own" : "made") + (spare == null ? "" : spare.original ? " spare=own" : " spare=made") + " " + stats;
   }

   /** LevelExtractor.onResourceManagerReload: only the game's own renderer hears of a reload. */
   public static void onReload() {
      if (!routing) {
         reloaded = true;
      }
   }

   /**
    * LevelExtractor.allChanged on the renderer on screen. The game calls it when a reload is through; that is the
    * moment the other renderers catch up (their terrain was built with the old textures).
    */
   public static void onAllChanged(LevelExtractor extractor) {
      if (!reloaded || routing) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (extractor != mc.levelExtractor) {
         return;
      }

      reloaded = false;
      Pair now = installed(mc);
      refresh(mc, now, false);
      if (spare != null) {
         refresh(mc, spare, true);
      }

      for (Tab tab : Tabs.tabs()) {
         Pair pair = tab.pair;
         if (pair == null || pair == now) {
            continue;
         }

         if (tab.watch || tab.look == null) {
            // its packs are among the ones loaded (what is built now is right when it returns), or it is watched
            refresh(mc, pair, true);
         } else {
            // its terrain was built with other packs than the ones loaded now, and would be built again with its own
            // when it returns: nothing worth keeping
            forget(tab);
         }
      }
   }

   private static void refresh(Minecraft mc, Pair pair, boolean rebuild) {
      boolean was = routing;
      routing = true;
      try {
         within(mc, pair, () -> {
            if (!pair.original) {
               pair.extractor.onResourceManagerReload(mc.getResourceManager());
               clouds(mc, pair);
            }

            if (rebuild) {
               pair.extractor.allChanged();
            }
         });
      } catch (RuntimeException e) {
         Tabs.LOGGER.warn("A tab's renderer could not take the new resources: {}", e.toString());
      } finally {
         routing = was;
      }
   }
}
