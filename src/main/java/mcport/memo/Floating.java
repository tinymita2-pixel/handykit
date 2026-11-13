package mcport.memo;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Holding something that floats over the game (a small view of a tab, the memo pad) with the mouse.
 *
 * <p>There is a cursor over these only while a screen is open (the chat line, the memo's editor). The press comes
 * from that screen's mouse event; from then on the cursor is read once a frame, and the thing held is told where it
 * is as soon as it has left the place of the press by a few pixels. Letting go without that much movement is a
 * plain click. One thing is held at a time.
 */
public final class Floating {
   /** What became of a hold. */
   public enum End {
      /** Let go where it was pressed. */
      CLICK,
      /** Let go after it was moved. */
      DROP,
      /** The screen it was pressed on went away before the button was let go: nothing is to be done. */
      GONE
   }

   public interface Held {
      /** The cursor has left the place of the press and is here now; every frame from then on. */
      void dragged(double mx, double my);

      void let(double mx, double my, End how);
   }

   /** The cursor may wander this far (GUI pixels) and it is still a click. */
   private static final double SLACK = 3.0;
   private static @Nullable Held held;
   private static @Nullable Screen screen;
   private static double pressX;
   private static double pressY;
   private static boolean moved;
   private static @Nullable Field activeButton;
   private static boolean looked;

   private Floating() {
   }

   public static boolean holding() {
      return held != null;
   }

   /** The mouse button went down on this thing, on the screen that is open now. */
   public static void press(Held thing, double mx, double my) {
      held = thing;
      screen = Minecraft.getInstance().gui.screen();
      pressX = mx;
      pressY = my;
      moved = false;
   }

   /** Once a frame. */
   public static void frame(Minecraft mc) {
      Held now = held;
      if (now == null) {
         return;
      }

      double mx = mc.mouseHandler.getScaledXPos(mc.getWindow());
      double my = mc.mouseHandler.getScaledYPos(mc.getWindow());
      if (mc.gui.screen() != screen) {
         end(mx, my, moved ? End.DROP : End.GONE);
         return;
      }

      if (!buttonDown(mc)) {
         // the release never reached the screen
         end(mx, my, moved ? End.DROP : End.CLICK);
         return;
      }

      if (!moved && (Math.abs(mx - pressX) > SLACK || Math.abs(my - pressY) > SLACK)) {
         moved = true;
      }

      if (moved) {
         now.dragged(mx, my);
      }
   }

   /** The mouse button was let go on a screen. True when something was being held: the release was its. */
   public static boolean release(double mx, double my) {
      if (held == null) {
         return false;
      }

      end(mx, my, moved ? End.DROP : End.CLICK);
      return true;
   }

   private static void end(double mx, double my, End how) {
      Held was = held;
      held = null;
      screen = null;
      if (was != null) {
         was.let(how == End.CLICK ? pressX : mx, how == End.CLICK ? pressY : my, how);
      }
   }

   /** For test scripts, which cannot keep a button down: what is held is moved to this place and let go there. */
   public static void test(double mx, double my) {
      Held now = held;
      if (now != null) {
         moved = true;
         now.dragged(mx, my);
         end(mx, my, End.DROP);
      }
   }

   /**
    * Is a mouse button still down? With a screen open the game keeps that only in a private field of its mouse
    * handler; when the field cannot be read the answer is yes, and the release event alone ends the hold.
    */
   private static boolean buttonDown(Minecraft mc) {
      if (!looked) {
         looked = true;
         try {
            Field field = MouseHandler.class.getDeclaredField("activeButton");
            field.setAccessible(true);
            activeButton = field;
         } catch (ReflectiveOperationException | RuntimeException e) {
            activeButton = null;
         }
      }

      Field field = activeButton;
      if (field == null) {
         return true;
      }

      try {
         return field.get(mc.mouseHandler) != null;
      } catch (IllegalAccessException e) {
         return true;
      }
   }
}
