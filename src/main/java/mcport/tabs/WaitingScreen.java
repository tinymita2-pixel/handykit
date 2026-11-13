package mcport.tabs;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Shown for a tab that is still logging in (after a transfer): keeps its connection ticking until the world arrives. */
final class WaitingScreen extends Screen {
   private final Tab tab;

   WaitingScreen(Tab tab) {
      super(Component.translatable("connect.joining"));
      this.tab = tab;
   }

   @Override
   protected void init() {
      this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> Tabs.requestClose(this.tab))
         .bounds(this.width / 2 - 100, this.height / 4 + 132, 200, 20)
         .build());
   }

   @Override
   public void tick() {
      Connection connection = this.tab.connection;
      if (connection != null) {
         if (connection.isConnected()) {
            connection.tick();
         } else {
            connection.handleDisconnection();
         }
      }
   }

   @Override
   public boolean shouldCloseOnEsc() {
      return false;
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      super.extractRenderState(g, mouseX, mouseY, a);
      g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 50, -1);
      g.centeredText(this.font, Component.literal(this.tab.title()), this.width / 2, this.height / 2 - 36, 0xFFA0A0A0);
   }
}
