package mcport.tabs;

import mcport.tabs.mixin.GuiAccess;
import mcport.tabs.mixin.MinecraftAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * The part of the client that belongs to one server: the fields vanilla keeps on Minecraft (and the hud on Gui).
 * Exactly one World is "installed" in those fields at a time; the others wait here until their packets or
 * their tick need them.
 */
final class World {
   @Nullable LocalPlayer player;
   @Nullable ClientLevel level;
   @Nullable MultiPlayerGameMode gameMode;
   @Nullable Hud hud;
   @Nullable HitResult hit;
   @Nullable Entity crosshair;
   boolean local;
   /** The integrated server of a singleplayer world; it keeps running while its tab is behind the screen. */
   net.minecraft.client.server.@Nullable IntegratedServer server;

   /** Copies what Minecraft holds right now into this holder. */
   void capture(Minecraft mc) {
      this.player = mc.player;
      this.level = mc.level;
      this.gameMode = mc.gameMode;
      this.hud = mc.gui.hud;
      this.hit = mc.hitResult;
      this.crosshair = mc.crosshairPickEntity;
      this.local = mc.isLocalServer();
      this.server = ((MinecraftAccess)mc).handykit$server();
   }

   /** Puts this holder's objects into Minecraft's fields. Does not touch the renderer, sound or camera. */
   void install(Minecraft mc) {
      mc.player = this.player;
      mc.level = this.level;
      mc.gameMode = this.gameMode;
      if (this.hud != null) {
         ((GuiAccess)mc.gui).handykit$setHud(this.hud);
      }

      mc.hitResult = this.hit;
      mc.crosshairPickEntity = this.crosshair;
      ((MinecraftAccess)mc).handykit$setLocalServer(this.local);
      ((MinecraftAccess)mc).handykit$setServer(this.server);
   }

   void clear() {
      this.player = null;
      this.level = null;
      this.gameMode = null;
      this.hud = null;
      this.hit = null;
      this.crosshair = null;
      this.local = false;
      this.server = null;
   }

   boolean inPlay() {
      return this.player != null && this.level != null;
   }
}
