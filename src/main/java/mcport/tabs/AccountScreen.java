package mcport.tabs;

import com.mojang.blaze3d.platform.InputConstants;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import mcport.tabs.mixin.MinecraftAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Which account the next server tab logs in with. The first row is the account the game was started with; below it
 * the other Microsoft accounts signed in here (white) and plain names for offline-mode servers (grey). A click
 * picks a row and goes back.
 */
public final class AccountScreen extends Screen {
   private static final int ROW_H = 18;
   private static final int LIST_W = 220;
   private final @Nullable Screen parent;
   private @Nullable EditBox name;
   private int listTop;
   private int listX;

   // a Microsoft sign-in under way; lives across init() (the link confirm screen comes back here)
   private MsLogin.@Nullable DeviceCode code;
   private volatile boolean signingIn;
   private volatile boolean cancelled;
   private volatile @Nullable Component status;
   /** Where the sign-in is, under the code. */
   private volatile @Nullable Component progress;
   /** The link confirm screen is up: that is a detour and back, not leaving. */
   private boolean detour;

   /** What to do once an account was picked (a tab that waits to be opened with it). */
   private final @Nullable Runnable after;
   /** Microsoft account -> what Accounts.state said when the list was built (opening the stored sign-in costs a little). */
   private final java.util.Map<Accounts.Account, long[]> states = new java.util.IdentityHashMap<>();

   public AccountScreen(@Nullable Screen parent) {
      this(parent, null);
   }

   public AccountScreen(@Nullable Screen parent, @Nullable Runnable after) {
      super(Component.translatable("handykit.accounts.title"));
      this.parent = parent;
      this.after = after;
   }

   private void picked(Accounts.@Nullable Account account) {
      Accounts.choose(account);
      this.onClose();
      if (this.after != null) {
         this.after.run();
      }
   }

   private List<Accounts.@Nullable Account> rows() {
      List<Accounts.@Nullable Account> rows = new ArrayList<>();
      rows.add(null);
      rows.addAll(Accounts.list());
      return rows;
   }

