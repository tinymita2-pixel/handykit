package mcport.legacypackfix.mixin;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import mcport.legacypackfix.LegacyShaderState;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves translated sources wherever a shader set is read. Each set carries the state of its own reload: while a
 * reload is under way the screen still draws with the previous set, and pipelines compiled late against it must
 * not see the next reload's answers.
 */
@Mixin(ShaderManager.Configs.class)
public abstract class ShaderConfigsMixin implements LegacyShaderState.Holder {
   @Unique
   private @Nullable LegacyShaderState handykit$state;

   @Override
   public @Nullable LegacyShaderState handykit$legacyState() {
      return this.handykit$state;
   }

   @Override
   public void handykit$setLegacyState(LegacyShaderState state) {
      this.handykit$state = state;
   }

   @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
   private void handykit$translatedShader(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
      LegacyShaderState state = this.handykit$state;
      if (state != null) {
         String translated = state.shader(id, type);
         if (translated != null) {
            cir.setReturnValue(translated);
         }
      }
   }

   @Inject(method = "getInclude", at = @At("HEAD"), cancellable = true)
   private void handykit$translatedInclude(Identifier id, CallbackInfoReturnable<ShaderSource.CachedIncludeSource> cir) {
      LegacyShaderState state = this.handykit$state;
      if (state != null) {
         ShaderSource.CachedIncludeSource translated = state.include(id);
         if (translated != null) {
            cir.setReturnValue(translated);
         }
      }
   }

   @Inject(method = "close", at = @At("TAIL"))
   private void handykit$closeState(CallbackInfo ci) {
      LegacyShaderState state = this.handykit$state;
      if (state != null) {
         state.close();
      }
   }
}
