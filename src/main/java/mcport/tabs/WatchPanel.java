package mcport.tabs;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mcport.macro.MacroClient;
import mcport.macro.MacroConfig;
import mcport.memo.Floating;
import mcport.tabs.mixin.ChatScreenAccess;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * A tab behind the screen, kept an eye on: a small panel with what the tab would show on screen (or, while there is
 * no picture of it, a map from above: the ground around its player, other players white, monsters red, other
 * creatures yellow), its health and its last chat lines.
 *
 * <p>The panels stack in the upper right corner. With the chat cursor a panel can be taken and put anywhere (it
 * stays there for that server; dropped back onto its place in the stack it is automatic again), pulled larger or
 * smaller by its lower left corner, a click on its picture brings the tab on screen, and the two arrows in its header
 * change how many chat lines it shows. While the chat screen is open every panel has a line of its own under its
 * chat: what is typed there goes to that tab's server.
 */
final class WatchPanel {
   private static final int CELLS = 48;
   private static final int CELL = 2;
   private static final int SIZE = CELLS * CELL;
   private static final int HEADER = 11;
   /** The usual width of a panel, and the narrowest it can be pulled. */
   private static final int VIEW_W = 160;
   private static final int MIN_W = 96;
   /** The line typed in under a panel, while the chat screen is open. */
   private static final int INPUT_H = 12;
   /** The corner a panel is pulled larger or smaller by. */
   private static final int GRIP = 7;
   private static final int LINE = 10;
   /** The most chat lines a panel shows (and a tab remembers). */
   static final int MOST_LINES = 8;
   private static final int ARROW_W = 8;
   /** A panel dropped this close to its place in the stack goes back into the stack. */
   private static final int SNAP = 8;

   private WatchPanel() {
   }

   /**
    * arrows: the left edge of the "fewer" arrow; "more" is right next to it. foot: from this height down a click
    * puts the cursor into the panel's own line. input: the top of that line (-1: the chat screen is not open).
    */
   private record Panel(Tab tab, int x, int y, int w, int h, int arrows, int foot, int input) {
   }

   /** Where a panel goes this frame, and where the stack would put it. */
   private record Slot(Tab tab, @Nullable GpuTextureView picture, int w, int body, int h, int x, int y, int autoX, int autoY) {
   }

   /** The panel being pulled larger or smaller, and the width it has now. */
   private static @Nullable Tab resizing;
   private static int resizeW;

   private static int widest(Minecraft mc) {
      return Math.max(MIN_W, Math.min(640, mc.getWindow().getGuiScaledWidth() * 3 / 4));
   }

   /** How wide a tab's panel is: what the player pulled it to, or the usual. */
   private static int width(Minecraft mc, Tab tab) {
      if (tab == resizing) {
         return resizeW;
      }

      Integer chosen = MacroConfig.base().tabWatchSizes.get(key(tab));
      return Math.clamp(chosen == null ? VIEW_W : chosen, MIN_W, widest(mc));
   }

   static boolean resizing(Tab tab) {
      return tab == resizing;
   }

   /** The height of the picture drawn for a panel (pixels of the window): enough for the panel's size, never less than it used to be. */
   static int pictureHeight(Minecraft mc, Tab tab) {
      double scale = mc.getWindow().getGuiScale();
      // the window's shape, as the picture is made in it (not the picture there is now: that would chase itself)
      float aspect = (float)mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
      int body = Math.round(width(mc, tab) / aspect);
      int wanted = (int)Math.round(body * scale);
      // in steps, so that a little pulling does not make a new picture
      return Math.clamp((wanted + 89) / 90 * 90, 270, 810);
   }

   private static boolean typing(Minecraft mc) {
      return mc.gui.screen() instanceof ChatScreen;
   }

   private static int lines(Tab tab) {
      return Math.clamp(tab.watchLines >= 0 ? tab.watchLines : MacroConfig.base().tabWatchLines, 0, MOST_LINES);
   }

