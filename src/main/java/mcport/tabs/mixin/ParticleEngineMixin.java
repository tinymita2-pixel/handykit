package mcport.tabs.mixin;

import mcport.tabs.Tabs;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Particles of a background world would appear in the world on screen. */
@Mixin(value = ParticleEngine.class, priority = 2000)
public abstract class ParticleEngineMixin {
   @Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V", at = @At("HEAD"), cancellable = true)
   private void handykit$emitter(Entity entity, ParticleOptions particle, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         ci.cancel();
      }
   }

   @Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V", at = @At("HEAD"), cancellable = true)
   private void handykit$timedEmitter(Entity entity, ParticleOptions particle, int lifetime, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         ci.cancel();
      }
   }

   @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
   private void handykit$create(ParticleOptions options, double x, double y, double z, double xd, double yd, double zd, CallbackInfoReturnable<Particle> cir) {
      if (Tabs.inBackground()) {
         cir.setReturnValue(null);
      }
   }

   /** The small view draws another world: the particles here are those of the world on screen. */
   @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
   private void handykit$notInSmallView(
      net.minecraft.client.renderer.state.level.ParticlesRenderState state,
      net.minecraft.client.renderer.culling.Frustum frustum,
      net.minecraft.client.Camera camera,
      float partialTickTime,
      CallbackInfo ci
   ) {
      if (mcport.tabs.Pip.drawing()) {
         ci.cancel();
      }
   }

   @Inject(method = "add", at = @At("HEAD"), cancellable = true)
   private void handykit$add(Particle particle, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         ci.cancel();
      }
   }
}
