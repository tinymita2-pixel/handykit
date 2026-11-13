package mcport.tabs.mixin;

import mcport.tabs.Tabs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Joining a server while one is on screen opens a tab; a background tab that is transferred reconnects by itself. */
@Mixin(value = ConnectScreen.class, priority = 2000)
public abstract class ConnectScreenMixin {
   @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
   private static void handykit$startConnecting(
      Screen parent, Minecraft minecraft, ServerAddress hostAndPort, ServerData data, boolean isQuickPlay, @Nullable TransferState transferState, CallbackInfo ci
   ) {
      Tabs.trace("startConnecting " + hostAndPort + " transfer=" + (transferState != null) + " background=" + Tabs.inBackground());
      if (Tabs.onStartConnecting(minecraft, hostAndPort, data, transferState)) {
         ci.cancel();
      }
   }
}