   /** The height of everything under the picture. */
   private static int foot(Tab tab) {
      int lines = lines(tab);
      return lines == 0 ? 1 : lines * LINE + 2;
   }

   /** Where the panels were drawn last, for clicks. */
   private static final List<Panel> PANELS = new ArrayList<>();

   // ---------------------------------------------------------------- taking a panel and putting it somewhere

   /** The panel being moved with the mouse, and where its upper left corner is wanted now. */
   private static @Nullable Tab moving;
   private static int dragX;
   private static int dragY;

   /** Places are kept by server address, and by account where a tab plays as another one. */
   private static String key(Tab tab) {
      String address = tab.addressKey();
      String account = tab.accountName();
      return (address == null ? "" : address) + (account == null ? "" : "|" + account.toLowerCase(Locale.ROOT));
   }

   private static boolean near(int x, int y, int autoX, int autoY) {
      return Math.abs(x - autoX) <= SNAP && Math.abs(y - autoY) <= SNAP;
   }

   /** Every watched tab's panel: its size, the place the player gave it, or the next place in the stack. */
   private static List<Slot> layout(Minecraft mc) {
      List<Slot> out = new ArrayList<>();
      int gw = mc.getWindow().getGuiScaledWidth();
      int gh = mc.getWindow().getGuiScaledHeight();
      int right = gw - 4;
      // under the tab strip, which is there in the game and on the chat screen alike
      Screen screen = mc.gui.screen();
      int y = TabBar.showsInGame() || screen != null && TabBar.showsOn(screen) ? TabBar.HEIGHT + 4 : 4;
      Tab active = Tabs.active();
      Map<String, int[]> places = MacroConfig.base().tabWatchPlaces;
      for (Tab tab : Tabs.tabs()) {
         if (!tab.watch || tab == active || tab.closed || tab.world.player == null || tab.world.level == null) {
            continue;
         }

         // no picture (shaders on, or not drawn yet): the map from above
         GpuTextureView picture = Pip.picture(tab);
         int w = width(mc, tab);
         int body = picture != null ? Math.round(w / Pip.aspect(tab)) : w;
         int h = HEADER + body + foot(tab) + (typing(mc) ? INPUT_H : 0);
         int autoX = right - w;
         int autoY = y;
         int px;
         int py;
         int[] place = places.get(key(tab));
         if (tab == moving && !near(dragX, dragY, autoX, autoY)) {
            px = dragX;
            py = dragY;
         } else if (tab != moving && place != null) {
            px = gw - place[0] - w;
            py = place[1];
         } else {
            px = autoX;
            py = autoY;
            y += h + 4;
         }

         // inside the window, whatever its size is now
         px = Math.clamp(px, 1, Math.max(1, gw - w - 1));
         py = Math.clamp(py, 1, Math.max(1, gh - h));
         out.add(new Slot(tab, picture, w, body, h, px, py, autoX, autoY));
      }

      return out;
   }

   private static boolean onGrip(Panel panel, double mx, double my) {
      return mx >= panel.x() - 2 && mx < panel.x() + GRIP && my >= panel.y() + panel.h() - GRIP && my < panel.y() + panel.h() + 2;
   }

