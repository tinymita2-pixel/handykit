package mcport.tabs;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.AtlasGlyphProvider;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.particle.ParticleResources;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;

/**
 * The game's resources as each tab sees them: its own server packs on top of the game's, and nobody else's.
 *
 * <p>The game holds one loaded set of resources (textures stitched into atlases, baked models, fonts, shaders,
 * sounds, language) and makes a new one from scratch whenever a server pack comes or goes; that took a second or
 * two on every change of tab between servers with different packs, and a small view of another tab could only be
 * drawn with the packs of the tab on screen. Here the game's own set never holds a server pack. Each list of server
 * packs gets a set of its own (a {@link Look}), made beside the game while it goes on: the files are read and the
 * atlases stitched on worker threads with the game's own loading code, and the short steps that need the client
 * thread run a few a frame. What a set consists of is the values of a fixed list of fields of the game's managers
 * (the {@link Slot}s); putting a set in is writing those fields, a few microseconds. So a change of tab loads
 * nothing, and a small view is drawn with its own tab's set put in for just that drawing.
 */
public final class Assets {
   private static final int VISUAL = 0;
   private static final int FONT = 1;
   private static final int SHADER = 2;
   private static final int LANG = 3;
   /** Client thread time a frame for the steps of a set that is being made. */
   private static final long BUDGET = 3_000_000L;

   /** One loaded set of resources: the game's own, or that with a list of server packs on top. */
   public static final class Look {
      final int no;
      final String key;
      final List<Path> files;
      final List<UUID> ids;
      /** What each slot holds for this set, while it is not the one in the game's fields. */
      Object[] values;
      /** Changes whenever the textures and models of this set were made anew: terrain built with another stamp is stale. */
      long stamp;
      volatile boolean ready;
      volatile boolean failed;
      volatile boolean gone;
      int madeIn;
      long started;
      @Nullable MultiPackResourceManager resources;
      final List<PackResources> opened = new ArrayList<>();
      /** The parts its packs change; the others are the game's own, shared. */
      final boolean[] own = new boolean[4];
      boolean sounds;
      /** Textures outside the atlases that its packs hold, and the ones of those that were asked for so far. */
      Set<Identifier> textureIds = Set.of();
      final Map<Identifier, AbstractTexture> textures = new HashMap<>();
      /** SoundManager.Preparations of its packs; null: the game's own sounds. */
      @Nullable Object soundPrep;
      /** Its sound events, and the sound files its own packs hold. */
      Map<Identifier, Object> soundEvents = Map.of();
      Map<Identifier, Resource> soundFiles = Map.of();
      Set<Identifier> soundPaths = Set.of();
      final List<Consumer<Look>> waiting = new ArrayList<>();
      long unusedSince;

      Look(int no, String key, List<Path> files, List<UUID> ids) {
         this.no = no;
         this.key = key;
         this.files = files;
         this.ids = ids;
      }

      @Override
      public String toString() {
         return this.no == 0 ? "the game's own resources" : "resource set " + this.no + " (" + this.files.size() + " server pack" + (this.files.size() == 1 ? "" : "s") + ")";
      }
   }

   /** One field of one of the game's resource managers. */
   private abstract static class Slot {
      final int part;
      /** What a set that makes this part itself starts with (null: with the game's own value). */
      @Nullable Supplier<Object> fresh;

      Slot(int part) {
         this.part = part;
      }

      abstract Object get();

      abstract void set(Object value);
   }

   private static final class FieldSlot extends Slot {
      final @Nullable Object target;
      final Field field;

      FieldSlot(int part, @Nullable Object target, Field field) {
         super(part);
         this.target = target;
         this.field = field;
      }

      @Override
      Object get() {
         try {
            return this.field.get(this.target);
         } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
         }
      }

