package mcport.tabs.mixin;

import java.util.function.BooleanSupplier;
import mcport.tabs.Tabs;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.MessageSignature;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With the "chat delay" option on, lines wait in one queue and are shown later, by then in whatever tab is on
 * screen. Lines of a tab behind the screen are taken at once, in their own tab.
 */
@Mixin(ChatListener.class)
public abstract class ChatListenerMixin {
   @Inject(method = "handleMessage", at = @At("HEAD"), cancellable = true)
   private void handykit$noDelayBehindTheScreen(@Nullable MessageSignature signature, BooleanSupplier handler, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         handler.getAsBoolean();
         ci.cancel();
      }
   }
}
