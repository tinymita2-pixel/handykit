package mcport.macro.mixin;

import mcport.macro.MacroClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every slot click in a container goes through here, for the input recorder. */
@Mixin(MultiPlayerGameMode.class)
public abstract class ContainerInputMixin {
   @Inject(method = "handleContainerInput", at = @At("HEAD"))
   private void handykit$click(int containerId, int slotId, int buttonNum, ContainerInput input, Player player, CallbackInfo ci) {
      MacroClient.onContainerInput(containerId, slotId, buttonNum, input);
   }
}
