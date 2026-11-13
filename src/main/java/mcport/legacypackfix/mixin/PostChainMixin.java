package mcport.legacypackfix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Set;
import mcport.legacypackfix.PostEffects;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A post effect is built when it is first used. When the effect of a player or server pack cannot be built (a pass
 * that does not compile even with the fallback shaders), the effect it replaced is built in its place: a glowing
 * outline in vanilla's look instead of none.
 */
@Mixin(PostChain.class)
public abstract class PostChainMixin {
   @WrapMethod(method = "load")
   private static PostChain handykit$loadReplacedEffect(
      PostChainConfig config,
      TextureManager textureManager,
      Set<Identifier> allowedExternalTargets,
      Identifier id,
      Projection projection,
      ProjectionMatrixBuffer projectionMatrixBuffer,
      Operation<PostChain> original
   ) {
      try {
         return original.call(config, textureManager, allowedExternalTargets, id, projection, projectionMatrixBuffer);
      } catch (Exception failure) {
         // load() declares the checked CompilationException; the wrapped call passes it on undeclared
         PostChainConfig replaced = failure instanceof ShaderManager.CompilationException ? PostEffects.replacement(id, config, failure) : null;
         if (replaced == null) {
            throw failure;
         }

         return original.call(replaced, textureManager, allowedExternalTargets, id, projection, projectionMatrixBuffer);
      }
   }
}
