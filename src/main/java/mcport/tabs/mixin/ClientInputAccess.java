package mcport.tabs.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets one input object carry on where another stopped (a tab changing sides of the screen while it is played back). */
@Mixin(ClientInput.class)
public interface ClientInputAccess {
   @Accessor("moveVector")
   void handykit$setMoveVector(Vec2 vector);
}
