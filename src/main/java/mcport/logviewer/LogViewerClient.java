package mcport.logviewer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookSignScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;

public final class LogViewerClient implements ClientModInitializer {
   public static final KeyMapping OPEN = new KeyMapping("key.logviewer.open", InputConstants.KEY_F7, mcport.macro.MacroClient.CATEGORY);
   /** The key went down and has not been let go: a held key repeats, and would open and close the viewer over and over. */
   private static boolean held;

   /** The open key came in (a press, or a repeat while it is held): true once per time it is pressed. */
   static boolean pressed() {
      if (held) {
         return false;
      }

      held = true;
      return true;
   }

   private static boolean down() {
      try {
         InputConstants.Key key = InputConstants.getKey(OPEN.saveString());
         return key.getType() == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(key.getValue());
      } catch (RuntimeException e) {
         return false;
      }
   }

   /**
    * With text being typed only a function key opens or closes the viewer; any other key the player bound it to is
    * a letter for the text field there.
    */
   static boolean typing(Screen screen) {
      if (!(screen.getFocused() instanceof EditBox)
         && !(screen instanceof ChatScreen)
         && !(screen instanceof AbstractSignEditScreen)
         && !(screen instanceof BookEditScreen)
         && !(screen instanceof BookSignScreen)) {
         return false;
      }

      String name = OPEN.saveString();
      String prefix = "key.keyboard.f";
      if (!name.startsWith(prefix) || name.length() == prefix.length()) {
         return true;
      }

      for (int i = prefix.length(); i < name.length(); i++) {
         if (!Character.isDigit(name.charAt(i))) {
            return true;
         }
      }

      return false;
   }

   @Override
   public void onInitializeClient() {
      KeyMappingHelper.registerKeyMapping(OPEN);
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         while (OPEN.consumeClick()) {
            if (client.gui.screen() == null && pressed()) {
               client.gui.setScreen(new LogScreen(null));
            }
         }

         if (held && !down()) {
            held = false;
         }
      });
      // menus swallow key mappings, so also listen inside every screen (title, pause, server list, disconnect, ...)
      ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
         if (screen instanceof LogScreen || screen instanceof KeyBindsScreen) {
            return;
         }

         ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            // the macro screen is waiting for a key to bind: F7 is that key, not "open the log"
            if (s instanceof mcport.macro.MacroScreen macro && macro.capturingKey()) {
               return true;
            }

            if (OPEN.matches(event) && client.gui.screen() == s && !typing(s)) {
               if (pressed()) {
                  client.gui.setScreen(new LogScreen(s));
               }

               return false;
            }

            return true;
         });
      });
   }
}
