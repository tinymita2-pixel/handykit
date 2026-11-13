package mcport.legacypackfix;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;
import org.jspecify.annotations.Nullable;

/**
 * Post effects (glowing outline, menu blur, a server's own screen effects) are loaded when first used, in the middle
 * of play. When one fails the game treats it as a broken resource pack: it drops the player's packs, or with only a
 * server pack loaded, leaves the server. An effect of a pack that cannot work here is replaced or left out instead:
 * at the reload when it names a shader nothing provides, on first use when it cannot be built.
 */
public final class PostEffects {
   private static final FileToIdConverter POST = FileToIdConverter.json("post_effect");

   /** An effect of a player or server pack that is in use, and the effect it replaced. */
   private record Replaced(WeakReference<PostChainConfig> own, PostChainConfig base, String pack) {
   }

   /** By effect id, one entry for every shader set that is still around (a reload under way, another server tab). */
   private static final Map<Identifier, List<Replaced>> REPLACED = new ConcurrentHashMap<>();

   private PostEffects() {
   }

   /**
    * What to build in place of an effect that could not be built: the effect a player or server pack replaced with
    * it, or null when it replaced none.
    */
   public static @Nullable PostChainConfig replacement(Identifier id, PostChainConfig failed, Exception failure) {
      for (Replaced entry : REPLACED.getOrDefault(id, List.of())) {
         if (entry.own.get() == failed) {
            LegacyShaderState.LOGGER.warn(
               "[LegacyPackFix] post effect {} of {} could not be built ({}); using the effect it replaced", id, entry.pack, failure.getMessage()
            );
            return entry.base;
         }
      }

      return null;
   }

   private static void remember(Identifier id, Replaced replaced) {
      REPLACED.compute(id, (key, known) -> {
         List<Replaced> entries = new ArrayList<>();
         if (known != null) {
            known.stream().filter(entry -> entry.own.get() != null).forEach(entries::add);
         }

         entries.add(replaced);
         return List.copyOf(entries);
      });
   }

   /**
    * The shader a post effect names that does not exist, or null. Packs for 1.21.6 - 1.21.8 name vertex shaders
    * (post/blur, post/sobel, ...) that 26.3 no longer has.
    */
   public static @Nullable String missingShader(PostChainConfig config, BiPredicate<Identifier, ShaderType> exists) {
      for (PostChainConfig.Pass pass : config.passes()) {
         if (!exists.test(pass.vertexShaderId(), ShaderType.VERTEX)) {
            return "vertex shader " + pass.vertexShaderId();
         }

         if (!exists.test(pass.fragmentShaderId(), ShaderType.FRAGMENT)) {
            return "fragment shader " + pass.fragmentShaderId();
         }
      }

      return null;
   }

   /** Returns the same shader set, or one where the post effects of player and server packs that cannot load are swapped out. */
   public static ShaderManager.Configs repair(ShaderManager.Configs configs, ResourceManager manager) {
      Map<Identifier, PostChainConfig> chains = null;
      BiPredicate<Identifier, ShaderType> exists = (id, type) -> configs.getShader(id, type) != null;
      for (Map.Entry<Identifier, List<Resource>> entry : POST.listMatchingResourceStacks(manager).entrySet()) {
         List<Resource> stack = entry.getValue();
         if (stack.isEmpty() || !Planner.isUserPack(stack.getLast().sourcePackId())) {
            continue;
         }

         Identifier id = POST.fileToId(entry.getKey());
         PostChainConfig config = configs.postChains().get(id);
         String problem;
         if (config == null) {
            problem = "it is not a 26.3 post effect file";
         } else {
            String missing = missingShader(config, exists);
            if (missing == null) {
               // it stays; whether its shaders compile shows on first use, and then what it replaced can still take over
               PostChainConfig base = base(stack, exists);
               if (base != null && !base.equals(config)) {
                  remember(id, new Replaced(new WeakReference<>(config), base, stack.getLast().sourcePackId()));
               }

               continue;
            }

            problem = "it names " + missing + ", which 26.3 does not have";
         }

         if (chains == null) {
            chains = new HashMap<>(configs.postChains());
         }

         PostChainConfig base = base(stack, exists);
         String pack = stack.getLast().sourcePackId();
         if (base != null) {
            chains.put(id, base);
            LegacyShaderState.LOGGER.warn("[LegacyPackFix] post effect {} of {} cannot be used ({}); using the effect it replaced", id, pack, problem);
         } else {
            chains.remove(id);
            LegacyShaderState.LOGGER.warn("[LegacyPackFix] post effect {} of {} cannot be used ({}); leaving it out", id, pack, problem);
         }
      }

      return chains == null ? configs : new ShaderManager.Configs(configs.shaderSources(), configs.includeSources(), Map.copyOf(chains));
   }

   /** The effect of the highest resource that no player or server pack put there, if it can load. */
   private static @Nullable PostChainConfig base(List<Resource> stack, BiPredicate<Identifier, ShaderType> exists) {
      for (int i = stack.size() - 1; i >= 0; i--) {
         Resource resource = stack.get(i);
         if (Planner.isUserPack(resource.sourcePackId())) {
            continue;
         }

         try (Reader reader = resource.openAsReader()) {
            JsonElement json = StrictJsonParser.parse(reader);
            PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(JsonSyntaxException::new);
            return missingShader(config, exists) == null ? config : null;
         } catch (IOException | JsonParseException e) {
            return null;
         }
      }

      return null;
   }

   /**
    * A post effect failed while player or server packs are loaded: say so and go on without the effect. Dropping
    * every pack, or leaving the server, would not bring the effect back.
    */
   public static boolean keepsPacks(Exception failure) {
      Minecraft mc = Minecraft.getInstance();
      // a tab's server packs are in that tab's own resource set, not among the game's
      boolean userPacks = mc != null && (mcport.tabs.Assets.serverPacksInPlace() || mc.getResourceManager().listPacks().anyMatch(pack -> Planner.isUserPack(pack.packId())));
      if (userPacks) {
         LegacyShaderState.LOGGER.warn("[LegacyPackFix] a post effect could not be loaded ({}); it stays off, the resource packs stay on", failure.getMessage());
      }

      return userPacks;
   }
}
