package mcport.tabs.mixin;

import mcport.tabs.Tabs;
import net.minecraft.client.resources.MapTextureManager;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Map textures are keyed by map number only; map 0 of another server must not repaint the one on screen. */
@Mixin(value = MapTextureManager.class, priority = 2000)
public abstract class MapTextureManagerMixin {
   @Inject(method = "update", at = @At("HEAD"), cancellable = true)
   private void handykit$update(MapId id, MapItemSavedData data, CallbackInfo ci) {
      if (Tabs.inBackground()) {
         ci.cancel();
      }
   }
}
