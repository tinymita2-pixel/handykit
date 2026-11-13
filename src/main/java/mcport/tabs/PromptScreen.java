package mcport.tabs;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** One line of text asked from the player (a tab's name, what to send after joining), then back where they were. */
public final class PromptScreen extends Screen {
   private final @Nullable Screen parent;
   private final String initial;
   private final int maxLength;
   private final Consumer<String> done;
   private EditBox box;

   public PromptScreen(@Nullable Screen parent, Component title, String initial, int maxLength, Consumer<String> done) {
      super(title);
      this.parent = parent;
      this.initial = initial;
      this.maxLength = maxLength;
      this.done = done;
   }

   @Override
   protected void init() {
      int w = Math.min(this.width - 40, 320);
      this.box = new EditBox(this.font, this.width / 2 - w / 2, this.height / 2 - 10, w, 20, this.title);
      this.box.setMaxLength(this.maxLength);
      this.box.setValue(this.initial);
      this.addRenderableWidget(this.box);
      this.setInitialFocus(this.box);
      this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.finish()).bounds(this.width / 2 - 102, this.height / 2 + 18, 100, 20).build());
      this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose()).bounds(this.width / 2 + 2, this.height / 2 + 18, 100, 20).build());
   }

   private void finish() {
      this.done.accept(this.box.getValue());
      this.onClose();
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
         this.finish();
         return true;
      }

      return super.keyPressed(event);
   }

   @Override
   public void onClose() {
      this.minecraft.gui.setScreen(this.parent);
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      super.extractRenderState(g, mouseX, mouseY, a);
      g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 30, -1);
   }
}
