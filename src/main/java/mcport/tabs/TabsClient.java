package mcport.tabs;

import com.mojang.blaze3d.platform.InputConstants;
import mcport.macro.MacroClient;
import mcport.macro.MacroScreen;
import mcport.memo.Floating;
import mcport.memo.Memo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.resources.Identifier;

/**
 * Keys and drawing for the server tabs; the switching itself is in {@link Tabs}. The things that float over the
 * game are wired up here too: the small views of watched tabs and the memo pad, which the mouse can take and move.
 */
public final class TabsClient implements ClientModInitializer {
   /** Next tab; with Shift the previous one. Works in game and in menus. */
   public static final KeyMapping NEXT = new KeyMapping("key.handykit.tab.next", InputConstants.KEY_F6, MacroClient.CATEGORY);

   @Override
   public void onInitializeClient() {
      KeyMappingHelper.registerKeyMapping(NEXT);
      KeyMappingHelper.registerKeyMapping(Memo.KEY);
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         while (NEXT.consumeClick()) {
            Tabs.requestCycle(shiftDown() ? -1 : 1);
         }

         Memo.tick(client);
      });
      ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
         if (!(screen instanceof KeyBindsScreen)) {
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
               // the macro screen is waiting for a key to bind: F6 is that key
               if (s instanceof MacroScreen macro && macro.capturingKey()) {
                  return true;
               }

               // the line under a small view has the keys while it is the one typed in
               if (WatchPanel.key(s, event)) {
                  return false;
               }

               if (NEXT.matches(event) && client.gui.screen() == s) {
                  Tabs.requestCycle(event.hasShiftDown() ? -1 : 1);
                  return false;
               }

               return true;
            });
         }

         ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, a) -> {
            if (TabBar.showsOn(s)) {
               TabBar.draw(g, mouseX, mouseY, s);
            }
         });
         ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !(TabBar.showsOn(s) && TabBar.click(s, event.x(), event.y(), event.button()))
            && !Memo.press(s, event.x(), event.y(), event.button())
            && !(s instanceof ChatScreen && WatchPanel.press(event.x(), event.y(), event.button())));
         // a small view or the memo pad is held from the press to the release; the screen sees neither that nor the drag between
         ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !(event.button() == InputConstants.MOUSE_BUTTON_LEFT && Floating.release(event.x(), event.y())));
         ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) -> !Floating.holding());
      });
      HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("handykit", "tabs"), (g, delta) -> {
         // a small view draws the HUD of its own tab through this same list: the strip and the panels are not part of that
         if (WatchPanel.drawingHud()) {
            return;
         }

         Floating.frame(Minecraft.getInstance());
         if (TabBar.showsInGame()) {
            TabBar.draw(g, -1, -1, null);
         }

         WatchPanel.draw(g, delta);
         Memo.draw(g);
      });
   }

   /** Test hook for the small views (see WatchPanel.debug); only with -Dhandykit.debug=true. */
   public static void debug(String command) {
      if (Tabs.DEBUG) {
         WatchPanel.debug(command);
      }
   }

   private static boolean shiftDown() {
      return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
   }
}
