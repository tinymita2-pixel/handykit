package mcport.tabs.mixin;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a one-shot sound plays and how loud; a sound of a tab behind the screen is moved in front of the listener. */
@Mixin(AbstractSoundInstance.class)
public interface SoundInstanceAccess {
   @Accessor("volume")
   float handykit$volume();

   @Accessor("volume")
   void handykit$setVolume(float volume);

   @Accessor("pitch")
   float handykit$pitch();

   @Accessor("x")
   double handykit$x();

   @Accessor("y")
   double handykit$y();

   @Accessor("z")
   double handykit$z();

   @Accessor("x")
   void handykit$setX(double x);

   @Accessor("y")
   void handykit$setY(double y);

   @Accessor("z")
   void handykit$setZ(double z);
}
