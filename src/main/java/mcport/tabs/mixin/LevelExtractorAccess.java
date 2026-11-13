package mcport.tabs.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which world a renderer is bound to. */
@Mixin(LevelExtractor.class)
public interface LevelExtractorAccess {
   @Accessor("level")
   @Nullable ClientLevel handykit$level();
}
