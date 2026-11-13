package mcport.tabs.mixin;

import java.util.Optional;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The game loads the cloud picture into its one cloud renderer when resources load. The second renderer of the small
 * view has a cloud renderer of its own, which nobody would ever hand that picture to.
 */
@Mixin(CloudRenderer.class)
public interface CloudRendererAccess {
   @Invoker("prepare")
   Optional<?> handykit$prepare(ResourceManager manager, ProfilerFiller profiler);

   @Invoker("apply")
   void handykit$apply(Optional<?> data, ResourceManager manager, ProfilerFiller profiler);
}
