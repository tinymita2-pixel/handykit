package mcport.macro;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** The config button in Mod Menu's list opens the macro screen. */
public final class ModMenuEntry implements ModMenuApi {
   @Override
   public ConfigScreenFactory<?> getModConfigScreenFactory() {
      return MacroScreen::new;
   }
}
