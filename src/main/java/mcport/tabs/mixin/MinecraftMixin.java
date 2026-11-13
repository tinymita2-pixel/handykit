package mcport.tabs.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mcport.tabs.Packs;
import mcport.tabs.Tabs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** What Minecraft does to the renderer, the sound and the screen for "the" world must not happen for a background one. */
@Mixin(value = Minecraft.class, priority = 2000)
public abstract class MinecraftMixin {
   @Shadow
   public @Nullable ClientLevel level;

   @Inject(method = "tick", at = @At("TAIL"))
   private void handykit$tabs(CallbackInfo ci) {
      Tabs.endTick((Minecraft)(Object)this);
   }

   @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"), cancellable = true)
   private void handykit$disconnect(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         if (!keepResourcePacks) {
            mcport.tabs.Packs.cleanup(Tabs.contextTab());
         }

         Tabs.clearBackgroundWorld((Minecraft)(Object)this);
         ci.cancel();
      } else {
         Tabs.onForegroundDisconnect((Minecraft)(Object)this, keepResourcePacks);
      }
   }

   @Inject(method = "clearClientLevel", at = @At("HEAD"), cancellable = true)
   private void handykit$clearLevel(Screen screen, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         Tabs.clearBackgroundWorld((Minecraft)(Object)this);
         ci.cancel();
      }
   }

   @Inject(method = "setLevel", at = @At("HEAD"), cancellable = true)
   private void handykit$setLevel(ClientLevel level, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         // the renderer, particles and sound stay with the world on screen
         this.level = level;
         ci.cancel();
      }
   }

   @Inject(method = "setScreenAndShow", at = @At("HEAD"), cancellable = true)
   private void handykit$setScreenAndShow(Screen screen, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         Tabs.onBackgroundScreen(screen);
         ci.cancel();
      }
   }

   @Inject(method = "setCameraEntity", at = @At("HEAD"), cancellable = true)
   private void handykit$setCamera(@Nullable Entity entity, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         Tabs.setBackgroundCamera(entity);
         ci.cancel();
      }
   }

   @Inject(method = "getCameraEntity", at = @At("HEAD"), cancellable = true)
   private void handykit$getCamera(CallbackInfoReturnable<Entity> cir) {
      if (Tabs.inBackground()) {
         cir.setReturnValue(Tabs.backgroundCamera());
      }
   }

   /** A tab that logged in with another account is that account for everything done in its name. */
   @Inject(method = "getUser", at = @At("HEAD"), cancellable = true)
   private void handykit$tabUser(CallbackInfoReturnable<net.minecraft.client.User> cir) {
      net.minecraft.client.User user = Tabs.userOverride();
      if (user != null) {
         cir.setReturnValue(user);
      }
   }

   /** Only one integrated server at a time: a singleplayer world waiting behind the screen is saved and closed first. */
   @Inject(method = "doWorldLoad", at = @At("HEAD"))
   private void handykit$oneWorld(CallbackInfo ci) {
      Tabs.onSingleplayerStart();
   }

   /** The game loads its own resources anew (F3+T, the pack screen): the tabs' resource sets are made again afterwards. */
   @Inject(method = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
   private void handykit$reloadStarts(CallbackInfoReturnable<java.util.concurrent.CompletableFuture<Void>> cir) {
      mcport.tabs.Assets.reloadStarts((Minecraft)(Object)this);
   }

   @Inject(method = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
   private void handykit$reloadEnds(CallbackInfoReturnable<java.util.concurrent.CompletableFuture<Void>> cir) {
      Minecraft mc = (Minecraft)(Object)this;
      java.util.concurrent.CompletableFuture<Void> done = cir.getReturnValue();
      if (done != null) {
         done.whenComplete((nothing, error) -> mc.execute(() -> mcport.tabs.Assets.reloadEnds(mc)));
      }
   }

   /** Before the game draws the screen: the small view of a watched tab is drawn into its own picture. */
   @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"))
   private void handykit$smallView(boolean advanceGameTime, CallbackInfo ci) {
      if (advanceGameTime) {
         Minecraft mc = (Minecraft)(Object)this;
         mcport.tabs.Pip.frame(mc, mc.getDeltaTracker());
      }
   }

   /** Test runs count frames here. */
   @Inject(method = "runTick", at = @At("TAIL"))
   private void handykit$frame(boolean advanceGameTime, CallbackInfo ci) {
      Tabs.frame();
   }

   @Inject(method = "stop", at = @At("HEAD"))
   private void handykit$stop(CallbackInfo ci) {
      Tabs.shutdown();
   }
}