      @Override
      void set(Object value) {
         try {
            this.field.set(this.target, value);
         } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
         }
      }
   }

   private record Step(Look look, Runnable action) {
   }

   private record Retry(List<UUID> ids, List<Path> files, List<Consumer<Look>> waiting) {
   }

   static final Look BASE = new Look(0, "", List.of(), List.of());
   private static final Map<String, Look> LOOKS = new LinkedHashMap<>();
   private static final ConcurrentLinkedQueue<Step> STEPS = new ConcurrentLinkedQueue<>();
   private static final List<Retry> AGAIN = new ArrayList<>();
   /** Items of the GUI that were collected under another set than the one on screen, this frame. */
   private static final java.util.IdentityHashMap<Object, Look> ITEMS = new java.util.IdentityHashMap<>();
   private static Slot @Nullable [] slots;
   private static boolean broken;
   /** The set whose values are in the game's fields right now. */
   private static Look current = BASE;
   /** The set of the tab on screen: what is in the fields whenever nothing else is being drawn or made. */
   private static Look shown = BASE;
   /** The set whose sounds are in the sound manager. */
   private static Look soundsOf = BASE;
   private static @Nullable Map<Identifier, Object> baseSounds;
   private static @Nullable Map<Identifier, Resource> baseSoundFiles;
   /** A step of this set is running: what the game's loading code replaces belongs to another set and is kept. */
   private static @Nullable Look applying;
   /** The game is loading its own resources anew; until it is through, everything is drawn with those. */
   private static boolean frozen;
   /** Counts the times the game loaded its own resources. */
   private static int epoch;
   private static int numbers;
   private static long stamps = 1;
   private static int serial;

   private static FontManager fontManager;
   private static ParticleResources particles;
   private static Field particleReload;
   private static Object glyphsA;
   private static Object glyphsB;
   private static Method invalidate;
   private static Field soundRegistry;
   private static Field soundCache;
   private static Field soundEngineField;
   private static Field soundBuffers;
   private static Field soundBufferCache;
   private static Method soundPrepare;
   private static Method prepApply;
   private static Field prepEvents;
   private static Field prepFiles;
   private static Field resetFailedEffects;
   private static Field fontSetsField;
   private static Field fontProvidersField;
   private static Field postChainsField;
   private static Field pipelinesField;

   static {
      BASE.ready = true;
      BASE.stamp = stamps;
   }

   private Assets() {
   }

   // ---------------------------------------------------------------- the fields a set consists of

   private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
      Field field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return field;
   }

   private static <T> T listener(Minecraft mc, List<PreparableReloadListener> all, Class<T> type) {
      for (PreparableReloadListener listener : all) {
         if (type.isInstance(listener)) {
            return type.cast(listener);
         }
      }

      throw new IllegalStateException("The game has no " + type.getSimpleName());
   }

   @SuppressWarnings("unchecked")
   private static List<PreparableReloadListener> listeners(Minecraft mc) throws ReflectiveOperationException {
      Object manager = mc.getResourceManager();
      return (List<PreparableReloadListener>)field(manager.getClass(), "listeners").get(manager);
   }

   private static void all(List<Slot> out, int part, Object target, Class<?> from, Class<?> upTo, @Nullable Object blank) throws IllegalAccessException {
      for (Class<?> type = from; type != null && type != upTo; type = type.getSuperclass()) {
         for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) {
               continue;
            }

            f.setAccessible(true);
            FieldSlot slot = new FieldSlot(part, target, f);
            if (blank != null) {
               Object start = f.get(blank);
               slot.fresh = () -> start;
            }

            out.add(slot);
         }
      }
   }

   /** False when this game version keeps its resources some other way: then server packs load the way the game does it. */
   static boolean works() {
      return slots != null;
   }

   /** On the client thread, once the game is up: the list of fields is made. */
   static void init(Minecraft mc) {
      if (slots == null && !broken) {
         slots(mc);
      }
   }

   private static Slot @Nullable [] slots(Minecraft mc) {
      if (slots != null || broken) {
         return slots;
      }

      try {
         List<Slot> out = new ArrayList<>();
         List<PreparableReloadListener> all = listeners(mc);
         AtlasManager atlases = mc.getAtlasManager();
         List<TextureAtlas> list = new ArrayList<>();
         atlases.forEach((id, atlas) -> list.add(atlas));
         for (TextureAtlas atlas : list) {
            // a new one that never held anything: what the game's upload expects to find the first time
            all(out, VISUAL, atlas, TextureAtlas.class, Object.class, new TextureAtlas(atlas.location()));
         }

         FieldSlot sprites = new FieldSlot(VISUAL, atlases, field(AtlasManager.class, "spriteLookup"));
         sprites.fresh = Map::of;
         out.add(sprites);
         all(out, VISUAL, mc.getModelManager(), ModelManager.class, Object.class, null);
         fontManager = listener(mc, all, FontManager.class);
         fontProvidersField = field(FontManager.class, "providersToClose");
         fontSetsField = field(FontManager.class, "fontSets");
         FieldSlot closing = new FieldSlot(FONT, fontManager, fontProvidersField);
         closing.fresh = ArrayList::new;
         out.add(closing);
         FieldSlot sets = new FieldSlot(FONT, fontManager, fontSetsField);
         sets.fresh = HashMap::new;
         out.add(sets);
         FieldSlot inText = new FieldSlot(VISUAL, fontManager, field(FontManager.class, "atlasProviders"));
         inText.fresh = () -> {
            Map<Identifier, AtlasGlyphProvider> made = new HashMap<>();
            atlases.forEach((id, atlas) -> made.put(id, new AtlasGlyphProvider(atlas)));
            return made;
         };
         out.add(inText);
         glyphsA = field(FontManager.class, "anyGlyphs").get(fontManager);
         glyphsB = field(FontManager.class, "nonFishyGlyphs").get(fontManager);
         invalidate = glyphsA.getClass().getDeclaredMethod("invalidate");
         invalidate.setAccessible(true);
         out.add(new FieldSlot(VISUAL, listener(mc, all, EquipmentAssetManager.class), field(EquipmentAssetManager.class, "equipmentAssets")));
         EntityRenderDispatcher entities = mc.getEntityRenderDispatcher();
         for (String name : new String[]{"renderers", "playerRenderers", "mannequinRenderers"}) {
            out.add(new FieldSlot(VISUAL, entities, field(EntityRenderDispatcher.class, name)));
         }

         out.add(new FieldSlot(VISUAL, mc.getBlockEntityRenderDispatcher(), field(BlockEntityRenderDispatcher.class, "renderers")));
         particles = listener(mc, all, ParticleResources.class);
         particleReload = field(ParticleResources.class, "onReload");
         for (Object set : ((Map<?, ?>)field(ParticleResources.class, "spriteSets").get(particles)).values()) {
            out.add(new FieldSlot(VISUAL, set, field(set.getClass(), "sprites")));
         }

         postChainsField = field(ShaderManager.class, "postChains");
         pipelinesField = field(RenderSystem.class, "currentPipelineCache");
         out.add(new FieldSlot(SHADER, mc.getShaderManager(), postChainsField));
         out.add(new Slot(SHADER) {
            @Override
            Object get() {
               try {
                  return pipelinesField.get(null);
               } catch (IllegalAccessException e) {
                  throw new IllegalStateException(e);
               }
            }

            @Override
            void set(Object value) {
               RenderSystem.setCurrentPipelineCache((PipelineCache)value);
            }
         });
         out.add(new Slot(LANG) {
            @Override
            Object get() {
               return Language.getInstance();
            }

            @Override
            void set(Object value) {
               Language.inject((Language)value);
            }
         });
         soundRegistry = field(SoundManager.class, "registry");
         soundCache = field(SoundManager.class, "soundCache");
         soundEngineField = field(SoundManager.class, "soundEngine");
         soundBuffers = field(SoundEngine.class, "soundBuffers");
         soundBufferCache = field(soundBuffers.getType(), "cache");
         soundPrepare = SoundManager.class.getDeclaredMethod("prepare", ResourceManager.class, ProfilerFiller.class);
         soundPrepare.setAccessible(true);
         Class<?> prepared = Class.forName("net.minecraft.client.sounds.SoundManager$Preparations");
         prepFiles = field(prepared, "soundCache");
         prepEvents = field(prepared, "registry");
         prepApply = prepared.getDeclaredMethod("apply", Map.class, Map.class, SoundEngine.class);
         prepApply.setAccessible(true);
         resetFailedEffects = field(net.minecraft.client.renderer.GameRenderer.class, "shouldResetFailedPostEffects");
         slots = out.toArray(Slot[]::new);
         BASE.values = new Object[slots.length];
         Tabs.LOGGER.info("Each tab gets its own resource set ({} fields of the game's resource managers)", slots.length);
      } catch (ReflectiveOperationException | RuntimeException e) {
         broken = true;
         Tabs.LOGGER.warn("Resources cannot be kept per tab in this game version ({}); server packs load the way the game does it", e.toString());
      }

      return slots;
   }

   private static Object baseValue(int index) {
      return current == BASE ? slots[index].get() : BASE.values[index];
   }

   /** Writes this set into the game's fields (and keeps what was there for the set that had them). */
   private static void enter(Look to) {
      Look from = current;
      if (from == to) {
         return;
      }

      Slot[] all = slots;
      Object[] out = from.values;
      Object[] in = to.values;
      for (int i = 0; i < all.length; i++) {
         Slot slot = all[i];
         out[i] = slot.get();
         slot.set(in[i]);
      }

      current = to;
      try {
         // the font keeps the last glyphs it looked up
         invalidate.invoke(glyphsA);
         invalidate.invoke(glyphsB);
      } catch (ReflectiveOperationException e) {
         throw new IllegalStateException(e);
      }
   }

   // ---------------------------------------------------------------- who sees what

   /** The set a tab is drawn with: its own once that is made, the game's own until then. */
   static Look of(@Nullable Tab tab) {
      if (tab == null || frozen) {
         return BASE;
      }

      Look look = tab.look;
      return look != null && look.ready && !look.gone ? look : BASE;
   }

   static Look shown() {
      return shown;
   }

   /** A set for this tab is asked for and not there yet. */
   static boolean busy(@Nullable Tab tab) {
      if (tab == null) {
         return false;
      }

      Look next = tab.nextLook;
      return frozen && tab.look != null || next != null && !next.ready && !next.failed && !next.gone;
   }

   /** The resources a tab's packs are read from (for what the game loads by itself per renderer: clouds). */
   static ResourceManager resources(Minecraft mc, Look look) {
      return look.resources != null ? look.resources : mc.getResourceManager();
   }

   /** Draws something of another tab with that tab's resources in place. */
   static void during(Look look, Runnable action) {
      Look before = current;
      if (before == look || slots == null || look.values == null || frozen) {
         action.run();
         return;
      }

      enter(look);
      try {
         action.run();
      } finally {
         enter(before);
      }
   }

   /** The tab on screen is drawn with this set from now on. Nothing is loaded: the set is there. */
   static void show(Minecraft mc, Look look) {
      if (slots == null) {
         return;
      }

      if (!look.ready || look.gone || frozen) {
         look = BASE;
      }

      if (shown == look && current == look) {
         return;
      }

      long started = System.nanoTime();
      enter(look);
      shown = look;
      sounds(mc, look);
      try {
         // effects that failed with the other set's shaders get another try
         resetFailedEffects.setBoolean(mc.gameRenderer, true);
      } catch (IllegalAccessException e) {
         throw new IllegalStateException(e);
      }

      Tabs.trace("on screen: " + look + ", put in within " + (System.nanoTime() - started) / 1000L + " us");
   }

   /** The sound events and files of the set on screen go into the sound manager. The engine is not restarted. */
   @SuppressWarnings("unchecked")
   private static void sounds(Minecraft mc, Look look) {
      Look to = look.soundPrep != null ? look : BASE;
      if (soundsOf == to) {
         return;
      }

      try {
         SoundManager manager = mc.getSoundManager();
         Map<Identifier, Object> registry = (Map<Identifier, Object>)soundRegistry.get(manager);
         Map<Identifier, Resource> files = (Map<Identifier, Resource>)soundCache.get(manager);
         if (soundsOf == BASE && baseSounds == null) {
            baseSounds = new HashMap<>(registry);
            baseSoundFiles = new HashMap<>(files);
         }

         Object engine = soundEngineField.get(manager);
         // a file both sets have under one name is another file now: what was decoded of it is dropped
         Map<Identifier, ?> decoded = (Map<Identifier, ?>)soundBufferCache.get(soundBuffers.get(engine));
         for (Identifier path : soundsOf.soundPaths) {
            decoded.remove(path);
         }

         for (Identifier path : to.soundPaths) {
            decoded.remove(path);
         }

         if (to == BASE) {
            registry.clear();
            registry.putAll(baseSounds);
            files.clear();
            files.putAll(baseSoundFiles);
         } else {
            prepApply.invoke(to.soundPrep, registry, files, engine);
         }

         soundsOf = to;
         otherSounds(mc);
      } catch (ReflectiveOperationException | RuntimeException e) {
         Tabs.LOGGER.warn("The sounds of the tab on screen could not be put in: {}", e.toString());
      }
   }

   /**
    * A tab behind the screen plays a sound of its own pack: the file has to be there for the sound engine. The files
    * of every set are kept in the sound manager's list (a name two sets both have is the file of the set on screen).
    */
   @SuppressWarnings("unchecked")
   private static void otherSounds(Minecraft mc) {
      if (slots == null) {
         return;
      }

      try {
         Map<Identifier, Resource> files = (Map<Identifier, Resource>)soundCache.get(mc.getSoundManager());
         for (Look look : LOOKS.values()) {
            if (look != soundsOf && look.ready && !look.gone) {
               look.soundFiles.forEach(files::putIfAbsent);
            }
         }
      } catch (ReflectiveOperationException | RuntimeException e) {
         Tabs.LOGGER.warn("The sound files of the tabs behind the screen could not be listed: {}", e.toString());
      }
   }

   /** SoundManager.getSoundEvent in a tab behind the screen whose sounds are not the ones on screen. */
   public static boolean soundsElsewhere() {
      if (slots == null || !Tabs.inBackground() || LOOKS.isEmpty()) {
         return false;
      }

      Look look = of(Tabs.contextTab());
      return (look.soundPrep != null ? look : BASE) != soundsOf;
   }

   /** ...its own sound event of that name (null: it has none, as on its own screen). */
   public static @Nullable Object soundEvent(Identifier name) {
      Look look = of(Tabs.contextTab());
      if (look.soundPrep != null) {
         return look.soundEvents.get(name);
      }

      Map<Identifier, Object> base = baseSounds;
      return base == null ? null : base.get(name);
   }

   /** TextureManager.getTexture: a texture outside the atlases that a pack of the set in place holds is that set's own. */
   public static @Nullable AbstractTexture texture(Identifier id) {
      Look look = current;
      if (look.textureIds.isEmpty() || !look.textureIds.contains(id) || !RenderSystem.isOnRenderThread()) {
         return null;
      }

      AbstractTexture made = look.textures.get(id);
      if (made == null) {
         SimpleTexture texture = new SimpleTexture(id);
         TextureContents contents;
         try {
            contents = texture.loadContents(look.resources);
         } catch (Exception e) {
            Tabs.LOGGER.warn("A server pack's texture {} could not be read: {}", id, e.toString());
            contents = TextureContents.createMissing();
         }

         texture.apply(contents);
         look.textures.put(id, texture);
         made = texture;
      }

      return made;
   }

   /** GuiItemRenderState is made: an item of the GUI, collected while another set than the one on screen is in place. */
   public static void tagItem(Object item) {
      if (current != shown) {
         ITEMS.put(item, current);
      }
   }

   /** The GUI draws that item's icon into its sheet: with the set it was collected under. */
   public static <T> T itemDuring(Object item, Supplier<T> action) {
      Look look = ITEMS.isEmpty() ? null : ITEMS.get(item);
      Look before = current;
      if (look == null || look == before || look.gone || look.values == null || frozen) {
         return action.get();
      }

      enter(look);
      try {
         return action.get();
      } finally {
         enter(before);
      }
   }

   /** FontManager makes a font: the pages its glyphs are drawn on are named after the font, and each set has its own. */
   public static Identifier fontPages(Identifier font) {
      Look look = applying;
      return look == null || look == BASE ? font : font.withPrefix("hk" + look.no + "/");
   }

   /** A set with server packs is in the game's fields right now (on screen, being drawn into a small view, or being made). */
   public static boolean serverPacksInPlace() {
      return current != BASE;
   }

   /**
    * Once a tick: the moving textures (water, fire) of the sets that are only seen in small views move on. The game
    * does that for the set on screen.
    */
   static void animate(Minecraft mc) {
      if (LOOKS.isEmpty() || frozen || slots == null) {
         return;
      }

      Look done = null;
      for (Tab tab : Tabs.tabs()) {
         if (!tab.watch || tab.closed || tab == Tabs.active()) {
            continue;
         }

         Look look = of(tab);
         if (look == shown || look == done || !look.own[VISUAL]) {
            continue;
         }

         done = look;
         during(look, () -> mc.getAtlasManager().forEach((id, atlas) -> atlas.cycleAnimationFrames()));
      }
   }

   /** The game's loading code closes what it replaces; during a step of ours that belongs to another set and stays. */
   public static boolean keeping() {
      return applying != null;
   }

   // ---------------------------------------------------------------- making a set

   /** The set for these pack files (bottom first), made if it is not there. Empty: the game's own. */
   static Look obtain(Minecraft mc, List<UUID> ids, List<Path> files) {
      if (files.isEmpty() || slots(mc) == null) {
         return BASE;
      }

      StringBuilder key = new StringBuilder();
      for (Path file : files) {
         key.append(file.toAbsolutePath()).append('\n');
      }

      Look look = LOOKS.get(key.toString());
      if (look != null && !look.failed && !look.gone) {
         return look;
      }

      look = new Look(++numbers, key.toString(), List.copyOf(files), List.copyOf(ids));
      LOOKS.put(look.key, look);
      make(mc, look);
      return look;
   }

   /** Runs with the set when it is there, with null when it could not be made; at once when that is known already. */
   static void whenMade(Look look, Consumer<@Nullable Look> then) {
      if (look.ready) {
         then.accept(look);
      } else if (look.failed || look.gone) {
         then.accept(null);
      } else {
         look.waiting.add(then);
      }
   }

   private static void make(Minecraft mc, Look look) {
      look.madeIn = epoch;
      look.started = System.currentTimeMillis();
      List<PackResources> base = mc.getResourceManager().listPacks().toList();
      CompletableFuture.runAsync(() -> open(look, base), Util.backgroundExecutor()).whenComplete((nothing, error) -> mc.execute(() -> {
         if (error != null) {
            fail(mc, look, error);
         } else if (!stale(look)) {
            try {
               start(mc, look);
            } catch (ReflectiveOperationException | RuntimeException e) {
               fail(mc, look, e);
            }
         }
      }));
   }

   private static boolean stale(Look look) {
      return look.gone || look.failed || look.madeIn != epoch;
   }

   /** On a worker thread: the pack files are opened the way the game opens a server pack, and looked through. */
   private static void open(Look look, List<PackResources> base) {
      List<PackResources> all = new ArrayList<>(base);
      for (int i = 0; i < look.files.size(); i++) {
         Path path = look.files.get(i);
         String name = String.format(java.util.Locale.ROOT, "server/%08X/%s", 0x48000000 + serial++, look.ids.get(i));
         PackLocationInfo info = new PackLocationInfo(name, Component.translatable("resourcePack.server.name"), PackSource.SERVER, Optional.empty());
         Pack.ResourcesSupplier supplier = new FilePackResources.FileResourcesSupplier(path);
         Pack.Metadata metadata = Pack.readPackMetadata(info, supplier, SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES), PackType.CLIENT_RESOURCES);
         if (metadata == null) {
            throw new IllegalStateException("invalid pack metadata in " + path.getFileName());
         }

         new Pack(info, supplier, metadata, new PackSelectionConfig(true, Pack.Position.TOP, true)).open().forEach(look.opened::add);
      }

      all.addAll(look.opened);
      look.resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, all);
      Set<Identifier> textures = new HashSet<>();
      boolean[] has = new boolean[5];
      for (PackResources pack : look.opened) {
         for (String namespace : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
            pack.listResources(PackType.CLIENT_RESOURCES, namespace, "textures", (id, in) -> textures.add(id));
            for (String dir : new String[]{"models", "items", "blockstates", "particles", "equipment", "atlases"}) {
               pack.listResources(PackType.CLIENT_RESOURCES, namespace, dir, (id, in) -> has[VISUAL] = true);
            }

            pack.listResources(PackType.CLIENT_RESOURCES, namespace, "font", (id, in) -> has[FONT] = true);
            pack.listResources(PackType.CLIENT_RESOURCES, namespace, "shaders", (id, in) -> has[SHADER] = true);
            pack.listResources(PackType.CLIENT_RESOURCES, namespace, "post_effect", (id, in) -> has[SHADER] = true);
            pack.listResources(PackType.CLIENT_RESOURCES, namespace, "lang", (id, in) -> has[LANG] = true);
            pack.listResources(PackType.CLIENT_RESOURCES, namespace, "sounds", (id, in) -> has[4] = true);
            if (pack.getResource(PackType.CLIENT_RESOURCES, Identifier.fromNamespaceAndPath(namespace, "sounds.json")) != null) {
               has[4] = true;
            }
         }
      }

      look.textureIds = textures;
      look.own[VISUAL] = has[VISUAL] || !textures.isEmpty();
      look.own[FONT] = has[FONT];
      look.own[SHADER] = has[SHADER];
      look.own[LANG] = has[LANG];
      look.sounds = has[4];
   }

   /** On the client thread: the game's own loading code is started on this set's resources. */
   @SuppressWarnings("unchecked")
   private static void start(Minecraft mc, Look look) throws ReflectiveOperationException {
      Slot[] all = slots;
      Object[] values = new Object[all.length];
      for (int i = 0; i < all.length; i++) {
         Slot slot = all[i];
         values[i] = look.own[slot.part] && slot.fresh != null ? slot.fresh.get() : baseValue(i);
      }

      look.values = values;
      List<PreparableReloadListener> run = new ArrayList<>();
      for (PreparableReloadListener listener : listeners(mc)) {
         boolean wanted = listener instanceof ShaderManager ? look.own[SHADER]
            : listener instanceof FontManager ? look.own[FONT]
            : (listener instanceof AtlasManager || listener instanceof ModelManager || listener instanceof EquipmentAssetManager || listener instanceof EntityRenderDispatcher
               || listener instanceof BlockEntityRenderDispatcher || listener instanceof ParticleResources) && look.own[VISUAL];
         if (wanted) {
            run.add(listener);
         }
      }

      List<CompletableFuture<?>> parts = new ArrayList<>();
      Executor background = Util.backgroundExecutor();
      if (!run.isEmpty()) {
         Executor client = action -> STEPS.add(new Step(look, action));
         ReloadInstance instance = SimpleReloadInstance.of(look.resources, run, background, client, CompletableFuture.completedFuture(Unit.INSTANCE));
         parts.add(instance.done());
      }

      if (look.own[LANG]) {
         String code = mc.getLanguageManager().getSelected();
         LanguageInfo info = mc.getLanguageManager().getLanguage(code);
         List<String> stack = new ArrayList<>(2);
         stack.add("en_us");
         if (!code.equals("en_us") && info != null) {
            stack.add(code);
         }

         boolean rightToLeft = info != null && info.bidirectional();
         int index = langSlot();
         parts.add(CompletableFuture.supplyAsync(() -> ClientLanguage.loadFrom(look.resources, stack, rightToLeft), background).thenAcceptAsync(language -> {
            if (current == look) {
               all[index].set(language);
            } else {
               look.values[index] = language;
            }
         }, mc));
      }

      if (look.sounds) {
         SoundManager manager = mc.getSoundManager();
         parts.add(CompletableFuture.runAsync(() -> {
            try {
               Object prepared = soundPrepare.invoke(manager, look.resources, InactiveProfiler.INSTANCE);
               Map<Identifier, Resource> own = new HashMap<>();
               for (Map.Entry<?, ?> file : ((Map<?, ?>)prepFiles.get(prepared)).entrySet()) {
                  if (look.opened.contains(((Resource)file.getValue()).source())) {
                     own.put((Identifier)file.getKey(), (Resource)file.getValue());
                  }
               }

               look.soundFiles = own;
               look.soundPaths = own.keySet();
               look.soundEvents = (Map<Identifier, Object>)prepEvents.get(prepared);
               look.soundPrep = prepared;
            } catch (ReflectiveOperationException e) {
               throw new IllegalStateException(e);
            }
         }, background));
      }

      CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).whenComplete((nothing, error) -> mc.execute(() -> {
         if (error != null) {
            fail(mc, look, error);
         } else if (!stale(look)) {
            look.stamp = ++stamps;
            look.ready = true;
            otherSounds(mc);
            Tabs.LOGGER.info("Made {} beside the game in {} ms ({}{}{}{}{}{} textures of its own outside the atlases)", look, System.currentTimeMillis() - look.started,
               look.own[VISUAL] ? "textures and models, " : "", look.own[FONT] ? "fonts, " : "", look.own[SHADER] ? "shaders, " : "", look.own[LANG] ? "language, " : "",
               look.sounds ? "sounds, " : "", look.textureIds.size());
            done(look, true);
         }
      }));
   }

   private static int langSlot() {
      for (int i = slots.length - 1; i >= 0; i--) {
         if (slots[i].part == LANG) {
            return i;
         }
      }

      throw new IllegalStateException();
   }

   private static void done(Look look, boolean made) {
      List<Consumer<Look>> waiting = List.copyOf(look.waiting);
      look.waiting.clear();
      for (Consumer<Look> then : waiting) {
         try {
            then.accept(made ? look : null);
         } catch (RuntimeException e) {
            Tabs.LOGGER.warn("A tab could not take its resources", e);
         }
      }
   }

   private static void fail(Minecraft mc, Look look, Throwable error) {
      if (look.failed || look.gone) {
         return;
      }

      Throwable cause = error;
      while (cause.getCause() != null && (cause instanceof java.util.concurrent.CompletionException || cause instanceof IllegalStateException && cause.getMessage() == null)) {
         cause = cause.getCause();
      }

      Tabs.LOGGER.warn("{} could not be made: {}", look, cause.toString());
      look.failed = true;
      done(look, false);
      drop(mc, look);
   }

   /** Once a frame on the client thread, outside all drawing: a few steps of the sets that are being made. */
   static void frame(Minecraft mc) {
      if (!ITEMS.isEmpty()) {
         ITEMS.clear();
      }

      if (STEPS.isEmpty() || slots == null) {
         return;
      }

      long until = System.nanoTime() + BUDGET;
      Step step;
      while ((step = STEPS.poll()) != null) {
         Look look = step.look();
         if (stale(look) || frozen) {
            continue;
         }

         Look before = current;
         Object hook = null;
         long started = System.nanoTime();
         try {
            // the game drops every particle on screen when particle textures are loaded: not for a set made on the side
            hook = particleReload.get(particles);
            particleReload.set(particles, null);
            applying = look;
            enter(look);
            step.action().run();
         } catch (Throwable t) {
            fail(mc, look, t);
         } finally {
            enter(before);
            applying = null;
            try {
               particleReload.set(particles, hook);
            } catch (IllegalAccessException e) {
               Tabs.LOGGER.warn("{}", e.toString());
            }
         }

         long took = System.nanoTime() - started;
         if (took > 20_000_000L) {
            Tabs.trace("a step of " + look + " held the game for " + took / 1_000_000L + " ms");
         }

         if (System.nanoTime() > until) {
            break;
         }
      }
   }

   // ---------------------------------------------------------------- letting go

   /** Closes what this set made for itself. What it shares with the game's own set is left alone. */
   @SuppressWarnings("unchecked")
   private static void drop(Minecraft mc, Look look) {
      if (look == BASE || look.gone) {
         return;
      }

      look.gone = true;
      // nothing is kept for another set here: what this one made is closed for real
      applying = null;
      LOOKS.remove(look.key, look);
      if (shown == look) {
         show(mc, BASE);
      }

      if (soundsOf == look) {
         sounds(mc, BASE);
      }

      Slot[] all = slots;
      if (look.values != null && all != null) {
         Look before = current == look ? BASE : current;
         int pipelines = -1;
         for (int i = 0; i < all.length; i++) {
            if (all[i].part == SHADER && !(all[i] instanceof FieldSlot)) {
               pipelines = i;
            }
         }

         enter(look);
         try {
            if (look.own[VISUAL]) {
               // its atlases: the game's objects stay, what this set uploaded into them goes
               mc.getAtlasManager().forEach((id, atlas) -> atlas.close());
            }

            if (look.own[FONT]) {
               Map<Identifier, FontSet> sets = (Map<Identifier, FontSet>)fontSetsField.get(fontManager);
               sets.values().forEach(FontSet::close);
               sets.clear();
               List<AutoCloseable> providers = (List<AutoCloseable>)fontProvidersField.get(fontManager);
               for (AutoCloseable provider : providers) {
                  provider.close();
               }

               providers.clear();
            }

            if (look.own[SHADER] && pipelines >= 0 && all[pipelines].get() != BASE.values[pipelines]) {
               ((AutoCloseable)postChainsField.get(mc.getShaderManager())).close();
               ((PipelineCache)all[pipelines].get()).close();
            }
         } catch (Exception e) {
            Tabs.LOGGER.warn("{} could not be let go cleanly: {}", look, e.toString());
         } finally {
            enter(before);
         }
      }

      for (AbstractTexture texture : look.textures.values()) {
         texture.close();
      }

      look.textures.clear();
      List<PackResources> opened = List.copyOf(look.opened);
      // files may still be read by a worker that has not noticed
      Util.ioPool().execute(() -> opened.forEach(PackResources::close));
      Tabs.trace("let go of " + look);
   }

   /** Every few seconds: sets no tab has asked for in a while are let go. */
   static void tick(Minecraft mc) {
      if (LOOKS.isEmpty() || frozen) {
         return;
      }

      long now = System.currentTimeMillis();
      for (Look look : List.copyOf(LOOKS.values())) {
         boolean used = false;
         for (Tab tab : Tabs.tabs()) {
            if (tab.look == look || tab.nextLook == look) {
               used = true;
               break;
            }
         }

         if (used || !look.ready && !look.failed) {
            look.unusedSince = 0;
         } else if (look.unusedSince == 0) {
            look.unusedSince = now;
         } else if (now - look.unusedSince > 60_000L) {
            drop(mc, look);
         }
      }
   }

   // ---------------------------------------------------------------- the game loads its own resources anew

   /** Minecraft.reloadResourcePacks: F3+T, the pack screen, an option that needs it. */
   public static void reloadStarts(Minecraft mc) {
      if (slots == null) {
         return;
      }

      // the game writes its new resources into its fields: its own set must be the one in them
      epoch++;
      for (Look look : List.copyOf(LOOKS.values())) {
         if (!look.ready && !look.failed && !look.waiting.isEmpty()) {
            // asked for and not there yet: asked for again once the game is through
            AGAIN.add(new Retry(look.ids, look.files, List.copyOf(look.waiting)));
            look.waiting.clear();
         }

         drop(mc, look);
      }

      show(mc, BASE);
      frozen = true;
      STEPS.clear();
      for (Tab tab : Tabs.tabs()) {
         tab.nextLook = null;
      }
   }

   /** ...and is through: the sets of the open tabs are made again from the new resources. */
   public static void reloadEnds(Minecraft mc) {
      if (!frozen) {
         return;
      }

      frozen = false;
      baseSounds = null;
      baseSoundFiles = null;
      soundsOf = BASE;
      List<Retry> retries = List.copyOf(AGAIN);
      AGAIN.clear();
      for (Retry retry : retries) {
         Look look = obtain(mc, retry.ids(), retry.files());
         for (Consumer<Look> then : retry.waiting()) {
            whenMade(look, then);
         }
      }

      Packs.again(mc);
   }

   /** For the test hook. */
   static String describe() {
      StringBuilder out = new StringBuilder("looks=").append(LOOKS.size()).append(" shown=").append(shown.no);
      for (Look look : LOOKS.values()) {
         out.append(' ').append(look.no).append(look.ready ? ":ready" : look.failed ? ":failed" : ":making");
      }

      return out.toString();
   }
}
