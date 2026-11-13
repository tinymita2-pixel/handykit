package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import mcport.tabs.Packs;
import mcport.tabs.Tabs;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game has one pack manager for "the" server. With tabs every tab has its own (Packs): what a server sends,
 * takes back, and what the player answers goes to the manager of the tab it happens in.
 */
@Mixin(DownloadedPackSource.class)
public abstract class PackSourceMixin {
   @ModifyExpressionValue(
      method = {"pushPack", "popPack", "popAll", "allowServerPacks", "rejectServerPacks"},
      at = @At(
         value = "FIELD",
         target = "Lnet/minecraft/client/resources/server/DownloadedPackSource;manager:Lnet/minecraft/client/resources/server/ServerPackManager;"
      )
   )
   private ServerPackManager handykit$tabManager(ServerPackManager original) {
      return Packs.managerNow(original);
   }

   /** A connection starts (on the connecting thread): the tab it belongs to hears what the server list says about packs. */
   @Inject(method = "configureForServerControl", at = @At("HEAD"), cancellable = true)
   private void handykit$control(Connection connection, ServerPackManager.PackPromptStatus status, CallbackInfo ci) {
      if (Packs.control(connection, status)) {
         ci.cancel();
      }
   }

   /** A session ended: the packs of its tab go. The game's own manager (it holds nothing of a tab's) does the same. */
   @Inject(method = "cleanupAfterDisconnect", at = @At("HEAD"))
   private void handykit$cleared(CallbackInfo ci) {
      Packs.cleanup(Tabs.contextTab());
   }
}
