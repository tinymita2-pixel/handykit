package mcport.tabs;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import mcport.tabs.mixin.MinecraftAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The strip of server tabs along the top edge, like browser tabs: one per open server, then "+". On the server
 * list its right end names the account the next tab logs in with. A right click on a tab opens its menu; resting
 * the mouse on a tab shows how its player is doing.
 */
public final class TabBar {
   public static final int HEIGHT = 14;
   private static final int MAX_W = 118;
   private static final int MIN_W = 28;
   private static final int PLUS_W = 14;
   private static final int GAP = 1;
   private static final int LEFT = 2;
   private static final int CLOSE_W = 11;
   private static final int ACCOUNT_COLOR = 0xFF8FC0FF;
   private static final int ITEM_H = 12;
   private static final int MENU_W = 132;
   private static final float[] VOLUMES = {1.0F, 0.7F, 0.4F, 0.2F};

   private record Slot(@Nullable Tab tab, int x, int w) {
      boolean has(double mx, double my) {
         return mx >= this.x && mx < this.x + this.w && my >= 0 && my < HEIGHT;
      }
   }

   private enum Item {
      MUTE,
      VOLUME,
      RENAME,
      DUPLICATE,
      LINK,
      WATCH,
      PACKS,
      AFTER_JOIN,
      CLOSE
   }

   /** The open tab menu: whose, where, and on which screen (another screen means it is gone). */
   private record Menu(Tab tab, int x, Screen screen) {
   }

   private static @Nullable Menu menu;

   private TabBar() {
   }

   /** Menus with a free top edge and a mouse cursor. */
   public static boolean showsOn(Screen screen) {
      if (screen instanceof JoinMultiplayerScreen) {
         // the account for the next tab is chosen here, also before the first server is open
         return true;
      }

      if (Tabs.tabs().isEmpty()) {
         return false;
      }

      if (Minecraft.getInstance().level != null) {
         return screen instanceof PauseScreen || screen instanceof ChatScreen || screen instanceof AbstractContainerScreen<?> || screen instanceof DeathScreen;
      }

      return screen instanceof TitleScreen || screen instanceof DisconnectedScreen || screen instanceof WaitingScreen;
   }

   /** In game without a menu the strip only shows once there is something to switch to. */
   public static boolean showsInGame() {
      Minecraft mc = Minecraft.getInstance();
      return mc.gui.screen() == null && Tabs.tabs().size() >= 2;
   }

   private static boolean picksAccount(@Nullable Screen screen) {
      return screen instanceof JoinMultiplayerScreen;
   }

   private static String accountLabel(Minecraft mc) {
      Accounts.Account next = Accounts.next();
      return next != null ? next.name : ((MinecraftAccess)mc).handykit$launcherUser().getName();
   }

   /** x and width of the account chip at the right end. */
   private static int[] chip(Minecraft mc) {
      int w = Math.min(120, mc.font.width(accountLabel(mc)) + 18);
      return new int[]{mc.getWindow().getGuiScaledWidth() - LEFT - w, w};
   }

   private static List<Slot> layout(Minecraft mc, boolean plus, boolean chip) {
      List<Tab> tabs = Tabs.tabs();
      List<Slot> slots = new ArrayList<>(tabs.size() + 1);
      int n = tabs.size();
      plus &= n > 0;
      int room = mc.getWindow().getGuiScaledWidth() - LEFT * 2 - (plus ? PLUS_W + GAP : 0) - (chip ? chip(mc)[1] + 4 : 0);
      int w = n == 0 ? 0 : Math.clamp((room - GAP * (n - 1)) / n, MIN_W, MAX_W);
      int x = LEFT;
      for (Tab tab : tabs) {
         slots.add(new Slot(tab, x, w));
         x += w + GAP;
      }

      if (plus) {
         slots.add(new Slot(null, x, PLUS_W));
      }

      return slots;
   }

   private static List<Item> items(Tab tab) {
      List<Item> items = new ArrayList<>();
      if (!tab.closed) {
         items.add(Item.MUTE);
         items.add(Item.VOLUME);
         items.add(Item.RENAME);
         if (!tab.local) {
            items.add(Item.DUPLICATE);
            items.add(Item.LINK);
            items.add(Item.WATCH);

            items.add(Item.AFTER_JOIN);
         }
      }

      items.add(Item.CLOSE);
      return items;
   }

