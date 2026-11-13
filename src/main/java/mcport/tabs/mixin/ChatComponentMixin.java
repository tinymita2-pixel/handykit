package mcport.tabs.mixin;

import mcport.tabs.Tabs;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts chat lines that arrive in a tab while it is in the background. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
   @Inject(method = "addMessage", at = @At("HEAD"))
   private void handykit$unread(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
      Tabs.onChatLine(message);
   }

   /** Two of my tabs on one server both get every public line; the log keeps one. */
   @Inject(method = "logChatMessage", at = @At("HEAD"), cancellable = true)
   private void handykit$logOnce(net.minecraft.client.multiplayer.chat.GuiMessage message, CallbackInfo ci) {
      if (Tabs.loggedByOtherTab(message.content().getString())) {
         ci.cancel();
      }
   }

   /** The text of the log line (first String local of logChatMessage), tagged when the tab is in the background. */
   @ModifyVariable(method = "logChatMessage", at = @At("STORE"), ordinal = 0)
   private String handykit$logTag(String text) {
      return Tabs.logTag(text);
   }
}
