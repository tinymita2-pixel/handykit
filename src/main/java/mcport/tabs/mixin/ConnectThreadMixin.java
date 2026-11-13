package mcport.tabs.mixin;

import mcport.tabs.Tabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The connector thread of the connect screen logs in as the account of the tab it connects. */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public abstract class ConnectThreadMixin {
   @Inject(method = "run", at = @At("HEAD"))
   private void handykit$account(CallbackInfo ci) {
      Tabs.onConnectorThread(null);
   }
}
