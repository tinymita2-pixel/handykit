package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import mcport.tabs.Tabs;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every login starts here: tie the new connection to its tab, and to the account that tab logs in with. */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class LoginListenerMixin {
   @Shadow
   @Final
   private Connection connection;
   @Shadow
   @Final
   private @Nullable ServerData serverData;
   /** Set on the connector thread; the session server is asked later, on an IO thread that knows nothing of tabs. */
   @Unique
   private @Nullable User handykit$user;

   @Inject(method = "<init>", at = @At("RETURN"))
   private void handykit$created(CallbackInfo ci) {
      this.handykit$user = Tabs.threadUser();
      Tabs.onLoginListener(this.connection, this.serverData);
   }

   @ModifyExpressionValue(method = "authenticateServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getUser()Lnet/minecraft/client/User;"))
   private User handykit$tabAccount(User original) {
      User user = this.handykit$user;
      return user != null ? user : original;
   }
}
