package mcport.memo;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import mcport.macro.MacroClient;
import mcport.macro.MacroConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The memo pad: one note that floats over the game, turned on and off with a key.
 *
 * <p>It is drawn with the HUD (in the game and under the chat line). With the chat cursor it can be taken by its
 * handle (or its body) and moved, pulled larger or smaller by its lower right corner, and a click on its body opens
 * the editor ({@link MemoScreen}), which is the same pad with a text box in it. Text, place and size are kept in the
 * shared settings.
 */
public final class Memo {
   public static final KeyMapping KEY = new KeyMapping("key.handykit.memo", InputConstants.KEY_N, MacroClient.CATEGORY);
   /** The strip on top the pad is held by. */
   static final int HANDLE = 6;
   /** The text box of the editor keeps its text this far from its edges; the pad draws its text in the same place. */
   private static final int INSET = 4;
   private static final int LINE = 9;
   private static final int CORNER = 7;
   private static final int MIN_W = 80;
   private static final int MIN_H = 40;

   private enum Part {
      NONE,
      HANDLE,
      BODY,
      CORNER
   }

   private static boolean dirty;
   private static boolean wantEditor;
   private static @Nullable String wrappedText;
   private static int wrappedWidth;
   private static List<FormattedCharSequence> wrapped = List.of();

   private Memo() {
   }

   static String text() {
      return MacroConfig.base().memoText;
   }

   /** The editor's text changed. Written out when the editor closes. */
   static void typed(String value) {
      MacroConfig shared = MacroConfig.base();
      if (!value.equals(shared.memoText)) {
         shared.memoText = value;
         dirty = true;
      }
   }

   static void save() {
      if (dirty) {
         dirty = false;
         MacroConfig.base().save();
      }
   }

   /** Every client tick: the key, and an editor that was asked for from inside a mouse event. */
   public static void tick(Minecraft mc) {
      while (KEY.consumeClick()) {
         toggle(mc);
      }

      if (wantEditor) {
         wantEditor = false;
         if (MacroConfig.base().memoOn && mc.level != null && (mc.gui.screen() == null || mc.gui.screen() instanceof ChatScreen)) {
            mc.gui.setScreen(new MemoScreen());
         }
      }
   }

   private static void toggle(Minecraft mc) {
      MacroConfig shared = MacroConfig.base();
      shared.memoOn = !shared.memoOn;
      shared.save();
      if (shared.memoOn && shared.memoText.isBlank() && mc.level != null && mc.gui.screen() == null) {
         // an empty pad is there to be written on
         mc.gui.setScreen(new MemoScreen());
      }
   }

   private static boolean shown(Minecraft mc) {
      Screen screen = mc.gui.screen();
      return MacroConfig.base().memoOn && mc.level != null && mc.player != null && mc.gui.overlay() == null && !mc.getDebugOverlay().showDebugScreen()
         && (screen == null || screen instanceof ChatScreen || screen instanceof MemoScreen);
   }

   /** Left, top, width and height of the pad in GUI pixels, inside the window whatever its size is now. */
   static int[] rect(Minecraft mc) {
      MacroConfig shared = MacroConfig.base();
      int gw = mc.getWindow().getGuiScaledWidth();
      int gh = mc.getWindow().getGuiScaledHeight();
      int w = Math.clamp(shared.memoSize[0], MIN_W, Math.max(MIN_W, gw - 2));
      int h = Math.clamp(shared.memoSize[1], MIN_H, Math.max(MIN_H, gh - 2));
      int x = Math.clamp(gw - shared.memoPlace[0] - w, 1, Math.max(1, gw - w - 1));
      int y = Math.clamp(gh - shared.memoPlace[1] - h, 1, Math.max(1, gh - h - 1));
      return new int[]{x, y, w, h};
   }

   private static Part part(int[] r, double mx, double my) {
      if (mx < r[0] - 1 || my < r[1] - 1 || mx >= r[0] + r[2] + 1 || my >= r[1] + r[3] + 1) {
         return Part.NONE;
      }

      if (mx >= r[0] + r[2] - CORNER && my >= r[1] + r[3] - CORNER) {
         return Part.CORNER;
      }

      return my < r[1] + HANDLE ? Part.HANDLE : Part.BODY;
   }

   /** Is this point on the pad (the editor closes on a click anywhere else)? */
   static boolean covers(Minecraft mc, double mx, double my) {
      return part(rect(mc), mx, my) != Part.NONE;
   }

