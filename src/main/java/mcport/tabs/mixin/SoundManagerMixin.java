package mcport.tabs.mixin;

import mcport.tabs.Bgm;
import mcport.tabs.Tabs;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Worlds behind the screen are silent unless the player chose to hear every tab; then their one-shot sounds play
 * as heard by that tab's player. A "stop sound" from one of them never cuts the sound on screen. What a server
 * starts and stops in a tab is noted on the way ({@link Bgm}), so its music can go on when the tab is back.
 */
@Mixin(value = SoundManager.class, priority = 2000)
public abstract class SoundManagerMixin {
   @Inject(method = "play", at = @At("HEAD"), cancellable = true)
   private void handykit$play(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
      Bgm.heard(sound);
      if (Tabs.inBackground() ? !Tabs.letsBackgroundSound(sound) : !Tabs.letsForegroundSound(sound)) {
         cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
      }
   }

   /** A tab behind the screen plays by the sound events of its own packs, not by those of the tab on screen. */
   @Inject(method = "getSoundEvent", at = @At("HEAD"), cancellable = true)
   private void handykit$ownEvents(Identifier location, CallbackInfoReturnable<net.minecraft.client.sounds.WeighedSoundEvents> cir) {
      if (mcport.tabs.Assets.soundsElsewhere()) {
         cir.setReturnValue((net.minecraft.client.sounds.WeighedSoundEvents)mcport.tabs.Assets.soundEvent(location));
      }
   }

   @Inject(method = "play", at = @At("RETURN"))
   private void handykit$played(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
      Bgm.played(sound);
   }

   @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
   private void handykit$playDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
      if (Tabs.inBackground() && !Tabs.letsBackgroundSound(sound)) {
         ci.cancel();
      }
   }

   @Inject(method = "queueTickingSound", at = @At("HEAD"), cancellable = true)
   private void handykit$queueTicking(TickableSoundInstance sound, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         ci.cancel();
      }
   }

   @Inject(method = "stop(Lnet/minecraft/resources/Identifier;Lnet/minecraft/sounds/SoundSource;)V", at = @At("HEAD"), cancellable = true)
   private void handykit$stopNamed(Identifier name, SoundSource source, CallbackInfo ci) {
      Bgm.stopped(name, source);
      if (Tabs.inBackground()) {
         ci.cancel();
      }
   }

   @Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
   private void handykit$stopOne(SoundInstance sound, CallbackInfo ci) {
      Bgm.stopped(sound);
   }

   @Inject(method = "stop()V", at = @At("HEAD"), cancellable = true)
   private void handykit$stopAll(CallbackInfo ci) {
      if (Bgm.stoppedAll()) {
         ci.cancel();
      }
   }
}
