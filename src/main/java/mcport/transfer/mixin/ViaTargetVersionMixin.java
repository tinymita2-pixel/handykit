package mcport.transfer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import mcport.transfer.Via;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Every change of ViaFabricPlus's client-wide version passes here. ViaFabricPlus remembers one "version before the
 * connection" and puts it back when a connection closes; with two connections open the second close hands over
 * null, which breaks ViaFabricPlus itself, and the first can change the version under a server that is on screen
 * or logging in. {@link Via#guardTargetVersion} says what the version may become. Only applied when ViaFabricPlus
 * is installed and still has this method; never required.
 */
@Pseudo
@Mixin(targets = "com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl", remap = false)
public abstract class ViaTargetVersionMixin {
   @WrapMethod(method = "setTargetVersion(Lcom/viaversion/viaversion/api/protocol/version/ProtocolVersion;Z)V", remap = false, require = 0)
   private void handykit$guard(ProtocolVersion version, boolean revertOnDisconnect, Operation<Void> original) {
      Object allowed = Via.guardTargetVersion(version, revertOnDisconnect);
      if (allowed != null) {
         original.call(allowed, revertOnDisconnect);
      }
   }
}
