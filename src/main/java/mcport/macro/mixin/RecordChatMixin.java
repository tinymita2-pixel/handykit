package mcport.macro.mixin;

import mcport.macro.MacroClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat and commands on their way out, for the input recorder. */
@Mixin(ClientPacketListener.class)
public abstract class RecordChatMixin {
   @Inject(method = "sendChat", at = @At("HEAD"))
   private void handykit$chat(String content, CallbackInfo ci) {
      MacroClient.onChatSent((ClientPacketListener)(Object)this, content, false);
   }

   @Inject(method = "sendCommand", at = @At("HEAD"))
   private void handykit$command(String command, CallbackInfo ci) {
      MacroClient.onChatSent((ClientPacketListener)(Object)this, command, true);
   }
}
