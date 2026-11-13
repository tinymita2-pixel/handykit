package mcport.legacypackfix.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import mcport.legacypackfix.LegacyShaderState;
import mcport.legacypackfix.PipelineFallback;
import mcport.legacypackfix.PostEffects;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
   /** The shader set of a reload leaves here with what this reload knows about the packs' shaders. */
   @Inject(method = "loadConfigs", at = @At("RETURN"), cancellable = true)
   private static void handykit$translate(ResourceManager manager, CallbackInfoReturnable<ShaderManager.Configs> cir) {
      ShaderManager.Configs configs = cir.getReturnValue();
      LegacyShaderState state = LegacyShaderState.build(manager);
      ((LegacyShaderState.Holder)(Object)configs).handykit$setLegacyState(state);
      ShaderManager.Configs repaired;
      try {
         repaired = PostEffects.repair(configs, manager);
      } catch (RuntimeException e) {
         repaired = configs;
      }

      if (repaired != configs) {
         // the same sources and includes with other post effects; the first set is dropped unclosed, they share the includes
         ((LegacyShaderState.Holder)(Object)repaired).handykit$setLegacyState(state);
         cir.setReturnValue(repaired);
      }
   }

   @Inject(method = "compilePipelines", at = @At("HEAD"), cancellable = true)
   private static void handykit$compileWithFallback(
      GpuDevice device,
      ShaderSource shaderSource,
      List<RenderPipeline> pipelines,
      Executor taskExecutor,
      Executor reloadExecutor,
      CallbackInfoReturnable<CompletableFuture<List<CompiledRenderPipeline>>> cir
   ) {
      LegacyShaderState state = LegacyShaderState.of(shaderSource);
      if (state.active()) {
         cir.setReturnValue(PipelineFallback.compileAll(device, shaderSource, pipelines, taskExecutor, reloadExecutor, state));
      }
   }

   /** A post effect that fails on first use is not a reason to drop the packs or leave the server. */
   @WrapWithCondition(
      method = "getPostChain",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderManager;tryTriggerRecovery(Ljava/lang/Exception;)V")
   )
   private boolean handykit$keepPacks(ShaderManager self, Exception failure) {
      return !PostEffects.keepsPacks(failure);
   }
}
