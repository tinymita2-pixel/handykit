package mcport.tabs.mixin;

import mcport.tabs.Assets;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The pages a font's glyphs are drawn on are textures named after the font; each tab's resource set names its own. */
@Mixin(FontManager.class)
public abstract class FontLookMixin {
   @ModifyArg(
      method = "createFontSet",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/font/GlyphStitcher;<init>(Lnet/minecraft/client/renderer/texture/TextureManager;Lnet/minecraft/resources/Identifier;)V"
      ),
      index = 1
   )
   private Identifier handykit$pages(Identifier font) {
      return Assets.fontPages(font);
   }
}
