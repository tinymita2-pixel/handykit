package mcport.tabs.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccess {
   @Accessor("isLocalServer")
   void handykit$setLocalServer(boolean local);

   /** Rebinds the renderer, particles, sound and camera to a level (or to none). */
   @Invoker("updateLevelInEngines")
   void handykit$updateLevelInEngines(@Nullable ClientLevel level, boolean stopSound);

   /** The integrated server of the singleplayer world in Minecraft's fields (it travels with its tab). */
   @Accessor("singleplayerServer")
   net.minecraft.client.server.@Nullable IntegratedServer handykit$server();

   @Accessor("singleplayerServer")
   void handykit$setServer(net.minecraft.client.server.@Nullable IntegratedServer server);

   // the small view of a watched tab draws with a renderer of its own, put here for the time it draws
   @Mutable
   @Accessor("levelRenderer")
   void handykit$setLevelRenderer(net.minecraft.client.renderer.LevelRenderer renderer);

   @Mutable
   @Accessor("levelExtractor")
   void handykit$setLevelExtractor(net.minecraft.client.renderer.extract.LevelExtractor extractor);

   /** The account the game was started with, whatever tab is on screen. */
   @Accessor("user")
   net.minecraft.client.User handykit$launcherUser();

   // what the attack, use and pick keys do, for a tab that is played back behind the screen
   @Invoker("startAttack")
   boolean handykit$startAttack();

   @Invoker("startUseItem")
   void handykit$startUseItem();

   @Invoker("continueAttack")
   void handykit$continueAttack(boolean down);

   @Invoker("pickBlockOrEntity")
   void handykit$pickBlockOrEntity();

   @Accessor("rightClickDelay")
   int handykit$rightClickDelay();

   @Accessor("rightClickDelay")
   void handykit$setRightClickDelay(int ticks);

   @Accessor("missTime")
   int handykit$missTime();

   @Accessor("missTime")
   void handykit$setMissTime(int ticks);
}
