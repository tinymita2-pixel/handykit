package mcport.tabs.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.resources.WaypointStyleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Parts of the first hud that every other hud borrows: they are loaded or registered once at startup. */
@Mixin(Hud.class)
public interface HudAccess {
   @Accessor("waypointStyles")
   WaypointStyleManager handykit$waypointStyles();

   @Mutable
   @Accessor("waypointStyles")
   void handykit$setWaypointStyles(WaypointStyleManager styles);

   @Accessor("debugOverlay")
   DebugScreenOverlay handykit$debugOverlay();

   @Mutable
   @Accessor("debugOverlay")
   void handykit$setDebugOverlay(DebugScreenOverlay overlay);
}
