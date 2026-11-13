package mcport.tabs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/** One open server: a tab in the bar. While it is not the one on screen, its world waits in {@link #world}. */
public final class Tab {
   private static int nextId = 1;

   public final int id = nextId++;
   String name;
   String address;
   /** The live connection; replaced when the server transfers the tab somewhere else. */
   volatile @Nullable Connection connection;
   final World world = new World();
   /** Camera target while in the background (vanilla keeps it on the shared camera). */
   @Nullable Entity camera;
   /** A server-opened screen (chest, death, dialog) kept for when the tab comes back. */
   @Nullable Screen screen;
   /** Its own pack manager and what it has loaded (Packs); made when its first connection starts. */
   Packs.@Nullable State packState;
   /** The resource set it is drawn with (its server packs on top of the game's resources); null: the game's own. */
   Assets.@Nullable Look look;
   /** The set that is being made for packs its server has just sent. */
   Assets.@Nullable Look nextLook;
   /** Last tag payload per registry, to rebind the shared registries when this tab is on screen. */
   final Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = new HashMap<>();
   int serverRenderDistance = -1;
   boolean local;
   /** Between the two connections of a transfer: the old one is closed, the next one is not there yet. */
   volatile boolean transferring;
   /** A connector thread is on its way to the transfer target. */
   volatile boolean connecting;
   /** Ticks a transfer has gone without anything picking it up. */
   int transferWait;
   /** The account this tab logged in with when it is not the one the game was started with. */
   Accounts.@Nullable Account account;
   /** That account's login; the token is renewed on the connector thread before each connection. */
   volatile @Nullable User user;
   /** Minecraft keeps these two counters for the world on screen; a tab played back behind the screen has its own. */
   int rightClickDelay;
   int missTime;
   /** The name the player gave this tab. */
   @Nullable String customName;
   /** How the tab was joined, kept for joining again (reconnect, duplicate, tab sets). */
   net.minecraft.client.multiplayer.@Nullable ServerData data;
   /** The player turned this tab's sound off, or down. */
   boolean muted;
   float volume = 1.0F;
   /** Chat and commands sent in the tab on screen also go to every other tab marked like this. */
   boolean linked;
   /** Shown small in a corner while it is behind the screen. */
   boolean watch;
   long watchSince;
   /** Chat lines under this tab's small view; -1 until the player changes it (then the last choice made anywhere). */
   int watchLines = -1;
   /** What is typed in the line under its small view and not sent yet, and the lines sent from there (oldest first). */
   String watchDraft = "";
   final java.util.ArrayList<String> watchSent = new java.util.ArrayList<>();
   /** The renderer this tab's world is drawn with; it keeps the built terrain while the tab is behind the screen. */
   Renderers.@Nullable Pair pair;
   /** The screen as it last looked with this tab on it. */
   com.mojang.blaze3d.pipeline.@Nullable TextureTarget shot;
   long shotAt;
   /** The long sounds its server is playing. */
   final java.util.ArrayList<Bgm.Track> tracks = new java.util.ArrayList<>();
   /** They were stopped when the tab left the screen or resources were loaded anew, and are due to start again. */
   boolean soundsLost;
   long soundsLostAt;
   /** Its chat called for attention while it was behind the screen. */
   boolean alert;
   /** The last few chat lines, for the preview. */
   final java.util.ArrayDeque<String> chat = new java.util.ArrayDeque<>();
   /** Joining again after the server was lost: tries so far, and when the next one is due (0: none). */
   int reconnects;
   long reconnectAt;
   /** What is still to be sent after joining, and the ticks until the next line. */
   final java.util.ArrayDeque<String> afterJoin = new java.util.ArrayDeque<>();
   int afterJoinWait;
   boolean wasInPlay;
   int playTicks;
   /** The small view's ground colours, refreshed every few ticks. */
   int @Nullable [] watchMap;
   int watchAge;
   boolean closed;
   @Nullable Component closeReason;
   int unread;
   long lastSeen = System.currentTimeMillis();

   Tab(String name, String address) {
      this.name = name;
      this.address = address;
   }

   /** The name of the other account this tab plays as, or null for the game's own. */
   public @Nullable String accountName() {
      Accounts.Account a = this.account;
      return a == null ? null : a.name;
   }

   private @Nullable String keyOf;
   private @Nullable String key;

   /** The address in lower case (settings are filed under it), or null without one. Asked several times a frame. */
   @Nullable String addressKey() {
      String now = this.address;
      if (now != this.keyOf) {
         this.keyOf = now;
         this.key = now == null || now.isBlank() ? null : now.toLowerCase(java.util.Locale.ROOT);
      }

      return this.key;
   }

   /** A transfer that waits for the player to answer a screen first. */
   boolean asks() {
      return this.transferring && this.screen != null;
   }

   public String title() {
      if (this.customName != null && !this.customName.isBlank()) {
         return this.customName;
      }

      return this.name == null || this.name.isBlank() ? this.address : this.name;
   }

   @Override
   public String toString() {
      return "Tab#" + this.id + "[" + this.title() + "]";
   }
}
