package mcport.tabs;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where the time goes with several tabs open: frame times, what each tab behind the screen costs per tick and
 * in packets, and how long a change of tab takes. Counted only in test runs (-Dhandykit.debug=true); every
 * method here is a single boolean test otherwise.
 */
final class Perf {
   static final boolean ON = Boolean.getBoolean("handykit.debug");

   private static final class Cost {
      long ticks;
      long tickNanos;
      long tickMax;
      long packets;
      long packetNanos;
      long packetMax;
      String packetWorst = "";
   }

   private static final Map<Integer, Cost> COSTS = new HashMap<>();
   private static long lastFrame;
   private static long frames;
   private static long frameNanos;
   private static long frameMax;
   private static long slowFrames;
   /** Frames still to be watched after a change of tab (0: none). */
   private static int afterSwitch;
   private static long switchFirst;
   private static long switchMax;
   private static long switchSum;
   private static String switchName = "";

   private Perf() {
   }

   static long now() {
      return ON ? System.nanoTime() : 0L;
   }

   /** A frame that takes longer than this gets its place in the code logged (-Dhandykit.stuck=<ms>). */
   private static final long STUCK_MS = Long.getLong("handykit.stuck", 300L);
   private static volatile long frameStarted;
   private static Thread watcher;

   /** A thread that looks at the client thread whenever a frame has been going for a while, and logs where it is. */
   private static void watch(Thread client) {
      Thread thread = new Thread(() -> {
         String last = "";
         while (true) {
            try {
               Thread.sleep(Math.max(10L, STUCK_MS / 2L));
            } catch (InterruptedException e) {
               return;
            }

            long started = frameStarted;
            long stuck = System.nanoTime() - started;
            if (started == 0L || stuck < STUCK_MS * 1_000_000L) {
               last = "";
               continue;
            }

            StackTraceElement[] stack = client.getStackTrace();
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (StackTraceElement element : stack) {
               String name = element.getClassName();
               if (name.startsWith("java.lang.Thread") && shown == 0) {
                  continue;
               }

               sb.append(" < ").append(name.substring(name.lastIndexOf('.') + 1)).append('.').append(element.getMethodName()).append(':').append(element.getLineNumber());
               if (++shown >= 22) {
                  break;
               }
            }

            String text = sb.toString();
            if (!text.equals(last)) {
               last = text;
               Tabs.LOGGER.info("[tabs] perf stuck {} ms at{}", ms(stuck), text);
            }
         }
      }, "HandyKit frame watch");
      thread.setDaemon(true);
      thread.start();
      watcher = thread;
   }

   /** Once per frame drawn. */
   static void frame() {
      if (!ON) {
         return;
      }

      if (watcher == null) {
         watch(Thread.currentThread());
      }

      long now = System.nanoTime();
      frameStarted = now;
      if (lastFrame != 0L) {
         long took = now - lastFrame;
         frames++;
         frameNanos += took;
         frameMax = Math.max(frameMax, took);
         if (took > 50_000_000L) {
            slowFrames++;
         }

         if (afterSwitch > 0) {
            if (afterSwitch == 120) {
               switchFirst = took;
            }

            switchMax = Math.max(switchMax, took);
            switchSum += took;
            if (--afterSwitch == 0) {
               Tabs.LOGGER.info("[tabs] perf after switch to {}: first frame {} ms, worst {} ms, 120 frames in {} ms", switchName, ms(switchFirst), ms(switchMax), ms(switchSum));
            }
         }
      }

      lastFrame = now;
   }

   static void switched(String name) {
      if (ON) {
         afterSwitch = 120;
         switchFirst = 0L;
         switchMax = 0L;
         switchSum = 0L;
         switchName = name;
      }
   }

   static void tick(Tab tab, long started) {
      if (ON) {
         long took = System.nanoTime() - started;
         Cost cost = COSTS.computeIfAbsent(tab.id, id -> new Cost());
         cost.ticks++;
         cost.tickNanos += took;
         cost.tickMax = Math.max(cost.tickMax, took);
      }
   }

   static void packet(Tab tab, long started, Object packet) {
      if (ON) {
         long took = System.nanoTime() - started;
         Cost cost = COSTS.computeIfAbsent(tab.id, id -> new Cost());
         cost.packets++;
         cost.packetNanos += took;
         if (took > cost.packetMax) {
            cost.packetMax = took;
            cost.packetWorst = packet.getClass().getSimpleName();
         }
      }
   }

   static String ms(long nanos) {
      return String.format(Locale.ROOT, "%.2f", nanos / 1.0E6);
   }

   /** What was counted since the last report, and a fresh start. */
   static String report() {
      StringBuilder sb = new StringBuilder();
      sb.append(String.format(Locale.ROOT, "frames=%d avg=%s ms worst=%s ms over50ms=%d", frames, frames == 0 ? "-" : ms(frameNanos / frames), ms(frameMax), slowFrames));
      COSTS.forEach((id, cost) -> sb.append(String.format(
         Locale.ROOT,
         " | tab#%d behind: ticks=%d avg=%s ms worst=%s ms, packets=%d total=%s ms worst=%s ms (%s)",
         id,
         cost.ticks,
         cost.ticks == 0 ? "-" : ms(cost.tickNanos / cost.ticks),
         ms(cost.tickMax),
         cost.packets,
         ms(cost.packetNanos),
         ms(cost.packetMax),
         cost.packetWorst
      )));
      COSTS.clear();
      frames = 0L;
      frameNanos = 0L;
      frameMax = 0L;
      slowFrames = 0L;
      return sb.toString();
   }
}
