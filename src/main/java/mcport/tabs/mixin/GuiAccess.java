package mcport.tabs.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Gui.class)
public interface GuiAccess {
   /** Each tab has its own hud (chat, boss bars, tab list, titles); the one on screen sits in this field. */
   @Mutable
   @Accessor("hud")
   void handykit$setHud(Hud hud);

   /** Sets the screen field alone: no removed(), no init(), no mouse grab. */
   @Accessor("screen")
   void handykit$setScreenRaw(@Nullable Screen screen);
}