   /**
    * The mouse button went down with the chat cursor. True when it was on a panel: the panel is held from now on (or
    * its corner, to pull it larger or smaller). A press on a panel's own line is the screen's: the line takes it.
    */
   static boolean press(double mx, double my, int button) {
      if (button != InputConstants.MOUSE_BUTTON_LEFT || Floating.holding()) {
         return false;
      }

      for (Panel panel : PANELS) {
         if (onGrip(panel, mx, my)) {
            Tab tab = panel.tab();
            int startW = panel.w();
            float aspect = (float)panel.w() / Math.max(1, panel.h() - HEADER - foot(tab) - (panel.input() >= 0 ? INPUT_H : 0));
            Minecraft mc = Minecraft.getInstance();
            Floating.press(new Floating.Held() {
               @Override
               public void dragged(double cx, double cy) {
                  // the right edge stays where it is: further left or further down is larger
                  int byX = startW + (int)Math.round(mx - cx);
                  int byY = startW + (int)Math.round((cy - my) * aspect);
                  int w = Math.abs(byX - startW) >= Math.abs(byY - startW) ? byX : byY;
                  resizing = tab;
                  resizeW = Math.clamp(w, MIN_W, widest(mc));
               }

               @Override
               public void let(double cx, double cy, Floating.End how) {
                  if (how == Floating.End.DROP) {
                     this.dragged(cx, cy);
                     MacroConfig shared = MacroConfig.base();
                     if (resizeW == VIEW_W) {
                        shared.tabWatchSizes.remove(key(tab));
                     } else {
                        shared.tabWatchSizes.put(key(tab), resizeW);
                     }

                     shared.save();
                  }

                  resizing = null;
               }
            }, mx, my);
            return true;
         }

         if (panel.input() >= 0 && my >= panel.input() && my < panel.input() + INPUT_H && mx >= panel.x() && mx < panel.x() + panel.w()) {
            // the screen hands it to the line under the panel
            return false;
         }

         if (mx >= panel.x() && mx < panel.x() + panel.w() && my >= panel.y() && my < panel.y() + panel.h()) {
            Floating.press(new Floating.Held() {
               @Override
               public void dragged(double cx, double cy) {
                  moving = panel.tab();
                  dragX = panel.x() + (int)Math.round(cx - mx);
                  dragY = panel.y() + (int)Math.round(cy - my);
               }

               @Override
               public void let(double cx, double cy, Floating.End how) {
                  if (how == Floating.End.CLICK) {
                     click(panel, cx, cy);
                  } else if (how == Floating.End.DROP) {
                     this.dragged(cx, cy);
                     drop(panel.tab());
                  }

                  moving = null;
               }
            }, mx, my);
            return true;
         }
      }

      return false;
   }

   /** The panel was let go: it stays where it is, or goes back into the stack when that is where it was put. */
   private static void drop(Tab tab) {
      Minecraft mc = Minecraft.getInstance();
      MacroConfig shared = MacroConfig.base();
      for (Slot slot : layout(mc)) {
         if (slot.tab() == tab) {
            if (near(dragX, dragY, slot.autoX(), slot.autoY())) {
               shared.tabWatchPlaces.remove(key(tab));
            } else {
               // from the right and the top edge: that is the corner the stack lives in
               shared.tabWatchPlaces.put(key(tab), new int[]{mc.getWindow().getGuiScaledWidth() - (slot.x() + slot.w()), slot.y()});
            }

            shared.save();
            return;
         }
      }
   }

   /** A press and release in one place. */
   private static void click(Panel panel, double mx, double my) {
      Tab tab = panel.tab();
      if (my < panel.y() + HEADER && mx >= panel.arrows() && mx < panel.arrows() + 2 * ARROW_W) {
         // the two arrows: fewer or more chat lines under the picture
         int lines = Math.clamp(lines(tab) + (mx < panel.arrows() + ARROW_W ? -1 : 1), 0, MOST_LINES);
         tab.watchLines = lines;
         MacroConfig shared = MacroConfig.base();
         shared.tabWatchLines = lines;
         shared.save();
      } else if (my >= panel.foot()) {
         // its chat lines: the cursor goes into its own line
         focus(tab);
      } else {
         Tabs.requestSwitch(tab);
      }
   }

   // ---------------------------------------------------------------- typing to a tab behind the screen

   /** The line under one panel: a text box of the chat screen, which sends to its tab's server. */
   private static final class Line extends EditBox {
      final Tab tab;
      /** Where in the tab's sent lines the arrow keys are (the size of the list: the line being written). */
      int back;

