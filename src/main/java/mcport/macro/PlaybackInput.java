package mcport.macro;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Movement input of a player whose tab is behind the screen while a recording plays there: the recorded keys of
 * this tick instead of the keyboard (which belongs to the tab on screen). Bits as in Recorder.keys.
 */
final class PlaybackInput extends ClientInput {
   int mask;

   @Override
   public void tick() {
      int m = this.mask;
      this.keyPresses = new Input((m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0, (m & 16) != 0, (m & 32) != 0, (m & 64) != 0);
      float forward = impulse(this.keyPresses.forward(), this.keyPresses.backward());
      float left = impulse(this.keyPresses.left(), this.keyPresses.right());
      this.moveVector = new Vec2(left, forward).normalized();
   }

   private static float impulse(boolean positive, boolean negative) {
      return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
   }
}
