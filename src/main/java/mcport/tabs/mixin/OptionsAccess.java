package mcport.tabs.mixin;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Options.class)
public interface OptionsAccess {
   @Accessor("serverRenderDistance")
   int handykit$serverRenderDistance();

   @Accessor("serverRenderDistance")
   void handykit$setServerRenderDistance(int distance);
}
