package mcport.tabs.mixin;

import mcport.tabs.Renderers;
import mcport.tabs.Tabs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.extract.TransientBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A world tells "the" renderer of block and light changes. Every tab has its own renderer: the changes of a world
 * behind the screen go to that one, never to the one on screen.
 */
@Mixin(value = LevelExtractor.class, priority = 2000)
public abstract class LevelExtractorMixin {
   @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
   private void handykit$blockChanged(BlockPos pos, int flags, CallbackInfo ci) {
      if (Renderers.redirect((LevelExtractor)(Object)this, renderer -> renderer.blockChanged(pos, flags))) {
         ci.cancel();
      }
   }

   @Inject(method = "setBlocksDirty", at = @At("HEAD"), cancellable = true)
   private void handykit$blocksDirty(int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfo ci) {
      if (Renderers.redirect((LevelExtractor)(Object)this, renderer -> renderer.setBlocksDirty(x0, y0, z0, x1, y1, z1))) {
         ci.cancel();
      }
   }

   @Inject(
      method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private void handykit$blockDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
      if (Renderers.redirect((LevelExtractor)(Object)this, renderer -> renderer.setBlockDirty(pos, oldState, newState))) {
         ci.cancel();
      }
   }

   @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"), cancellable = true)
   private void handykit$sectionWithNeighbors(int x, int y, int z, CallbackInfo ci) {
      if (Renderers.redirect((LevelExtractor)(Object)this, renderer -> renderer.setSectionDirtyWithNeighbors(x, y, z))) {
         ci.cancel();
      }
   }

   @Inject(method = "setSectionRangeDirty", at = @At("HEAD"), cancellable = true)
   private void handykit$sectionRange(int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfo ci) {
      if (Renderers.redirect((LevelExtractor)(Object)this, renderer -> renderer.setSectionRangeDirty(x0, y0, z0, x1, y1, z1))) {
         ci.cancel();
      }
   }

   @Inject(method = "setSectionDirty(III)V", at = @At("HEAD"), cancellable = true)
   private void handykit$section(int x, int y, int z, CallbackInfo ci) {
      if (Renderers.redirect((LevelExtractor)(Object)this, renderer -> renderer.setSectionDirty(x, y, z))) {
         ci.cancel();
      }
   }

   @Inject(method = "queueTransientBlock", at = @At("HEAD"), cancellable = true)
   private void handykit$transientBlock(TransientBlock block, CallbackInfo ci) {
      if (Renderers.redirect((LevelExtractor)(Object)this, renderer -> renderer.queueTransientBlock(block))) {
         ci.cancel();
      }
   }

   @Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
   private void handykit$allChanged(CallbackInfo ci) {
      if (Renderers.passing()) {
         return;
      }

      if (Tabs.inBackground()) {
         ci.cancel();
      } else {
         // called when a reload is through: the renderers of the other tabs catch up
         Renderers.onAllChanged((LevelExtractor)(Object)this);
      }
   }

   /** A tab coming back on screen: its renderer is bound to its world already, and binding again would rebuild everything. */
   @Inject(method = "setLevel", at = @At("HEAD"), cancellable = true)
   private void handykit$keepTerrain(@Nullable ClientLevel level, CallbackInfo ci) {
      if (Renderers.keeps((LevelExtractor)(Object)this, level)) {
         ci.cancel();
      }
   }

   @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
   private void handykit$reloaded(ResourceManager resourceManager, CallbackInfo ci) {
      Renderers.onReload();
   }
}