   @Override
   protected void init() {
      this.detour = false;
      this.listX = this.width / 2 - LIST_W / 2;
      this.listTop = 44;
      this.states.clear();
      for (Accounts.Account account : Accounts.list()) {
         if (account.microsoft()) {
            this.states.put(account, Accounts.state(account));
         }
      }
      int y = this.height - 54;
      if (this.signingIn) {
         MsLogin.DeviceCode shown = this.code;
         this.addRenderableWidget(Button.builder(Component.translatable("handykit.accounts.open_page"), b -> {
            if (shown != null) {
               // test runs share the player's clipboard: leave it alone there
               if (!Boolean.getBoolean("handykit.debug")) {
                  this.minecraft.keyboardHandler.setClipboard(shown.userCode());
               }

               this.detour = true;
               ConfirmLinkScreen.confirmLinkNow(this, URI.create(shown.uri()), true);
            }
         }).bounds(this.width / 2 - 152, y, 150, 20).build()).active = shown != null;
         this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.stopSignIn()).bounds(this.width / 2 + 2, y, 100, 20).build());
      } else {
         this.addRenderableWidget(Button.builder(Component.translatable("handykit.accounts.add_microsoft"), b -> this.startSignIn())
            .bounds(this.listX, y, 108, 20)
            .build());
         this.name = new EditBox(this.font, this.listX + 112, y, LIST_W - 112 - 24, 20, Component.empty());
         this.name.setMaxLength(16);
         this.name.setHint(Component.translatable("handykit.accounts.name").withStyle(ChatFormatting.DARK_GRAY));
         this.addRenderableWidget(this.name);
         this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.addName()).bounds(this.listX + LIST_W - 20, y, 20, 20).build());
      }

      this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 75, this.height - 28, 150, 20).build());
   }

   private void addName() {
      if (this.name != null) {
         Accounts.Account account = Accounts.addOffline(this.name.getValue());
         if (account != null) {
            this.picked(account);
         }
      }
   }

   // ---------------------------------------------------------------- Microsoft sign-in

   private void startSignIn() {
      this.signingIn = true;
      this.cancelled = false;
      this.code = null;
      this.status = Component.translatable("handykit.accounts.asking");
      this.rebuildWidgets();
      Thread thread = new Thread(this::signIn, "HandyKit Account Sign-in");
      thread.setDaemon(true);
      thread.start();
   }

   private void stopSignIn() {
      this.cancelled = true;
      this.signingIn = false;
      this.code = null;
      this.status = null;
      this.rebuildWidgets();
   }

   /** Worker thread: get a code, then look every few seconds whether the player finished on Microsoft's page. */
   private void signIn() {
      try {
         MsLogin.DeviceCode issued = MsLogin.start();
         if (this.cancelled) {
            return;
         }

         this.minecraft.execute(() -> {
            if (this.signingIn && !this.cancelled) {
               this.code = issued;
               this.status = null;
               this.progress = Component.translatable("handykit.accounts.stage.waiting");
               if (this.minecraft.gui.screen() == this) {
                  this.rebuildWidgets();
               }
            }
         });
         Tabs.LOGGER.info("Microsoft sign-in: a code was issued, waiting for the player to enter it");
         int looks = 0;
         while (!this.cancelled && System.currentTimeMillis() < issued.expiresAt()) {
            Thread.sleep(issued.interval() * 1000L);
            if (this.cancelled) {
               Tabs.LOGGER.info("Microsoft sign-in: given up by the player");
               return;
            }

            if (++looks % 6 == 0) {
               Tabs.LOGGER.info("Microsoft sign-in: still waiting ({} looks)", looks);
            }

            MsLogin.Session session = MsLogin.poll(issued, stage -> {
               Tabs.LOGGER.info("Microsoft sign-in: {}", stage);
               this.progress = Component.translatable("handykit.accounts.stage." + stage);
            });
            if (session != null) {
               Tabs.LOGGER.info("Microsoft sign-in: done, the account is {}", session.name());
               Accounts.Account account = Accounts.addMicrosoft(session);
               this.minecraft.execute(() -> {
                  this.signingIn = false;
                  this.code = null;
                  this.status = null;
                  this.progress = null;
                  if (this.minecraft.gui.screen() == this) {
                     this.picked(account);
                  } else {
                     Accounts.choose(account);
                  }
               });
               return;
            }
         }

         if (!this.cancelled) {
            this.fail(Component.translatable("handykit.accounts.expired"));
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      } catch (MsLogin.LoginException e) {
         // our own key and the error name of the service; never a token
         Tabs.LOGGER.warn("Microsoft sign-in failed: {}", e.getMessage());
         this.fail(Component.translatable(e.key, e.detail));
      } catch (Exception e) {
         Tabs.LOGGER.warn("Microsoft sign-in failed: {}", e.toString());
         this.fail(Component.translatable("handykit.accounts.error.network", e.getClass().getSimpleName()));
      }
   }

   private void fail(Component why) {
      this.minecraft.execute(() -> {
         if (!this.cancelled) {
            this.signingIn = false;
            this.code = null;
            this.status = why;
            if (this.minecraft.gui.screen() == this) {
               this.rebuildWidgets();
            }
         }
      });
   }

   // ---------------------------------------------------------------- list

   private int rowAt(double mx, double my) {
      if (this.signingIn || mx < this.listX || mx >= this.listX + LIST_W || my < this.listTop) {
         return -1;
      }

      int row = (int)((my - this.listTop) / ROW_H);
      return row < this.rows().size() ? row : -1;
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      int row = this.rowAt(event.x(), event.y());
      if (row >= 0) {
         Accounts.Account account = this.rows().get(row);
         if (account != null && event.x() >= this.listX + LIST_W - 16) {
            Accounts.remove(account);
         } else {
            this.picked(account);
         }

         return true;
      }

      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if (this.name != null && this.name.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
         this.addName();
         return true;
      }

      return super.keyPressed(event);
   }

   @Override
   public void onClose() {
      this.minecraft.gui.setScreen(this.parent);
   }

   @Override
   public void removed() {
      // Going to the link confirm screen and back keeps the sign-in; leaving for good ends it. (Which screen comes
      // next cannot be asked here: the game calls this before it puts the next screen in place.)
      if (!this.detour) {
         this.cancelled = true;
      }
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      super.extractRenderState(g, mouseX, mouseY, a);
      g.centeredText(this.font, this.title, this.width / 2, 20, -1);
      if (this.signingIn) {
         MsLogin.DeviceCode shown = this.code;
         int mid = this.height / 2 - 30;
         if (shown != null) {
            g.centeredText(this.font, Component.translatable("handykit.accounts.enter_code", shown.uri().replace("https://", "")), this.width / 2, mid, 0xFFC8C8C8);
            int w = this.font.width(shown.userCode()) * 2;
            g.pose().pushMatrix();
            g.pose().translate(this.width / 2.0F - w / 2.0F, mid + 16);
            g.pose().scale(2.0F, 2.0F);
            g.text(this.font, shown.userCode(), 0, 0, 0xFFFFD040, true);
            g.pose().popMatrix();
            Component where = this.progress;
            if (where != null) {
               g.centeredText(this.font, where, this.width / 2, mid + 42, 0xFF909090);
            }
         }
      } else {
         List<Accounts.@Nullable Account> rows = this.rows();
         Accounts.Account chosen = Accounts.next();
         int hover = this.rowAt(mouseX, mouseY);
         for (int i = 0; i < rows.size(); i++) {
            Accounts.Account account = rows.get(i);
            int y = this.listTop + i * ROW_H;
            boolean on = account == chosen;
            g.fill(this.listX, y, this.listX + LIST_W, y + ROW_H - 1, on ? 0xE0484848 : i == hover ? 0xD0383838 : 0x90101010);
            if (on) {
               g.fill(this.listX, y + ROW_H - 2, this.listX + LIST_W, y + ROW_H - 1, 0xFFFFFFFF);
            }

            String label = account == null ? ((MinecraftAccess)this.minecraft).handykit$launcherUser().getName() : account.name;
            // names for offline-mode servers are not accounts: grey
            int color = account != null && !account.microsoft() ? 0xFF9A9A9A : 0xFFFFFFFF;
            g.text(this.font, label, this.listX + 6, y + 5, color, false);
            long[] state = account == null ? null : this.states.get(account);
            if (state != null && i != hover) {
               // signed in until a time, signs in when a tab joins, or has to be added again
               Component text = state[0] > 0
                  ? Component.literal(java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(state[1]).atZone(java.time.ZoneId.systemDefault())))
                  : Component.translatable(state[0] == 0 ? "handykit.accounts.state.on_join" : "handykit.accounts.state.again");
               g.text(this.font, text, this.listX + LIST_W - 6 - this.font.width(text), y + 5, state[0] < 0 ? 0xFFFF8080 : state[0] > 0 ? 0xFF70C070 : 0xFF808080, false);
            }

            if (account != null && i == hover) {
               boolean onCross = mouseX >= this.listX + LIST_W - 16;
               g.text(this.font, "✕", this.listX + LIST_W - 12, y + 5, onCross ? 0xFFFF6060 : 0xFFA0A0A0, false);
            }
         }
      }

      Component line = this.status;
      if (line != null) {
         g.centeredText(this.font, line, this.width / 2, this.height - 70, 0xFFFF9090);
      }
   }
}