   private static Component label(Item item, Tab tab) {
      String key = "handykit.tabs.menu." + item.name().toLowerCase(java.util.Locale.ROOT);
      return item == Item.VOLUME ? Component.translatable(key, Math.round(tab.volume * 100)) : Component.translatable(key);
   }

   private static boolean checked(Item item, Tab tab) {
      return switch (item) {
         case MUTE -> tab.muted;
         case LINK -> tab.linked;
         case WATCH -> tab.watch;
         default -> false;
      };
   }

   /** screen: the menu the strip is drawn on (it can be clicked there), or null over the game itself. */
   public static void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, @Nullable Screen screen) {
      Minecraft mc = Minecraft.getInstance();
      Font font = mc.font;
      boolean interactive = screen != null;
      boolean chip = picksAccount(screen);
      Tab active = Tabs.active();
      Tab hovered = null;
      int hoveredX = 0;
      Menu open = menu != null && menu.screen() == screen && Tabs.tabs().contains(menu.tab()) ? menu : null;
      if (open == null) {
         menu = null;
      }

      boolean pulse = System.currentTimeMillis() / 400 % 2 == 0;
      for (Slot slot : layout(mc, interactive, chip)) {
         boolean hover = interactive && open == null && slot.has(mouseX, mouseY);
         int x = slot.x();
         int w = slot.w();
         Tab tab = slot.tab();
         if (tab == null) {
            g.fill(x, 0, x + w, HEIGHT, hover ? 0xD0585858 : 0x90181818);
            g.centeredText(font, "+", x + w / 2, 3, 0xFFFFFFFF);
            continue;
         }

         boolean on = tab == active;
         g.fill(x, 0, x + w, HEIGHT, on ? 0xE0484848 : tab.alert && pulse ? 0xD0705020 : hover ? 0xD0383838 : 0x90101010);
         if (on) {
            g.fill(x, HEIGHT - 1, x + w, HEIGHT, 0xFFFFFFFF);
         }

         if (tab.linked) {
            // typed here, typed in every tab with this line
            g.fill(x, 0, x + w, 1, 0xFF40D0E0);
         }

         int right = x + w - 3;
         if (hover) {
            hovered = tab;
            hoveredX = x;
            boolean onClose = mouseX >= x + w - CLOSE_W;
            g.text(font, "✕", x + w - CLOSE_W + 2, 3, onClose ? 0xFFFF6060 : 0xFFA0A0A0, false);
            right = x + w - CLOSE_W;
         } else if (tab.asks() && !on) {
            // a question waits in this tab
            int cw = font.width("?");
            g.text(font, "?", right - cw, 3, 0xFFFFD040, false);
            right -= cw + 3;
         } else if (tab.unread > 0 && !on && !tab.closed) {
            String count = tab.unread > 99 ? "99+" : Integer.toString(tab.unread);
            int cw = font.width(count);
            g.text(font, count, right - cw, 3, tab.alert ? 0xFFFF9040 : 0xFFFFD040, false);
            right -= cw + 3;
         }

         int left = x + 3;
         LocalPlayer player = tab.closed ? null : Tabs.playerOf(tab);
         if (player != null && w >= 48) {
            // who plays here
            PlayerFaceExtractor.extractRenderState(g, player.getSkin(), left, 3, 8);
            left += 10;
         }

         if (!tab.closed && mcport.macro.MacroClient.playing(tab)) {
            // a recording is playing in this tab
            g.text(font, "▶", left, 3, 0xFF50E070, false);
            left += font.width("▶") + 2;
         }

         if (tab.muted || tab.volume < 1.0F) {
            int nw = font.width("♪");
            g.text(font, "♪", left, 3, tab.muted ? 0xFF806060 : 0xFF909090, false);
            if (tab.muted) {
               g.fill(left - 1, 6, left + nw + 1, 7, 0xFFFF5050);
            }

            left += nw + 2;
         }

         String title = tab.transferring ? tab.title() + " …" : tab.title();
         String account = tab.accountName();
         int room = Math.max(0, right - left);
         // the account gets its place first: two tabs of one server differ only by it
         String name = account == null ? "" : font.plainSubstrByWidth(account, Math.max(0, room * 3 / 5));
         int nameW = name.isEmpty() ? 0 : font.width(name) + 4;
         String label = font.plainSubstrByWidth(title, Math.max(0, room - nameW));
         int color = tab.closed ? 0xFFFF7070 : on ? 0xFFFFFFFF : tab.alert ? 0xFFFFB060 : 0xFFB8B8B8;
         g.text(font, label, left, 3, color, false);
         int lw = font.width(label);
         if (tab.closed) {
            g.fill(left, 7, left + lw, 8, 0xFFFF7070);
         }

         if (!name.isEmpty()) {
            g.text(font, name, left + lw + 4, 3, tab.closed ? 0xFFB06060 : ACCOUNT_COLOR, false);
         }
      }

      if (chip) {
         int[] at = chip(mc);
         boolean hover = mouseX >= at[0] && mouseX < at[0] + at[1] && mouseY >= 0 && mouseY < HEIGHT;
         boolean other = Accounts.next() != null;
         g.fill(at[0], 0, at[0] + at[1], HEIGHT, hover ? 0xD0585858 : 0x90181818);
         String label = font.plainSubstrByWidth(accountLabel(mc), at[1] - 16);
         g.text(font, label, at[0] + 4, 3, other ? ACCOUNT_COLOR : 0xFFE0E0E0, false);
         g.text(font, "▾", at[0] + at[1] - 9, 3, 0xFFA0A0A0, false);
      }

      float loading = Assets.busy(Tabs.active()) ? 1.0F : -1.0F;
      if (loading >= 0.0F) {
         // resource packs are loading behind the game
         int width = mc.getWindow().getGuiScaledWidth();
         g.fill(0, HEIGHT, width, HEIGHT + 1, 0x90101010);
         g.fill(0, HEIGHT, Math.round(width * Math.clamp(loading, 0.02F, 1.0F)), HEIGHT + 1, 0xFF40D0E0);
      }

      if (open != null) {
         drawMenu(g, font, open, mouseX, mouseY);
      } else if (hovered != null) {
         drawPreview(g, mc, font, hovered, hoveredX);
      }
   }

   private static int menuX(Minecraft mc, Menu open) {
      return Math.clamp(open.x(), LEFT, Math.max(LEFT, mc.getWindow().getGuiScaledWidth() - MENU_W - LEFT));
   }

   private static void drawMenu(GuiGraphicsExtractor g, Font font, Menu open, int mouseX, int mouseY) {
      Minecraft mc = Minecraft.getInstance();
      List<Item> items = items(open.tab());
      int x = menuX(mc, open);
      int y = HEIGHT + 1;
      g.fill(x, y, x + MENU_W, y + items.size() * ITEM_H + 2, 0xF0181818);
      for (int i = 0; i < items.size(); i++) {
         Item item = items.get(i);
         int top = y + 1 + i * ITEM_H;
         boolean hover = mouseX >= x && mouseX < x + MENU_W && mouseY >= top && mouseY < top + ITEM_H;
         if (hover) {
            g.fill(x + 1, top, x + MENU_W - 1, top + ITEM_H, 0xFF404040);
         }

         boolean on = checked(item, open.tab());
         g.text(font, label(item, open.tab()), x + 12, top + 2, item == Item.CLOSE ? 0xFFFF8080 : on ? 0xFFFFFFFF : 0xFFD0D0D0, false);
         if (on) {
            g.text(font, "✔", x + 3, top + 2, 0xFF50E070, false);
         }
      }
   }

   /** How the tab's player is doing, and the last lines of its chat. */
   private static void drawPreview(GuiGraphicsExtractor g, Minecraft mc, Font font, Tab tab, int x) {
      if (tab.closed) {
         Component reason = tab.closeReason;
         if (reason != null) {
            int rw = Math.min(font.width(reason), mc.getWindow().getGuiScaledWidth() - 8);
            g.fill(LEFT, HEIGHT + 1, LEFT + rw + 8, HEIGHT + 13, 0xE0181818);
            g.text(font, reason, LEFT + 4, HEIGHT + 3, 0xFFFF9090, false);
         }

         return;
      }

      List<String> lines = new ArrayList<>();
      LocalPlayer player = Tabs.playerOf(tab);
      String head = null;
      if (player != null) {
         head = String.format("♥ %d   %d, %d, %d   ▦ %d", Math.round(player.getHealth()), player.getBlockX(), player.getBlockY(), player.getBlockZ(),
            Tabs.loadedChunks(tab));
      }

      int max = 220;
      int skip = Math.max(0, tab.chat.size() - 3);
      for (String line : tab.chat) {
         if (skip-- <= 0) {
            lines.add(font.plainSubstrByWidth(line, max));
         }
      }

      if (head == null && lines.isEmpty()) {
         return;
      }

      int w = head == null ? 0 : font.width(head);
      for (String line : lines) {
         w = Math.max(w, font.width(line));
      }

      int rows = lines.size() + (head == null ? 0 : 1);
      int bx = Math.clamp(x, LEFT, Math.max(LEFT, mc.getWindow().getGuiScaledWidth() - w - 10));
      int by = HEIGHT + 1;
      g.fill(bx, by, bx + w + 8, by + rows * 10 + 4, 0xE0181818);
      int y = by + 3;
      if (head != null) {
         g.text(font, head, bx + 4, y, 0xFFFFFFFF, false);
         y += 10;
      }

      for (String line : lines) {
         g.text(font, line, bx + 4, y, 0xFFB0B0B0, false);
         y += 10;
      }
   }

   /** Returns true when the click was on the strip or its menu (and was acted on). */
   public static boolean click(Screen screen, double mx, double my, int button) {
      Minecraft mc = Minecraft.getInstance();
      Menu open = menu != null && menu.screen() == screen ? menu : null;
      if (open != null) {
         menu = null;
         List<Item> items = items(open.tab());
         int x = menuX(mc, open);
         int row = (int)Math.floor((my - HEIGHT - 2) / ITEM_H);
         if (mx >= x && mx < x + MENU_W && my >= HEIGHT + 2 && row >= 0 && row < items.size()) {
            run(items.get(row), open);
            return true;
         }
         // a click elsewhere only closes the menu, unless it is on the strip itself
      }

      if (my < 0 || my >= HEIGHT) {
         return open != null;
      }

      boolean chip = picksAccount(screen);
      if (chip) {
         int[] at = chip(mc);
         if (mx >= at[0] && mx < at[0] + at[1]) {
            mc.gui.setScreen(new AccountScreen(screen));
            return true;
         }
      }

      for (Slot slot : layout(mc, true, chip)) {
         if (!slot.has(mx, my)) {
            continue;
         }

         Tab tab = slot.tab();
         if (tab == null) {
            Tabs.requestNew();
         } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            menu = new Menu(tab, slot.x(), screen);
         } else if (tab.closed || mx >= slot.x() + slot.w() - CLOSE_W || button == InputConstants.MOUSE_BUTTON_MIDDLE) {
            // the cross, a middle click, or dismissing a tab whose server is gone
            Tabs.requestClose(tab);
         } else {
            Tabs.requestSwitch(tab);
         }

         return true;
      }

      return open != null;
   }

   private static void run(Item item, Menu open) {
      Tab tab = open.tab();
      switch (item) {
         case MUTE -> {
            tab.muted = !tab.muted;
            menu = open;
         }
         case VOLUME -> {
            int next = 0;
            for (int i = 0; i < VOLUMES.length; i++) {
               if (Math.abs(VOLUMES[i] - tab.volume) < 0.01F) {
                  next = (i + 1) % VOLUMES.length;
               }
            }

            tab.volume = VOLUMES[next];
            menu = open;
         }
         case RENAME -> Tabs.requestRename(tab);
         case DUPLICATE -> Tabs.requestDuplicate(tab);
         case LINK -> {
            tab.linked = !tab.linked;
            menu = open;
         }
         case WATCH -> {
            tab.watch = !tab.watch;
            Tabs.onWatchChanged(tab);
            menu = open;
         }
         case PACKS -> Tabs.requestPacks();
         case AFTER_JOIN -> Tabs.requestJoinCommands(tab);
         case CLOSE -> Tabs.requestClose(tab);
      }
   }
}
