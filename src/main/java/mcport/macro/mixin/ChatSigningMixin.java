package mcport.macro.mixin;

import mcport.macro.MacroConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.world.entity.player.ProfileKeyPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat reports need the signature the client puts on each message with its profile key. Without the key the
 * client never opens a signed chat session and every message goes out unsigned, which the report system cannot
 * use. Servers that insist on signed chat refuse unsigned messages; the player is told once per join.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChatSigningMixin {
   @Inject(method = "prepareKeyPair", at = @At("HEAD"), cancellable = true)
   private void handykit$noKey(CallbackInfo ci) {
      // the profile key is the one of the account the game was started with; another account must not show it
      if (MacroConfig.get().unsignedChat || mcport.tabs.Tabs.userOverride() != null) {
         ci.cancel();
      }
   }

   @Inject(method = "setKeyPair", at = @At("HEAD"), cancellable = true)
   private void handykit$noSession(ProfileKeyPair keyPair, CallbackInfo ci) {
      if (MacroConfig.get().unsignedChat || mcport.tabs.Tabs.userOverride() != null) {
         ci.cancel();
      }
   }

   @Inject(method = "handleLogin", at = @At("TAIL"))
   private void handykit$tellIfRefused(ClientboundLoginPacket packet, CallbackInfo ci) {
      boolean unsigned = MacroConfig.get().unsignedChat || mcport.tabs.Tabs.userOverride() != null;
      if (packet.enforcesSecureChat() && unsigned && Minecraft.getInstance().isSameThread()) {
         Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.translatable("handykit.unsigned.refused"));
      }
   }
}
