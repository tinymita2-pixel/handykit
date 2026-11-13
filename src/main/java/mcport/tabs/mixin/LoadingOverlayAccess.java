package mcport.tabs.mixin;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A reload that runs without its loading screen is finished from Packs: the reload itself, and what follows it. */
@Mixin(LoadingOverlay.class)
public interface LoadingOverlayAccess {
   @Accessor("reload")
   ReloadInstance handykit$reload();

   @Accessor("onFinish")
   Consumer<Optional<Throwable>> handykit$onFinish();
}
