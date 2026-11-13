package mcport.macro.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw key state: pending clicks without consuming them, and the held flag without ToggleKeyMapping's toggling. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccess {
   @Accessor("clickCount")
   int handykit$clicks();

   @Accessor("clickCount")
   void handykit$setClicks(int clicks);

   @Accessor("isDown")
   void handykit$setDownRaw(boolean down);

   @Accessor("key")
   InputConstants.Key handykit$key();
}
