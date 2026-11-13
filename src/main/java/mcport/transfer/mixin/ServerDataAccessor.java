package mcport.transfer.mixin;

import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerData.class)
public interface ServerDataAccessor {
   @Accessor("acceptedCodeOfConduct")
   int handykit$getAcceptedCodeOfConduct();

   @Accessor("acceptedCodeOfConduct")
   void handykit$setAcceptedCodeOfConduct(int value);
}
