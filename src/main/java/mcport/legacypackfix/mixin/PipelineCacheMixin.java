package mcport.legacypackfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import mcport.legacypackfix.FallbackShaderSource;
import mcport.legacypackfix.LegacyShaderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lazily compiled pipelines (post effects, glint item variants, mod pipelines) get the same fallback. The hook
 * sits on the compile itself, which only runs when the cache has no answer, so a pipeline that is already compiled
 * costs nothing more than before.
 */
@Mixin(PipelineCache.class)
public abstract class PipelineCacheMixin {
   /**
    * Pipelines that compile neither with the packs' shaders nor with the shaders those replaced. The game keeps no
    * record of a failed compile and asks again on every draw; these are answered "no" without compiling twice more.
    */
   @Unique
   private @Nullable Set<RenderPipeline> handykit$failed;

   @WrapOperation(
      method = "get",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/renderpearl/api/device/GpuDevice;compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
      )
   )
   private CompletableFuture<CompiledRenderPipeline.Pending> handykit$fallback(
      GpuDevice device, RenderPipeline pipeline, ShaderSource source, Executor executor, Operation<CompletableFuture<CompiledRenderPipeline.Pending>> original
   ) {
      // the state of this cache's own shader set; the game's built-in fallback cache has none and is left alone
      LegacyShaderState state = LegacyShaderState.of(source);
      if (!state.active() || !state.touches(pipeline)) {
         return original.call(device, pipeline, source, executor);
      }

      Set<RenderPipeline> failed = this.handykit$failed;
      if (failed != null && failed.contains(pipeline)) {
         return CompletableFuture.completedFuture(CompiledRenderPipeline.Pending.NULL);
      }

      return original.call(device, pipeline, source, executor)
         // a pack shader the compiler chokes on is a pack shader that does not compile
         .exceptionally(failure -> CompiledRenderPipeline.Pending.NULL)
         .<CompiledRenderPipeline.Pending>thenApply(pending -> () -> this.handykit$finish(device, pipeline, source, executor, state, pending));
   }

   /** On the render thread, where the game finishes a compile: the pack's own result, or else the fallback's. */
   @Unique
   private @Nullable CompiledRenderPipeline handykit$finish(
      GpuDevice device, RenderPipeline pipeline, ShaderSource source, Executor executor, LegacyShaderState state, CompiledRenderPipeline.Pending pending
   ) {
      CompiledRenderPipeline compiled;
      try {
         compiled = pending.finishCompile();
      } catch (RuntimeException e) {
         compiled = null;
      }

      if (compiled != null) {
         return compiled;
      }

      CompiledRenderPipeline replaced;
      try {
         replaced = device.compilePipeline(pipeline, new FallbackShaderSource(source, state), executor).join().finishCompile();
      } catch (RuntimeException e) {
         replaced = null;
      }

      if (replaced != null) {
         state.noteFallback(pipeline.getLocation());
         return replaced;
      }

      Set<RenderPipeline> failed = this.handykit$failed;
      if (failed == null) {
         this.handykit$failed = failed = new ReferenceOpenHashSet<>();
      }

      failed.add(pipeline);
      return null;
   }

   @Inject(method = "clear", at = @At("HEAD"))
   private void handykit$forgetFailed(CallbackInfo ci) {
      this.handykit$failed = null;
   }
}