      Line(Minecraft mc, Tab tab) {
         super(mc.fontFilterFishy, 0, 0, 40, INPUT_H, Component.translatable("chat.editBox"));
         this.tab = tab;
         this.setBordered(false);
         this.setMaxLength(256);
         this.setTextColor(0xFFFFFFFF);
         this.setValue(tab.watchDraft);
         this.setResponder(value -> tab.watchDraft = value);
         this.back = tab.watchSent.size();
      }

      @Override
      public void setFocused(boolean focused) {
         super.setFocused(focused);
         if (focused) {
            // the chat screen's own line keeps its cursor otherwise: one blinking cursor, where the keys go
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen instanceof ChatScreen chat) {
               EditBox own = ((ChatScreenAccess)chat).handykit$input();
               if (own != this && own.isFocused()) {
                  own.setCanLoseFocus(true);
                  own.setFocused(false);
                  own.setCanLoseFocus(false);
               }
            }
         }
      }
   }

   /** The lines of the chat screen that is open now, by tab. */
   private static final Map<Tab, Line> LINES = new java.util.HashMap<>();
   private static @Nullable Screen linesOn;

   private static boolean canSayTo(Tab tab) {
      return !tab.closed && tab.watch && tab != Tabs.active() && tab.world.inPlay();
   }

   /** Every frame the chat screen is open: each panel has its line, where the panel is now; lines without a panel go. */
   private static void syncLines(Minecraft mc) {
      Screen screen = mc.gui.screen();
      if (!(screen instanceof ChatScreen chat)) {
         LINES.clear();
         linesOn = null;
         return;
      }

      if (linesOn != screen) {
         LINES.clear();
         linesOn = screen;
      }

      List<net.minecraft.client.gui.components.AbstractWidget> widgets = Screens.getWidgets(screen);
      for (Map.Entry<Tab, Line> entry : List.copyOf(LINES.entrySet())) {
         Line line = entry.getValue();
         boolean shown = false;
         for (Panel panel : PANELS) {
            shown |= panel.tab() == entry.getKey() && panel.input() >= 0;
         }

         // the screen was built anew (a new window size) and dropped it, or its panel is gone
         boolean dropped = !screen.children().contains(line);
         if (!shown || dropped) {
            if (line.isFocused() || screen.getFocused() == line) {
               EditBox own = ((ChatScreenAccess)chat).handykit$input();
               screen.setFocused(own);
               own.setFocused(true);
            }

            widgets.remove(line);
            LINES.remove(entry.getKey());
         }
      }

      for (Panel panel : PANELS) {
         if (panel.input() < 0) {
            continue;
         }

         Line line = LINES.get(panel.tab());
         if (line == null) {
            line = new Line(mc, panel.tab());
            LINES.put(panel.tab(), line);
            widgets.add(line);
         }

         // past the corner it is pulled by
         line.setX(panel.x() + GRIP + 1);
         line.setY(panel.input() + 2);
         int width = panel.w() - GRIP - 3;
         if (line.getWidth() != width) {
            line.setWidth(width);
            // the text it shows was cut for the old width: from the start again, then to where the cursor is
            int cursor = line.getCursorPosition();
            line.moveCursorToStart(false);
            line.moveCursorTo(cursor, false);
         }
      }
   }

   /** The cursor goes into the line under this tab's panel. */
   private static void focus(Tab tab) {
      Screen screen = Minecraft.getInstance().gui.screen();
      Line line = LINES.get(tab);
      if (screen instanceof ChatScreen && line != null && screen.children().contains(line)) {
         screen.setFocused(line);
         line.setFocused(true);
      }
   }

   /**
    * A key on the chat screen. True when the line under a panel has the cursor and took the key: Enter sends what is in
    * it to its tab and leaves the screen open for the next line, the arrows go through what was sent there. Esc and
    * the tab key of Handy Kit (F6) do what they always do.
    */
   static boolean key(Screen screen, KeyEvent event) {
      if (!(screen instanceof ChatScreen chat) || !(screen.getFocused() instanceof Line line) || !LINES.containsValue(line)) {
         return false;
      }

      int key = event.key();
      if (key == InputConstants.KEY_ESCAPE || TabsClient.NEXT.matches(event)) {
         return false;
      }

      if (event.isConfirmation()) {
         send(chat, line);
      } else if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
         List<String> sent = line.tab.watchSent;
         int to = Math.clamp(line.back + (key == InputConstants.KEY_UP ? -1 : 1), 0, sent.size());
         if (to != line.back) {
            line.back = to;
            line.setValue(to == sent.size() ? "" : sent.get(to));
         }
      } else if (key != InputConstants.KEY_TAB) {
         line.keyPressed(event);
      }

      return true;
   }

   /** What is in the line goes to its tab's server, as that tab's player: a chat line, or a command when it starts with /. */
   private static void send(ChatScreen chat, Line line) {
      String text = chat.normalizeChatMessage(line.getValue());
      Tab to = line.tab;
      if (text.isEmpty() || !canSayTo(to)) {
         return;
      }

      line.setValue("");
      List<String> sent = to.watchSent;
      if (sent.isEmpty() || !sent.getLast().equals(text)) {
         sent.add(text);
         if (sent.size() > 50) {
            sent.removeFirst();
         }
      }

      line.back = sent.size();
      say(to, text);
   }

   private static void say(Tab to, String text) {
      Minecraft mc = Minecraft.getInstance();
      // not the player's typing on the server on screen: not recorded, not passed on to linked tabs
      Tabs.runIn(to, () -> MacroClient.automated(() -> {
         ClientPacketListener listener = mc.getConnection();
         if (listener != null) {
            if (text.startsWith("/")) {
               listener.sendCommand(text.substring(1));
            } else {
               listener.sendChat(text);
            }
         }
      }));
   }

   // ---------------------------------------------------------------- drawing

   private static boolean hud;

   /** The HUD of a watched tab is being drawn into its small view. */
   static boolean drawingHud() {
      return hud;
   }

   static void draw(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
      Minecraft mc = Minecraft.getInstance();
      PANELS.clear();
      if (!Pip.shown(mc) || mc.getDebugOverlay().showDebugScreen()) {
         syncLines(mc);
         return;
      }

      List<Slot> slots = layout(mc);
      boolean typing = typing(mc);
      double cursorX = mc.mouseHandler.getScaledXPos(mc.getWindow());
      double cursorY = mc.mouseHandler.getScaledYPos(mc.getWindow());
      for (Slot slot : slots) {
         Tab tab = slot.tab();
         LocalPlayer player = tab.world.player;
         ClientLevel level = tab.world.level;
         if (player == null || level == null) {
            continue;
         }

         int x = slot.x();
         int y = slot.y();
         int arrows;
         if (slot.picture() != null) {
            arrows = view(g, mc.font, tab, player, slot.picture(), x, y, slot.w(), slot.body());
            hud(g, mc, tab, delta, x, y + HEADER, slot.w());
         } else {
            arrows = one(g, mc.font, tab, player, level, x, y, slot.w());
         }

         int input = -1;
         if (typing) {
            // its own line, under its chat: brighter while it is the one typed in
            input = y + HEADER + slot.body() + foot(tab);
            Line line = LINES.get(tab);
            boolean on = line != null && line.isFocused();
            g.fill(x - 1, input, x + slot.w() + 1, input + INPUT_H, on ? 0xF0303030 : 0xC0000000);
            if (on) {
               g.fill(x - 1, input + INPUT_H - 1, x + slot.w() + 1, input + INPUT_H, 0xFFFFFFFF);
            }
         }

         // the corner it is pulled larger or smaller by: a small triangle, brighter with a cursor
         int bottom = y + slot.h();
         int grip = typing ? 0xFFE0E0E0 : 0x80A0A0A0;
         for (int i = 0; i < 5; i++) {
            g.fill(x - 1, bottom - 5 + i, x + i, bottom - 4 + i, grip);
         }

         // a panel without chat lines has next to nothing under its picture: the picture's lowest rows count as well
         int foot = lines(tab) == 0 ? y + HEADER + slot.body() - 6 : y + HEADER + slot.body();
         Panel panel = new Panel(tab, x, y, slot.w(), slot.h(), arrows, foot, input);
         PANELS.add(panel);
         if (typing && (tab == resizing || onGrip(panel, cursorX, cursorY))) {
            g.requestCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.RESIZE_EW);
         }
      }

      syncLines(mc);
   }

   /** Test hook (scripts cannot keep a mouse button down): "view <n> <dx> <dy>" takes the n-th panel and lets go that far away; "state". */
   static void debug(String command) {
      Minecraft mc = Minecraft.getInstance();
      String[] p = command.trim().split(" ");
      if (p[0].equals("say") && p.length >= 3) {
         // what typing into the n-th panel's line and Enter do, without the keyboard
         int index = Integer.parseInt(p[1]) - 1;
         if (index >= 0 && index < PANELS.size()) {
            Tab tab = PANELS.get(index).tab();
            say(tab, command.trim().substring(p[0].length() + p[1].length() + 2));
            Tabs.LOGGER.info("[tabs] panels say: went to tab #{}", tab.id);
         }
      }

      if (p[0].equals("size") && p.length >= 4) {
         // takes the n-th panel by its corner and lets go that far away
         int index = Integer.parseInt(p[1]) - 1;
         if (index >= 0 && index < PANELS.size()) {
            Panel panel = PANELS.get(index);
            double mx = panel.x() + 1;
            double my = panel.y() + panel.h() - 2;
            if (press(mx, my, InputConstants.MOUSE_BUTTON_LEFT)) {
               Floating.test(mx + Integer.parseInt(p[2]), my + Integer.parseInt(p[3]));
            }
         }
      }

      if (p[0].equals("view") && p.length >= 4) {
         int index = Integer.parseInt(p[1]) - 1;
         if (index >= 0 && index < PANELS.size()) {
            Panel panel = PANELS.get(index);
            double mx = panel.x() + 3;
            double my = panel.y() + 3;
            if (press(mx, my, InputConstants.MOUSE_BUTTON_LEFT)) {
               Floating.test(mx + Integer.parseInt(p[2]), my + Integer.parseInt(p[3]));
            }
         }
      }

      StringBuilder sb = new StringBuilder();
      for (Panel panel : PANELS) {
         Line line = LINES.get(panel.tab());
         sb.append(String.format(Locale.ROOT, " #%d %d,%d %dx%d foot=%d input=%d%s", panel.tab().id, panel.x(), panel.y(), panel.w(), panel.h(), panel.foot(), panel.input(),
            line == null ? "" : " line=" + line.getX() + "," + line.getY() + (line.isFocused() ? "(typing:" + line.getValue() + ")" : "")));
      }

      Tabs.LOGGER.info("[tabs] panels {} ->{} places={} sizes={} focus={} window={}x{}", command, sb, MacroConfig.base().tabWatchPlaces.keySet(), MacroConfig.base().tabWatchSizes,
         mc.gui.screen() == null || mc.gui.screen().getFocused() == null ? "-" : mc.gui.screen().getFocused().getClass().getSimpleName(),
         mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
   }

   /**
    * The tab's own HUD (hotbar, hearts, crosshair, boss bars, chat) over its picture, at the size it has on the real
    * screen shrunk to the picture: the game's HUD code draws it, with that tab's world in Minecraft's fields.
    */
   private static void hud(GuiGraphicsExtractor g, Minecraft mc, Tab tab, net.minecraft.client.DeltaTracker delta, int x, int top, int width) {
      net.minecraft.client.gui.Hud theirs = tab.world.hud;
      if (theirs == null || hud) {
         return;
      }

      boolean hidden = mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden;
      float scale = (float)width / g.guiWidth();
      hud = true;
      g.pose().pushMatrix();
      try {
         g.pose().translate(x, top);
         g.pose().scale(scale, scale);
         g.enableScissor(0, 0, g.guiWidth(), g.guiHeight());
         try {
            // its own resource set too: its HUD sprites, its font and language, its item models
            Assets.Look look = Assets.of(tab);
            Tabs.runIn(tab, () -> Assets.during(look, () -> theirs.extractRenderState(g, delta)));
         } finally {
            g.disableScissor();
         }
      } catch (RuntimeException e) {
         // a HUD element of another mod that cannot be drawn twice in a frame: the picture stays, without the HUD
         Tabs.LOGGER.warn("The HUD of tab {} could not be drawn into its small view: {}", tab.title(), e.toString());
      } finally {
         g.pose().popMatrix();
         hud = false;
         mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden = hidden;
      }
   }

   /**
    * The frame every panel shares: the dark back, the header (name, the two arrows for the number of chat lines,
    * health) and the tab's last chat lines under the body. Returns where the arrows are.
    */
   private static int frame(GuiGraphicsExtractor g, Font font, Tab tab, LocalPlayer player, int x, int y, int width, int body) {
      int foot = foot(tab);
      g.fill(x - 1, y - 1, x + width + 1, y + HEADER + body + foot, 0xC0101010);
      String health = "♥ " + Math.round(player.getHealth());
      int hw = font.width(health);
      int arrows = x + width - hw - 5 - 2 * ARROW_W;
      g.text(font, font.plainSubstrByWidth(tab.accountName() != null ? tab.accountName() : tab.title(), arrows - x - 4), x + 2, y + 1, 0xFFFFFFFF, false);
      // clickable with the chat cursor: brighter then
      boolean cursor = Minecraft.getInstance().gui.screen() instanceof ChatScreen;
      int lines = lines(tab);
      g.text(font, "▾", arrows + 1, y + 1, lines == 0 ? 0xFF505050 : cursor ? 0xFFFFFFFF : 0xFF909090, false);
      g.text(font, "▴", arrows + ARROW_W + 1, y + 1, lines == MOST_LINES ? 0xFF505050 : cursor ? 0xFFFFFFFF : 0xFF909090, false);
      g.text(font, health, x + width - hw - 1, y + 1, player.getHealth() <= 6 ? 0xFFFF5050 : 0xFFE0E0E0, false);
      int row = y + HEADER + body + 2;
      int skip = Math.max(0, tab.chat.size() - lines);
      for (String line : tab.chat) {
         if (skip-- > 0) {
            continue;
         }

         g.text(font, font.plainSubstrByWidth(line, width - 2), x + 1, row, 0xFFB0B0B0, false);
         row += LINE;
      }

      return arrows;
   }

   /** What the tab would show on screen, small. */
   private static int view(GuiGraphicsExtractor g, Font font, Tab tab, LocalPlayer player, GpuTextureView picture, int x, int y, int width, int height) {
      int arrows = frame(g, font, tab, player, x, y, width, height);
      int top = y + HEADER;
      Shots.blit(g, picture, x, top, x + width, top + height, -1);
      return arrows;
   }

   private static int one(GuiGraphicsExtractor g, Font font, Tab tab, LocalPlayer player, ClientLevel level, int px0, int py0, int width) {
      int arrows = frame(g, font, tab, player, px0, py0, width, width);
      // the map is drawn at its own size and scaled to the panel
      g.pose().pushMatrix();
      g.pose().translate(px0, py0 + HEADER);
      g.pose().scale(width / (float)SIZE, width / (float)SIZE);
      try {
         map(g, tab, player, level);
      } finally {
         g.pose().popMatrix();
      }

      return arrows;
   }

   private static void map(GuiGraphicsExtractor g, Tab tab, LocalPlayer player, ClientLevel level) {
      int x = 0;
      int top = 0;
      int stamp = (int)(System.currentTimeMillis() / 500);
      if (tab.watchMap == null || tab.watchAge != stamp) {
         tab.watchMap = ground(level, player);
         tab.watchAge = stamp;
      }

      int[] map = tab.watchMap;
      for (int cz = 0; cz < CELLS; cz++) {
         int run = 0;
         for (int cx = 1; cx <= CELLS; cx++) {
            // one rectangle per stretch of equal colour
            if (cx == CELLS || map[cz * CELLS + cx] != map[cz * CELLS + run]) {
               g.fill(x + run * CELL, top + cz * CELL, x + cx * CELL, top + (cz + 1) * CELL, map[cz * CELLS + run]);
               run = cx;
            }
         }
      }

      double px = player.getX();
      double pz = player.getZ();
      for (Entity entity : level.entitiesForRendering()) {
         if (entity == player || entity instanceof ItemEntity) {
            continue;
         }

         int ex = (int)Math.floor(entity.getX() - px) + CELLS / 2;
         int ez = (int)Math.floor(entity.getZ() - pz) + CELLS / 2;
         if (ex < 0 || ez < 0 || ex >= CELLS || ez >= CELLS) {
            continue;
         }

         int color = entity instanceof Player ? 0xFFFFFFFF : entity instanceof Enemy ? 0xFFFF4040 : 0xFFE8D060;
         g.fill(x + ex * CELL - 1, top + ez * CELL - 1, x + ex * CELL + CELL + 1, top + ez * CELL + CELL + 1, 0xFF000000);
         g.fill(x + ex * CELL, top + ez * CELL, x + ex * CELL + CELL, top + ez * CELL + CELL, color);
      }

      // the tab's own player: a dot in the middle and a short line the way it looks
      int mx = x + SIZE / 2;
      int mz = top + SIZE / 2;
      double yaw = Math.toRadians(player.getYRot());
      for (int i = 1; i <= 5; i++) {
         int lx = mx + (int)Math.round(-Math.sin(yaw) * i);
         int lz = mz + (int)Math.round(Math.cos(yaw) * i);
         g.fill(lx, lz, lx + 1, lz + 1, 0xFF50E070);
      }

      g.fill(mx - 1, mz - 1, mx + 2, mz + 2, 0xFF50E070);
   }

   /** The colour of the top block at every cell around the player (one cell, one block), darker where it lies lower. */
   private static int[] ground(ClientLevel level, LocalPlayer player) {
      int[] out = new int[CELLS * CELLS];
      int bx = player.getBlockX() - CELLS / 2;
      int bz = player.getBlockZ() - CELLS / 2;
      int py = player.getBlockY();
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int cz = 0; cz < CELLS; cz++) {
         for (int cx = 0; cx < CELLS; cx++) {
            int wx = bx + cx;
            int wz = bz + cz;
            int color = 0xFF181818;
            LevelChunk chunk = level.getChunkSource().getChunk(wx >> 4, wz >> 4, ChunkStatus.FULL, false);
            if (chunk != null) {
               int wy = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
               pos.set(wx, wy, wz);
               BlockState state = chunk.getBlockState(pos);
               int rgb = state.getMapColor(level, pos).col;
               if (rgb != 0) {
                  float shade = Math.clamp(0.8F + (wy - py) / 48.0F, 0.5F, 1.0F);
                  int r = (int)((rgb >> 16 & 0xFF) * shade);
                  int gr = (int)((rgb >> 8 & 0xFF) * shade);
                  int b = (int)((rgb & 0xFF) * shade);
                  color = 0xFF000000 | r << 16 | gr << 8 | b;
               }
            }

            out[cz * CELLS + cx] = color;
         }
      }

      return out;
   }
}
