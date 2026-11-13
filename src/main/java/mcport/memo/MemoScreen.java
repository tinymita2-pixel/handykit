package mcport.memo;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Writing on the memo pad: the pad as it floats over the game, with a text box where its text is. Nothing is drawn
 * behind it and the game goes on. Esc, a click beside the pad, or anything else that closes it keeps what was
 * written.
 */
public final class MemoScreen extends Screen {
   private MultiLineEditBox box;

   public MemoScreen() {
      super(Component.translatable("key.handykit.memo"));
   }

   @Override
   protected void init() {
      int[] r = Memo.rect(this.minecraft);
      this.box = MultiLineEditBox.builder()
         .setX(r[0])
         .setY(r[1] + Memo.HANDLE)
         .setShowBackground(false)
         .setShowDecorations(false)
         .setTextShadow(false)
         .build(this.font, r[2], r[3] - Memo.HANDLE, this.title);
      this.box.setCharacterLimit(20000);
      this.box.setValue(Memo.text());
      this.box.setValueListener(Memo::typed);
      this.addRenderableWidget(this.box);
      this.setInitialFocus(this.box);
   }

   /** The pad was moved or pulled to another size: the text box goes with it. */
   void refit() {
      this.rebuildWidgets();
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      // while the pad is being moved the box follows it (its size is fitted when the pad is let go)
      int[] r = Memo.rect(this.minecraft);
      if (this.box.getX() != r[0] || this.box.getY() != r[1] + Memo.HANDLE) {
         this.box.setX(r[0]);
         this.box.setY(r[1] + Memo.HANDLE);
      }

      super.extractRenderState(g, mouseX, mouseY, a);
   }

   @Override
   public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      // nothing: the game stays in view
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      // the handle and the corner are taken before this (Memo.press); what arrives here is the text box or beside the pad
      if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && !Memo.covers(this.minecraft, event.x(), event.y())) {
         this.onClose();
         return true;
      }

      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   @Override
   public void onClose() {
      Memo.save();
      this.minecraft.gui.setScreen(null);
   }

   @Override
   public void removed() {
      Memo.save();
      super.removed();
   }
}
