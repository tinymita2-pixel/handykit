package mcport.transfer.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import mcport.transfer.Via;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ViaFabricPlus writes "the selected version" to its settings file when the game exits, and takes whatever the
 * client-wide version is at that moment. With tabs that is the version of the tab that happened to be on screen,
 * and the next start would begin every connection as that version. The player's own pick is written instead.
 * Only applied when ViaFabricPlus is installed and still saves this way; never required.
 */
@Pseudo
@Mixin(targets = "com.viaversion.viafabricplus.settings.SettingsImpl", remap = false)
public abstract class ViaSettingsMixin {
   @ModifyExpressionValue(
      method = "snapshot()Lcom/google/gson/JsonObject;",
      at = @At(
         value = "INVOKE",
         target = "Lcom/viaversion/viafabricplus/api/ViaFabricPlusAPI;targetVersion()Lcom/viaversion/viaversion/api/protocol/version/ProtocolVersion;",
         remap = false
      ),
      remap = false,
      require = 0
   )
   private ProtocolVersion handykit$savePick(ProtocolVersion current) {
      return Via.pick(current) instanceof ProtocolVersion pick ? pick : current;
   }
}
