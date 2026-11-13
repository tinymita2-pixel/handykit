package mcport.macro;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Left and right auto click. Clicks are queued on the attack and use key mappings at the start of a tick,
 * so vanilla's own key handling turns them into attacks and item uses exactly like real presses.
 */
final class AutoClicker {
   static final class Side {
      final boolean left;
      boolean on;
      double owed;
      boolean holding;

      Side(boolean left) {
         this.left = left;
      }

      MacroConfig.Clicker config() {
         return this.left ? MacroConfig.get().left : MacroConfig.get().right;
      }

      KeyMapping key(Minecraft mc) {
         return this.left ? mc.options.keyAttack : mc.options.keyUse;
      }
   }

   final Side left = new Side(true);
   final Side right = new Side(false);

   void toggle(Minecraft mc, Side side) {
      side.on = !side.on;
      // the first click goes out on the next tick, not one interval later
      side.owed = side.on ? 1 : 0;
      if (!side.on) {
         this.release(mc, side);
      }
   }

   void stopAll(Minecraft mc) {
      for (Side side : new Side[]{this.left, this.right}) {
         side.on = false;
         this.release(mc, side);
      }
   }

   private void release(Minecraft mc, Side side) {
      if (side.holding) {
         side.holding = false;
         Keys.restore(side.key(mc));
      }
   }

   void startTick(Minecraft mc) {
      if (mc.player == null) {
         if (this.left.on || this.right.on) {
            this.stopAll(mc);
         }

         return;
      }

      for (Side side : new Side[]{this.left, this.right}) {
         if (!side.on) {
            continue;
         }

         // vanilla already let go of every key when the menu opened; pick up again when it closes
         if (mc.gui.screen() != null || mc.gui.overlay() != null) {
            side.holding = false;
            side.owed = 0;
            continue;
         }

         MacroConfig.Clicker config = side.config();
         KeyMapping key = side.key(mc);
         switch (config.mode) {
            case HOLD -> {
               Keys.setDown(key, true);
               side.holding = true;
            }
            case COOLDOWN -> {
               this.release(mc, side);
               if (side.left && mc.player.getAttackStrengthScale(0.0F) >= 1.0F && Keys.clicks(key) == 0) {
                  Keys.addClicks(key, 1);
               }
            }
            default -> {
               this.release(mc, side);
               side.owed += config.cps / 20.0;
               int n = (int)side.owed;
               if (n > 0) {
                  side.owed -= n;
                  Keys.addClicks(key, Math.min(n, 20));
               }
            }
         }
      }
   }
}
