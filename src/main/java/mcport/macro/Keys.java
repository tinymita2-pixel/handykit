package mcport.macro;

import com.mojang.blaze3d.platform.InputConstants;
import mcport.macro.mixin.KeyMappingAccess;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Raw key state helpers shared by the clicker and the recorder. */
final class Keys {
   private Keys() {
   }

   static KeyMappingAccess raw(KeyMapping key) {
      return (KeyMappingAccess)key;
   }

   static int clicks(KeyMapping key) {
      return raw(key).handykit$clicks();
   }

   static void addClicks(KeyMapping key, int n) {
      raw(key).handykit$setClicks(raw(key).handykit$clicks() + n);
   }

   static void setClicks(KeyMapping key, int n) {
      raw(key).handykit$setClicks(n);
   }

   static void setDown(KeyMapping key, boolean down) {
      raw(key).handykit$setDownRaw(down);
   }

   /** Whether the key or button bound to this mapping is held right now as far as the game goes: with a menu open the keys are the menu's. */
   static boolean physicallyDown(KeyMapping key) {
      return Minecraft.getInstance().gui.screen() == null && held(key);
   }

   /** Whether the key or button bound to this mapping is physically held right now, whatever is on screen. */
   static boolean held(KeyMapping key) {
      InputConstants.Key bound = raw(key).handykit$key();
      if (bound == null || bound == InputConstants.UNKNOWN) {
         return false;
      }

      Minecraft mc = Minecraft.getInstance();
      if (bound.getType() == InputConstants.Type.MOUSE) {
         return switch (bound.getValue()) {
            case 1 -> mc.mouseHandler.isLeftPressed();
            case 2 -> mc.mouseHandler.isMiddlePressed();
            case 3 -> mc.mouseHandler.isRightPressed();
            default -> false;
         };
      }

      return bound.getType() == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(bound.getValue());
   }

   /** Hands the key back to the player: drops pending clicks and matches the held flag to the real key. */
   static void restore(KeyMapping key) {
      setClicks(key, 0);
      setDown(key, physicallyDown(key));
   }
}
