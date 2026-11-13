package mcport.tabs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import mcport.tabs.mixin.SoundInstanceAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The long sounds a server is playing in a tab (its music), and bringing them back.
 *
 * <p>The game stops every sound when the world on screen changes and when resources are loaded anew, and a server
 * that started a piece of music does not know to start it again. So a tab that came back on screen was silent
 * until the server's next piece. Here every sound a server starts in a tab is noted with the moment it started,
 * whether the tab is on screen or behind it; once its length is known the short ones are forgotten. When the tab is
 * on screen again (and its resource packs are loaded), what would still be playing is started again at the place
 * it would have reached by now.
 */
public final class Bgm {
   /** Shorter sounds are over before they could be missed. */
   private static final float SHORTEST = 8.0F;
   /** Sounds remembered per tab (one per name and category: the newest). */
   private static final int MOST = 96;

   static final class Track {
      final SoundInstance instance;
      /** As the server asked for it: a tab's own volume and the place of a sound heard from behind the screen change the instance. */
      final float volume;
      final double x;
      final double y;
      final double z;
      final long started = System.nanoTime();
      /** The file picked for it (a sound name can stand for several). */
      @Nullable Sound sound;
      /** Its length; -1 not looked at yet, 0 cannot be told. */
      float seconds = -1.0F;

      Track(SoundInstance instance, float volume, double x, double y, double z) {
         this.instance = instance;
         this.volume = volume;
         this.x = x;
         this.y = y;
         this.z = z;
      }
   }

   /** The length of each sound file, with the resources loaded now. */
   private static final Map<Identifier, CompletableFuture<Float>> LENGTHS = new HashMap<>();
   /** Counts the reloads of the sound engine: what was asked before one is not acted on after it. */
   private static int generation;
   /** A packet from a server is being handled: sounds started now are the server's. */
   private static int packets;
   /** The sounds are being stopped because a tab leaves or enters the screen, not because its world said so. */
   private static boolean holding;
   private static boolean resuming;
   private static float seek;
   private static @Nullable Sound forced;

   private Bgm() {
   }

   static void enterPacket() {
      packets++;
   }

   static void leavePacket() {
      packets--;
   }

   static void holding(boolean now) {
      holding = now;
   }

   /** The sounds are being stopped because the tab on screen changes (SoundEngineMixin). */
   public static boolean changingTab() {
      return holding;
   }

   private static boolean tracked(SoundInstance sound) {
      Class<?> type = sound.getClass();
      return type == SimpleSoundInstance.class || type == EntityBoundSoundInstance.class;
   }

   // ---------------------------------------------------------------- noting what plays

   /** SoundManager.play, before anything decides whether it is heard: in the context of the tab it belongs to. */
   public static void heard(SoundInstance sound) {
      if (packets <= 0 || resuming || !tracked(sound)) {
         return;
      }

      Tab tab = Tabs.contextTab();
      if (tab == null || tab.closed) {
         return;
      }

      List<Track> tracks = tab.tracks;
      Identifier name = sound.getIdentifier();
      SoundSource source = sound.getSource();
      tracks.removeIf(t -> t.instance.getSource() == source && t.instance.getIdentifier().equals(name));
      if (tracks.size() >= MOST) {
         tracks.removeFirst();
      }

      SoundInstanceAccess access = (SoundInstanceAccess)sound;
      tracks.add(new Track(sound, access.handykit$volume(), access.handykit$x(), access.handykit$y(), access.handykit$z()));
   }

   /** SoundManager.play is through: on screen the file is known now, and with it the length. */
   public static void played(SoundInstance sound) {
      if (packets <= 0 || resuming || Tabs.inBackground() || !tracked(sound)) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      Tab tab = Tabs.contextTab();
      if (tab == null || tab.tracks.isEmpty() || mc.gui.overlay() != null) {
         // behind the screen, or while packs load, the resources are not this tab's: looked at when it is back
         return;
      }

      Track track = tab.tracks.getLast();
      if (track.instance != sound || track.sound != null) {
         return;
      }

      Sound picked = sound.getSound();
      if (picked == null) {
         return;
      }

      if (picked == SoundManager.EMPTY_SOUND || picked == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
         tab.tracks.remove(track);
         return;
      }

      track.sound = picked;
      length(mc, picked).thenAcceptAsync(seconds -> {
         track.seconds = seconds;
         if (seconds < SHORTEST && !sound.isLooping()) {
            tab.tracks.remove(track);
         }
      }, mc);
   }

