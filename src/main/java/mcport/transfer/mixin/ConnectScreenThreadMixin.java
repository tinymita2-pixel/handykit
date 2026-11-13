package mcport.transfer.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.netty.channel.ChannelFuture;
import mcport.transfer.TransferTargets;
import mcport.transfer.Via;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The connector thread; ViaFabricPlus reads the server's forced version, or else the client-wide one, later in the same run(). */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public abstract class ConnectScreenThreadMixin {
   @Shadow
   @Final
   ServerAddress val$hostAndPort;
   @Shadow
   @Final
   ServerData val$server;

   @Inject(method = "run", at = @At("HEAD"))
   private void handykit$detectTargetVersion(CallbackInfo ci) {
      TransferTargets.detect(this.val$server, this.val$hostAndPort);
      // without a version forced on the entry the connection starts from the player's pick, not from the version
      // of the tab that was on screen last; a fixed pick the server would turn away gives way to the server's version
      Via.beforeConnect(this.val$server, this.val$hostAndPort);
   }

   /** The connection that is logging in on screen from here on; another connection closing must not change its version. */
   @ModifyExpressionValue(
      method = "run",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/network/Connection;connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;"
      )
   )
   private ChannelFuture handykit$joining(ChannelFuture opened) {
      Via.joining(opened);
      return opened;
   }
}