   /**
    * The mouse button went down on a screen. True when the pad took it: by its handle or corner anywhere, by its
    * body on the chat screen (in the editor the body is the text box, which keeps its own clicks).
    */
   public static boolean press(@Nullable Screen screen, double mx, double my, int button) {
      Minecraft mc = Minecraft.getInstance();
      if (button != InputConstants.MOUSE_BUTTON_LEFT || !shown(mc) || Floating.holding()) {
         return false;
      }

      int[] at = rect(mc);
      Part part = part(at, mx, my);
      boolean editing = screen instanceof MemoScreen;
      if (part == Part.NONE || part == Part.BODY && editing) {
         return false;
      }

      Floating.press(new Floating.Held() {
         @Override
         public void dragged(double cx, double cy) {
            place(mc, at, part == Part.CORNER, (int)Math.round(cx - mx), (int)Math.round(cy - my));
         }

         @Override
         public void let(double cx, double cy, Floating.End how) {
            if (how == Floating.End.CLICK) {
               if (part == Part.BODY) {
                  // opened on the next tick: this is the inside of the chat screen's mouse event
                  wantEditor = true;
               }

               return;
            }

            if (how == Floating.End.DROP) {
               place(mc, at, part == Part.CORNER, (int)Math.round(cx - mx), (int)Math.round(cy - my));
            }

            MacroConfig.base().save();
            if (mc.gui.screen() instanceof MemoScreen editor) {
               editor.refit();
            }
         }
      }, mx, my);
      return true;
   }

   /** Moves the pad, or pulls its lower right corner, by this much from where it was when it was taken. */
   private static void place(Minecraft mc, int[] at, boolean resize, int dx, int dy) {
      MacroConfig shared = MacroConfig.base();
      int gw = mc.getWindow().getGuiScaledWidth();
      int gh = mc.getWindow().getGuiScaledHeight();
      int x = at[0];
      int y = at[1];
      int w = at[2];
      int h = at[3];
      if (resize) {
         w = Math.clamp(w + dx, MIN_W, Math.max(MIN_W, gw - x - 1));
         h = Math.clamp(h + dy, MIN_H, Math.max(MIN_H, gh - y - 1));
         shared.memoSize = new int[]{w, h};
      } else {
         x = Math.clamp(x + dx, 1, Math.max(1, gw - w - 1));
         y = Math.clamp(y + dy, 1, Math.max(1, gh - h - 1));
      }

      shared.memoPlace = new int[]{gw - (x + w), gh - (y + h)};
   }

   /** With the HUD, after the small views. */
   public static void draw(GuiGraphicsExtractor g) {
      Minecraft mc = Minecraft.getInstance();
      if (!shown(mc)) {
         return;
      }

      int[] r = rect(mc);
      int x = r[0];
      int y = r[1];
      int w = r[2];
      int h = r[3];
      Screen screen = mc.gui.screen();
      // with a cursor it can be taken: the handle and the corner are brighter then
      boolean cursor = screen != null;
      g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xC0101010);
      g.fill(x, y, x + w, y + HANDLE - 1, cursor ? 0xFF707070 : 0xFF3C3C3C);
      // the corner it is pulled larger or smaller by: a small triangle
      int mark = cursor ? 0xFFB0B0B0 : 0xFF505050;
      for (int row = 1; row <= 5; row++) {
         g.fill(x + w - row, y + h - 6 + row, x + w, y + h - 5 + row, mark);
      }

      if (screen instanceof MemoScreen) {
         // the editor's text box shows the text
         return;
      }

      String text = text();
      int width = w - 2 * INSET;
      if (!text.equals(wrappedText) || width != wrappedWidth) {
         wrappedText = text;
         wrappedWidth = width;
         wrapped = mc.font.split(Component.literal(text), width);
      }

      g.enableScissor(x + 1, y + HANDLE + 1, x + w - 1, y + h - 1);
      int row = y + HANDLE + INSET;
      for (FormattedCharSequence line : wrapped) {
         if (row >= y + h) {
            break;
         }

         g.text(mc.font, line, x + INSET, row, 0xFFE0E0E0, false);
         row += LINE;
      }

      g.disableScissor();
   }

   /**
    * Test hook (scripts cannot keep a mouse button down): "on" / "off", "text <text>", "edit", "move <dx> <dy>" and
    * "size <dx> <dy>" (takes the handle or the corner and lets go that far away), "state".
    */
   public static void debug(String command) {
      if (!Boolean.getBoolean("handykit.debug")) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      MacroConfig shared = MacroConfig.base();
      String[] p = command.trim().split(" ", 2);
      switch (p[0]) {
         case "on" -> shared.memoOn = true;
         case "off" -> shared.memoOn = false;
         case "text" -> shared.memoText = p.length > 1 ? p[1].replace("\\n", "\n") : "";
         case "edit" -> wantEditor = true;
         case "move", "size" -> {
            String[] by = p[1].split(" ");
            int[] r = rect(mc);
            double mx = p[0].equals("move") ? r[0] + r[2] / 2.0 : r[0] + r[2] - 2;
            double my = p[0].equals("move") ? r[1] + 2 : r[1] + r[3] - 2;
            if (press(mc.gui.screen(), mx, my, InputConstants.MOUSE_BUTTON_LEFT)) {
               Floating.test(mx + Integer.parseInt(by[0]), my + Integer.parseInt(by[1]));
            }
         }
         default -> {
         }
      }

      int[] r = rect(mc);
      MacroConfig.LOGGER.info("[memo] {} -> on={} at {},{} size {}x{} text={} screen={}", command, shared.memoOn, r[0], r[1], r[2], r[3],
         shared.memoText.replace("\n", "\\n"), mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
   }
}
