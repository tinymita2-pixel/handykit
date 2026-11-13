package mcport.legacypackfix;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one resource reload needs to know about the shaders of player and server packs: the translated sources of
 * older packs, and the sources those packs replaced, to fall back to. It belongs to the shader set (Configs) it was
 * built for, so a pipeline compiled late against the previous set still gets the previous answers.
 */
public final class LegacyShaderState {
   static final Logger LOGGER = LoggerFactory.getLogger("LegacyPackFix");
   private static final FileToIdConverter POST_EFFECTS = FileToIdConverter.json("post_effect");
   public static final LegacyShaderState EMPTY = new LegacyShaderState(new Planner.Result());

   /** Implemented by the shader set (ShaderConfigsMixin). */
   public interface Holder {
      @Nullable LegacyShaderState handykit$legacyState();

      void handykit$setLegacyState(LegacyShaderState state);
   }

   final Planner.Result plan;
   private final Map<Identifier, ShaderSource.CachedIncludeSource> includes = new HashMap<>();
   /** What an include a pack translated or replaced falls back to. */
   private final Map<Identifier, ShaderSource.CachedIncludeSource> fallbackIncludes = new HashMap<>();
   private final Set<String> fellBack = ConcurrentHashMap.newKeySet();
   private boolean closed;

   private LegacyShaderState(Planner.Result plan) {
      this.plan = plan;
      plan.includes.forEach((id, text) -> this.includes.put(id, ShaderSource.CachedIncludeSource.create(id, text)));
      plan.vanillaIncludes.forEach((id, text) -> this.fallbackIncludes.put(id, ShaderSource.CachedIncludeSource.create(id, text)));
      plan.baseIncludes.forEach((id, text) -> this.fallbackIncludes.putIfAbsent(id, ShaderSource.CachedIncludeSource.create(id, text)));
   }

   /** The state of the shader set a source stands for; sources that are not ours (the built-in fallback cache) have none. */
   public static LegacyShaderState of(@Nullable ShaderSource source) {
      if (source instanceof Holder holder) {
         LegacyShaderState state = holder.handykit$legacyState();
         if (state != null) {
            return state;
         }
      }

      return EMPTY;
   }

   /** A player or server pack carries shaders, so pipelines may need the fallback. */
   public boolean active() {
      return this.plan.active();
   }

   public @Nullable String shader(Identifier id, ShaderType type) {
      return this.plan.translated.get(new Planner.Key(id, type));
   }

   public ShaderSource.@Nullable CachedIncludeSource include(Identifier id) {
      return this.includes.get(id);
   }

   /** Whether a player or server pack has a hand in what this pipeline compiles. */
   public boolean touches(RenderPipeline pipeline) {
      return this.plan.touches(pipeline.getShaders().values());
   }

   /** Whether this pipeline compiles translated sources of an older pack. */
   public boolean translates(RenderPipeline pipeline) {
      return this.plan.translates(pipeline.getShaders().values());
   }

   /** The source to compile instead when the pack's own fails; null means "the same as before". */
   @Nullable String fallbackShader(Identifier id, ShaderType type) {
      Planner.Key key = new Planner.Key(id, type);
      return this.plan.replaces(key) ? this.plan.fallbackShader(key) : null;
   }

   boolean replaces(Identifier id, ShaderType type) {
      return this.plan.replaces(new Planner.Key(id, type));
   }

   ShaderSource.@Nullable CachedIncludeSource fallbackInclude(Identifier id) {
      return this.fallbackIncludes.get(id);
   }

   public void noteFallback(Identifier pipeline) {
      if (this.fellBack.add(pipeline.toString())) {
         LOGGER.warn("[LegacyPackFix] {} could not use the resource pack's shaders; using the shaders they replaced for it", pipeline);
      }
   }

   /** With the shader set this belongs to. */
   public synchronized void close() {
      if (!this.closed && this != EMPTY) {
         this.closed = true;
         this.includes.values().forEach(ShaderSource.CachedIncludeSource::close);
         this.fallbackIncludes.values().forEach(ShaderSource.CachedIncludeSource::close);
      }
   }

   public static LegacyShaderState build(ResourceManager manager) {
      LegacyShaderState state;
      try {
         state = new LegacyShaderState(Planner.plan(collect(manager)));
      } catch (RuntimeException | StackOverflowError e) {
         // a shader nested deeper than the translator can follow is one more that could not be translated, not a
         // reason to fail the whole reload (which would drop the packs or leave the server)
         LOGGER.error("[LegacyPackFix] shader translation failed; leaving packs untouched", e);
         return EMPTY;
      }

      if (state.plan.translates()) {
         LOGGER.info(
            "[LegacyPackFix] translated {} shader stages and {} includes from older packs {}: {}",
            state.plan.translated.size(),
            state.plan.includes.size(),
            state.plan.packs,
            new TreeSet<>(state.plan.touchedShaders.stream().map(Identifier::toString).toList())
         );
      }

      if (!state.plan.ignored.isEmpty()) {
         LOGGER.info("[LegacyPackFix] left alone {} older shader files under names 26.3 draws nothing with: {}", state.plan.ignored.size(), state.plan.ignored);
      }

      return state.active() ? state : EMPTY;
   }

   private static Map<Identifier, Planner.File> collect(ResourceManager manager) {
      Map<Identifier, Planner.File> files = new HashMap<>();
      for (Map.Entry<Identifier, List<Resource>> entry : manager.listResourceStacks("shaders", location -> true).entrySet()) {
         Identifier location = entry.getKey();
         Optional<Resource> top = manager.getResource(location);
         if (top.isEmpty()) {
            continue;
         }

         String text = read(top.get());
         files.put(location, new Planner.File(location, text, top.get().sourcePackId(), base(entry.getValue(), top.get(), text)));
      }

      // the post effect files in use: they say which vertex shader a post fragment shader runs with
      for (Map.Entry<Identifier, List<Resource>> entry : POST_EFFECTS.listMatchingResourceStacks(manager).entrySet()) {
         List<Resource> stack = entry.getValue();
         if (!stack.isEmpty()) {
            Resource top = stack.getLast();
            files.put(entry.getKey(), new Planner.File(entry.getKey(), read(top), top.sourcePackId(), Planner.isUserPack(top.sourcePackId()) ? base(stack) : null));
         }
      }

      return files;
   }

   /** The text of the highest resource that no player or server pack put there (vanilla, or a mod). */
   static @Nullable String base(List<Resource> stack) {
      for (int i = stack.size() - 1; i >= 0; i--) {
         Resource resource = stack.get(i);
         if (!Planner.isUserPack(resource.sourcePackId())) {
            return read(resource);
         }
      }

      return null;
   }

   /**
    * The same, when the top resource has been read already: most shader files come from no pack at all, and then
    * the top one is the base and need not be read a second time.
    */
   private static @Nullable String base(List<Resource> stack, Resource top, @Nullable String topText) {
      for (int i = stack.size() - 1; i >= 0; i--) {
         Resource resource = stack.get(i);
         if (!Planner.isUserPack(resource.sourcePackId())) {
            return resource.sourcePackId().equals(top.sourcePackId()) ? topText : read(resource);
         }
      }

      return null;
   }

   private static @Nullable String read(Resource resource) {
      try {
         return resource.readAllAsString();
      } catch (IOException e) {
         LOGGER.warn("[LegacyPackFix] could not read a shader from {}", resource.sourcePackId(), e);
         return null;
      }
   }
}
