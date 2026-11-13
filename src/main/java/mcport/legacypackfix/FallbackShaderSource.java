package mcport.legacypackfix;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;

/** The same shader set with every file a player or server pack translated or replaced swapped back to what it replaced. */
public record FallbackShaderSource(ShaderSource delegate, LegacyShaderState state) implements ShaderSource {
   @Override
   public String getShader(Identifier id, ShaderType type) {
      if (this.state.replaces(id, type)) {
         return this.state.fallbackShader(id, type);
      }

      return this.delegate.getShader(id, type);
   }

   @Override
   public CachedIncludeSource getInclude(Identifier id) {
      CachedIncludeSource base = this.state.fallbackInclude(id);
      // an include only the pack has stays: nothing else could stand in for it
      return base != null ? base : this.delegate.getInclude(id);
   }

   @Override
   public void close() {
      // owned by the shader manager / state
   }
}