   /** SoundManager.stop(name, category): the server of the tab in context stops sounds. */
   public static void stopped(@Nullable Identifier name, @Nullable SoundSource source) {
      Tab tab = Tabs.contextTab();
      if (tab != null && !tab.tracks.isEmpty()) {
         tab.tracks.removeIf(t -> (name == null || t.instance.getIdentifier().equals(name)) && (source == null || t.instance.getSource() == source));
      }
   }

   /** SoundManager.stop(sound). */
   public static void stopped(SoundInstance sound) {
      if (resuming || !tracked(sound)) {
         return;
      }

      for (Tab tab : Tabs.tabs()) {
         if (!tab.tracks.isEmpty()) {
            tab.tracks.removeIf(t -> t.instance == sound);
         }
      }
   }

   /**
    * SoundManager.stop(): every sound stops. When a tab's own world says so (a new world, a respawn) its music is
    * over. True for a world behind the screen: the sounds playing are not its to stop.
    */
   public static boolean stoppedAll() {
      if (!holding && !resuming) {
         Tab tab = Tabs.contextTab();
         if (tab != null) {
            tab.tracks.clear();
         }
      }

      return Tabs.inBackground();
   }

   /** SoundEngine.reload: resources were loaded anew, which stopped everything. */
   public static void reloaded() {
      generation++;
      LENGTHS.clear();
      Tab tab = Tabs.active();
      if (tab != null) {
         lost(tab);
      }
   }

   /** This tab's sounds were stopped by something other than its server. */
   static void lost(Tab tab) {
      if (!tab.tracks.isEmpty()) {
         tab.soundsLost = true;
         tab.soundsLostAt = System.currentTimeMillis();
      }
   }

   // ---------------------------------------------------------------- bringing it back

   /** Every tick, for the tab on screen. */
   static void tick(Minecraft mc) {
      Tab tab = Tabs.active();
      if (tab == null || !tab.soundsLost || mc.level == null || mc.player == null || mc.gui.overlay() != null) {
         return;
      }

      if (Assets.busy(tab) && System.currentTimeMillis() - tab.soundsLostAt < 15000L) {
         // its packs are not loaded yet: the music may be in them
         return;
      }

      tab.soundsLost = false;
      restore(mc, tab);
   }

   private static void restore(Minecraft mc, Tab tab) {
      SoundManager sounds = mc.getSoundManager();
      int asked = generation;
      for (Track track : List.copyOf(tab.tracks)) {
         if (sounds.isActive(track.instance)) {
            continue;
         }

         if (track.sound == null) {
            WeighedSoundEvents event = track.instance.getOrResolve(sounds);
            Sound picked = track.instance.getSound();
            if (event == null || picked == null || picked == SoundManager.EMPTY_SOUND || picked == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
               tab.tracks.remove(track);
               continue;
            }

            track.sound = picked;
         }

         length(mc, track.sound).thenAcceptAsync(seconds -> {
            track.seconds = seconds;
            resume(mc, tab, track, asked);
         }, mc);
      }
   }

   private static void resume(Minecraft mc, Tab tab, Track track, int asked) {
      if (!tab.tracks.contains(track)) {
         return;
      }

      SoundInstance instance = track.instance;
      boolean looping = instance.isLooping();
      if (track.seconds < SHORTEST && !looping) {
         tab.tracks.remove(track);
         return;
      }

      if (asked != generation || tab != Tabs.active() || Tabs.inBackground() || mc.level == null) {
         // the tab left again, or resources were loaded once more: the next return sees to it
         return;
      }

      SoundInstanceAccess access = (SoundInstanceAccess)instance;
      double at = (System.nanoTime() - track.started) / 1.0E9 * Math.clamp(access.handykit$pitch(), 0.5F, 2.0F);
      if (looping) {
         at = track.seconds > 0.0F ? at % track.seconds : 0.0;
      } else if (at >= track.seconds - 0.5) {
         tab.tracks.remove(track);
         return;
      }

      SoundManager sounds = mc.getSoundManager();
      if (sounds.isActive(instance) || instance instanceof EntityBoundSoundInstance bound && bound.isStopped()) {
         return;
      }

      access.handykit$setVolume(track.volume);
      access.handykit$setX(track.x);
      access.handykit$setY(track.y);
      access.handykit$setZ(track.z);
      seek = (float)at;
      forced = track.sound;
      resuming = true;
      SoundEngine.PlayResult result;
      try {
         result = sounds.play(instance);
      } finally {
         seek = 0.0F;
         forced = null;
         resuming = false;
      }

      Tabs.LOGGER.info("Tab {}: {} goes on at {} s of {} s ({})", tab.title(), instance.getIdentifier(),
         String.format(Locale.ROOT, "%.1f", at), String.format(Locale.ROOT, "%.1f", track.seconds), result);
   }

