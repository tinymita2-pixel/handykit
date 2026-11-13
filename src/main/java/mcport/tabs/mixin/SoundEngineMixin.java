package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.concurrent.CompletableFuture;
import mcport.tabs.Bgm;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A tab's music is brought back when the tab returns to the screen ({@link Bgm}): the sound is started with the
 * file it had, read as a stream, and what has gone by since it first started is skipped before it plays.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
   @Inject(method = "reload", at = @At("HEAD"))
   private void handykit$reloaded(CallbackInfo ci) {
      Bgm.reloaded();
   }

   @org.spongepowered.asm.mixin.Shadow
   private boolean loaded;
   @org.spongepowered.asm.mixin.Shadow
   @org.spongepowered.asm.mixin.Final
   private java.util.Map<net.minecraft.client.resources.sounds.SoundInstance, net.minecraft.client.sounds.ChannelAccess.ChannelHandle> instanceToChannel;
   @org.spongepowered.asm.mixin.Shadow
   @org.spongepowered.asm.mixin.Final
   private java.util.Map<net.minecraft.client.resources.sounds.SoundInstance, Integer> queuedSounds;
   @org.spongepowered.asm.mixin.Shadow
   @org.spongepowered.asm.mixin.Final
   private java.util.List<net.minecraft.client.resources.sounds.TickableSoundInstance> tickingSounds;
   @org.spongepowered.asm.mixin.Shadow
   @org.spongepowered.asm.mixin.Final
   private java.util.List<net.minecraft.client.resources.sounds.TickableSoundInstance> queuedTickableSounds;

   /**
    * A change of tab stops what the tab that leaves was playing. The game's way is to stop the sound thread, wait
    * for it and start it again, which holds the game for up to a third of a second; here every sound is told to
    * stop and the thread does that by itself a moment later.
    */
   @Inject(method = "stopAll", at = @At("HEAD"), cancellable = true)
   private void handykit$stopWithoutWaiting(CallbackInfo ci) {
      if (this.loaded && Bgm.changingTab()) {
         for (net.minecraft.client.sounds.ChannelAccess.ChannelHandle handle : this.instanceToChannel.values()) {
            handle.execute(com.mojang.blaze3d.audio.Channel::stop);
         }

         this.queuedSounds.clear();
         this.tickingSounds.clear();
         this.queuedTickableSounds.clear();
         ci.cancel();
      }
   }

   @ModifyExpressionValue(
      method = "play",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getSound()Lnet/minecraft/client/resources/sounds/Sound;")
   )
   private Sound handykit$sameFile(Sound picked) {
      return Bgm.variant(picked);
   }

   @ModifyExpressionValue(method = "play", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/sounds/Sound;shouldStream()Z"))
   private boolean handykit$asStream(boolean stream) {
      return stream || Bgm.seeking();
   }

   @ModifyExpressionValue(
      method = "play",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/Identifier;Z)Ljava/util/concurrent/CompletableFuture;"
      )
   )
   private CompletableFuture<AudioStream> handykit$fromTheMiddle(CompletableFuture<AudioStream> stream) {
      return Bgm.seek(stream);
   }
}
