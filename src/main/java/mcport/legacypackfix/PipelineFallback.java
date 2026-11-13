package mcport.legacypackfix;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.util.Util;

/**
 * Compiles reload pipelines with the packs' shaders, retrying with the shaders they replaced when a pipeline
 * fails. Without this one failing required pipeline aborts the reload and the game drops the whole server pack
 * (textures and models included) and the server kicks.
 */
public final class PipelineFallback {
   private PipelineFallback() {
   }

   public static CompletableFuture<List<CompiledRenderPipeline>> compileAll(
      GpuDevice device, ShaderSource source, List<RenderPipeline> pipelines, Executor taskExecutor, Executor reloadExecutor, LegacyShaderState state
   ) {
      ShaderSource fallback = new FallbackShaderSource(source, state);
      List<CompletableFuture<CompiledRenderPipeline>> futures = new ArrayList<>(pipelines.size());
      for (RenderPipeline pipeline : pipelines) {
         boolean touched = state.touches(pipeline);
         // order-independent transparency variants need OIT-aware shaders, which older packs never have
         if (state.translates(pipeline) && pipeline.getLocation().getPath().startsWith("pipeline/oit_")) {
            futures.add(compile(device, pipeline, fallback, taskExecutor, reloadExecutor));
            continue;
         }

         CompletableFuture<CompiledRenderPipeline> first = compile(device, pipeline, source, taskExecutor, reloadExecutor);
         if (touched) {
            // a pack shader the compiler chokes on is a pack shader that does not compile: the fallback takes over
            // instead of the whole reload failing
            first = first.exceptionally(failure -> null);
         }

         futures.add(first.thenCompose(compiled -> {
            if (compiled != null || !touched) {
               return CompletableFuture.completedFuture(compiled);
            }

            state.noteFallback(pipeline.getLocation());
            return compile(device, pipeline, fallback, taskExecutor, reloadExecutor);
         }));
      }

      return Util.sequence(futures);
   }

   private static CompletableFuture<CompiledRenderPipeline> compile(
      GpuDevice device, RenderPipeline pipeline, ShaderSource source, Executor taskExecutor, Executor reloadExecutor
   ) {
      return device.compilePipeline(pipeline, source, taskExecutor).thenApplyAsync(CompiledRenderPipeline.Pending::finishCompile, reloadExecutor);
   }
}