   // ---------------------------------------------------------------- the sound engine, while a sound is brought back

   /** SoundEngine.play: the file picked for the sound. */
   public static Sound variant(Sound picked) {
      Sound use = forced;
      return use != null ? use : picked;
   }

   /** SoundEngine.play: a sound that starts in its middle is read as a stream, whatever its file says. */
   public static boolean seeking() {
      return seek > 0.0F;
   }

   /** SoundEngine.play: the stream of the sound's file; what has gone by is read and thrown away before it plays. */
   public static CompletableFuture<AudioStream> seek(CompletableFuture<AudioStream> stream) {
      float seconds = seek;
      return seconds <= 0.0F ? stream : stream.thenApplyAsync(opened -> skip(opened, seconds), Util.nonCriticalIoPool());
   }

   private static AudioStream skip(AudioStream stream, float seconds) {
      try {
         AudioFormat format = stream.getFormat();
         long started = System.nanoTime();
         drain(stream, (long)(seconds * format.getSampleRate()) * format.getFrameSize());
         // reading that far took a moment of its own
         double took = (System.nanoTime() - started) / 1.0E9;
         drain(stream, (long)(took * format.getSampleRate()) * format.getFrameSize());
         Tabs.trace(String.format(Locale.ROOT, "sound read ahead %.1f s in %.0f ms", seconds, took * 1000.0));
      } catch (IOException | RuntimeException e) {
         Tabs.LOGGER.warn("A sound could not be started in its middle: {}", e.toString());
      }

      return stream;
   }

   private static void drain(AudioStream stream, long bytes) throws IOException {
      while (bytes > 0L) {
         ByteBuffer got = stream.read((int)Math.min(bytes, 1L << 16));
         int count = got == null ? 0 : got.remaining();
         if (count <= 0) {
            return;
         }

         bytes -= count;
      }
   }

   // ---------------------------------------------------------------- how long a sound file is

   private static CompletableFuture<Float> length(Minecraft mc, Sound sound) {
      Identifier path = sound.getPath();
      CompletableFuture<Float> known = LENGTHS.get(path);
      if (known == null) {
         // the music may be in a server pack of the tab on screen
         ResourceManager manager = Assets.resources(mc, Assets.shown());
         known = CompletableFuture.supplyAsync(() -> seconds(manager, path), Util.nonCriticalIoPool());
         LENGTHS.put(path, known);
      }

      return known;
   }

   /** The length of an Ogg Vorbis file: the sample count on its last page over the sample rate in its first. */
   private static float seconds(ResourceManager manager, Identifier path) {
      try (InputStream in = manager.open(path)) {
         byte[] all = in.readAllBytes();
         int rate = 0;
         int limit = Math.min(all.length - 16, 4096);
         for (int i = 0; i < limit; i++) {
            if (all[i] == 1 && all[i + 1] == 'v' && all[i + 2] == 'o' && all[i + 3] == 'r' && all[i + 4] == 'b' && all[i + 5] == 'i' && all[i + 6] == 's') {
               rate = (all[i + 12] & 255) | (all[i + 13] & 255) << 8 | (all[i + 14] & 255) << 16 | (all[i + 15] & 255) << 24;
               break;
            }
         }

         if (rate <= 0) {
            return 0.0F;
         }

         for (int i = all.length - 14; i >= 0; i--) {
            if (all[i] == 'O' && all[i + 1] == 'g' && all[i + 2] == 'g' && all[i + 3] == 'S') {
               long samples = 0L;
               for (int b = 7; b >= 0; b--) {
                  samples = samples << 8 | all[i + 6 + b] & 255;
               }

               if (samples > 0L) {
                  return (float)((double)samples / rate);
               }
            }
         }
      } catch (IOException | RuntimeException e) {
         // not there, or not a file of this kind
      }

      return 0.0F;
   }

   /** For the test hook. */
   static String describe(Tab tab) {
      List<String> out = new ArrayList<>();
      SoundManager sounds = Minecraft.getInstance().getSoundManager();
      for (Track track : tab.tracks) {
         out.add(String.format(Locale.ROOT, "%s %.1f/%.1f%s", track.instance.getIdentifier(), (System.nanoTime() - track.started) / 1.0E9, track.seconds,
            sounds.isActive(track.instance) ? " playing" : ""));
      }

      return out.toString();
   }
}
