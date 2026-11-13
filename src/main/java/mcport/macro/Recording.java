package mcport.macro;

/**
 * One recorded stretch of input, one entry per client tick.
 * keys: held keys (bit i = Recorder.KEYS[i]); clicks: presses as (tick, key, count) triples in tick order;
 * yaw/pitch: look offset from the first tick (null when look was not recorded); slot: hotbar slot after each tick.
 */
public final class Recording {
   /**
    * Something the player did that is not a held or pressed key. kind: "chat" and "command" (text), "inventory"
    * (opened the own inventory), "click" (slot, button and input kind inside the container named by menu) and
    * "close" (closed the container). Added in the editor: "wait_chat" holds the playback at its tick until a chat
    * line containing text arrives (timeout seconds, 0 for as long as it takes).
    */
   public static final class Event {
      public int tick;
      public String kind;
      public String text;
      public int slot;
      public int button;
      public String input;
      public String menu;
      public int timeout;

      Event copy() {
         Event e = new Event();
         e.tick = this.tick;
         e.kind = this.kind;
         e.text = this.text;
         e.slot = this.slot;
         e.button = this.button;
         e.input = this.input;
         e.menu = this.menu;
         e.timeout = this.timeout;
         return e;
      }
   }

   public String name = "";
   public MacroConfig.Repeat repeat = MacroConfig.Repeat.ONCE;
   public int count = 3;
   public int startSlot = -1;
   public int[] keys = new int[0];
   public int[] clicks = new int[0];
   public float[] yaw;
   public float[] pitch;
   public int[] slot;
   /** In tick order; absent in recordings without any. */
   public java.util.List<Event> events;
   /** Play round after round until the player holds at least untilCount of this item ("minecraft:wheat"); null is off. */
   public String untilItem;
   public int untilCount = 1;

   public Recording() {
   }

   public int length() {
      return this.keys.length;
   }

   public boolean hasLook() {
      return this.yaw != null && this.pitch != null;
   }

   boolean valid() {
      if (this.keys == null || this.keys.length == 0) {
         return false;
      }

      int n = this.keys.length;
      if (this.clicks == null || this.clicks.length % 3 != 0) {
         this.clicks = new int[0];
      }

      if (this.yaw != null && this.yaw.length != n || this.pitch != null && this.pitch.length != n) {
         this.yaw = null;
         this.pitch = null;
      }

      if (this.slot != null && this.slot.length != n) {
         this.slot = null;
      }

      if (this.events != null) {
         this.events.removeIf(e -> e == null || e.kind == null || e.tick < 0 || e.tick >= n || e.kind.equals("click") && e.input == null);
         this.events.sort(java.util.Comparator.comparingInt(e -> e.tick));
      }

      if (this.repeat == null) {
         this.repeat = MacroConfig.Repeat.ONCE;
      }

      if (this.name == null) {
         this.name = "";
      }

      this.count = Math.clamp(this.count, 1, 9999);
      return true;
   }

   // ---------------------------------------------------------------- editing

   /** Editing can make a recording up to twice as long as the longest one that can be recorded. */
   private static final int MAX_LENGTH = Recorder.MAX_TICKS * 2;

   Recording copy() {
      Recording r = new Recording();
      r.set(this);
      return r;
   }

   /** Takes over everything of another recording (the editor writes its working copy back like this). */
   void set(Recording o) {
      this.name = o.name;
      this.repeat = o.repeat;
      this.count = o.count;
      this.startSlot = o.startSlot;
      this.keys = o.keys.clone();
      this.clicks = o.clicks.clone();
      this.yaw = o.yaw == null ? null : o.yaw.clone();
      this.pitch = o.pitch == null ? null : o.pitch.clone();
      this.slot = o.slot == null ? null : o.slot.clone();
      this.events = null;
      if (o.events != null) {
         this.events = new java.util.ArrayList<>(o.events.size());
         for (Event e : o.events) {
            this.events.add(e.copy());
         }
      }

      this.untilItem = o.untilItem;
      this.untilCount = o.untilCount;
   }

   /** Keeps the ticks from..to (to not included); the look starts over from the first kept tick. */
   void trim(int from, int to) {
      int n = this.length();
      from = Math.clamp(from, 0, n - 1);
      to = Math.clamp(to, from + 1, n);
      if (from == 0 && to == n) {
         return;
      }

      if (this.slot != null && from > 0) {
         this.startSlot = this.slot[from - 1];
      }

      this.keys = java.util.Arrays.copyOfRange(this.keys, from, to);
      if (this.slot != null) {
         this.slot = java.util.Arrays.copyOfRange(this.slot, from, to);
      }

      if (this.hasLook()) {
         float y0 = this.yaw[from];
         float p0 = this.pitch[from];
         float[] yaw = new float[to - from];
         float[] pitch = new float[to - from];
         for (int i = 0; i < yaw.length; i++) {
            yaw[i] = this.yaw[from + i] - y0;
            pitch[i] = this.pitch[from + i] - p0;
         }

         this.yaw = yaw;
         this.pitch = pitch;
      }

      it.unimi.dsi.fastutil.ints.IntArrayList clicks = new it.unimi.dsi.fastutil.ints.IntArrayList();
      for (int i = 0; i + 2 < this.clicks.length; i += 3) {
         if (this.clicks[i] >= from && this.clicks[i] < to) {
            clicks.add(this.clicks[i] - from);
            clicks.add(this.clicks[i + 1]);
            clicks.add(this.clicks[i + 2]);
         }
      }

      this.clicks = clicks.toIntArray();
      if (this.events != null) {
         int start = from;
         int end = to;
         this.events.removeIf(e -> e.tick < start || e.tick >= end);
         this.events.forEach(e -> e.tick -= start);
      }
   }

   /**
    * factor 2 halves the pauses, 0.5 doubles them. A pause is a stretch of ticks in which no key is held, nothing is
    * pressed, the hotbar slot stays and no event happens. The ticks in which something does happen stay as they
    * are: held keys cannot be hurried, so the distance walked and the time a block takes to break do not change.
    * Every pause keeps at least one tick (a key let go and pressed again stays two presses), and the look ends each
    * pause where it did. False when that changes nothing, or would make the recording too long.
    */
   boolean speed(double factor) {
      int n = this.length();
      if (n == 0 || !(factor > 0)) {
         return false;
      }

      boolean[] busy = new boolean[n];
      for (int t = 0; t < n; t++) {
         boolean slotChanged = this.slot != null && (t == 0 ? this.startSlot >= 0 && this.slot[0] != this.startSlot : this.slot[t] != this.slot[t - 1]);
         busy[t] = this.keys[t] != 0 || slotChanged;
      }

      for (int i = 0; i + 2 < this.clicks.length; i += 3) {
         if (this.clicks[i] >= 0 && this.clicks[i] < n) {
            busy[this.clicks[i]] = true;
         }
      }

      if (this.events != null) {
         for (Event e : this.events) {
            if (e.tick >= 0 && e.tick < n) {
               busy[e.tick] = true;
            }
         }
      }

      // source[i]: the old tick the new tick i is taken from; moved[t]: where the old tick t ends up
      it.unimi.dsi.fastutil.ints.IntArrayList source = new it.unimi.dsi.fastutil.ints.IntArrayList(n);
      int[] moved = new int[n];
      for (int t = 0; t < n; ) {
         if (busy[t]) {
            moved[t] = source.size();
            source.add(t++);
            continue;
         }

         int end = t;
         while (end < n && !busy[end]) {
            end++;
         }

         int length = end - t;
         int kept = (int)Math.min(MAX_LENGTH + 1L, Math.max(1L, Math.round(length / factor)));
         for (int j = 0; j < kept; j++) {
            // spread over the pause and ending on its last tick
            long at = Math.round((j + 1) * (double)length / kept) - 1;
            source.add(t + (int)Math.clamp(at, 0L, length - 1L));
         }

         for (int k = t; k < end; k++) {
            moved[k] = source.size() - 1;
         }

         t = end;
         if (source.size() > MAX_LENGTH) {
            return false;
         }
      }

      int m = source.size();
      if (m == n) {
         return false;
      }

      int[] keys = new int[m];
      int[] slot = this.slot == null ? null : new int[m];
      float[] yaw = this.hasLook() ? new float[m] : null;
      float[] pitch = this.hasLook() ? new float[m] : null;
      for (int i = 0; i < m; i++) {
         int from = source.getInt(i);
         keys[i] = this.keys[from];
         if (slot != null) {
            slot[i] = this.slot[from];
         }

         if (yaw != null) {
            yaw[i] = this.yaw[from];
            pitch[i] = this.pitch[from];
         }
      }

      this.keys = keys;
      this.slot = slot;
      this.yaw = yaw;
      this.pitch = pitch;
      for (int i = 0; i + 2 < this.clicks.length; i += 3) {
         this.clicks[i] = moved[Math.clamp(this.clicks[i], 0, n - 1)];
      }

      if (this.events != null) {
         this.events.forEach(e -> e.tick = moved[Math.clamp(e.tick, 0, n - 1)]);
      }

      return true;
   }

   /** Plays the other recording right after this one. False when the two together would be too long. */
   boolean append(Recording o) {
      int n = this.length();
      int m = o.length();
      if ((long)n + m > MAX_LENGTH) {
         return false;
      }

      int[] keys = java.util.Arrays.copyOf(this.keys, n + m);
      System.arraycopy(o.keys, 0, keys, n, m);
      if (this.slot != null && o.slot != null) {
         int[] slot = java.util.Arrays.copyOf(this.slot, n + m);
         System.arraycopy(o.slot, 0, slot, n, m);
         this.slot = slot;
      } else {
         this.slot = null;
      }

      if (this.hasLook() || o.hasLook()) {
         float[] yaw = new float[n + m];
         float[] pitch = new float[n + m];
         if (this.hasLook()) {
            System.arraycopy(this.yaw, 0, yaw, 0, n);
            System.arraycopy(this.pitch, 0, pitch, 0, n);
         }

         // the second part turns on from where the first one ended up looking
         float y0 = n > 0 ? yaw[n - 1] : 0;
         float p0 = n > 0 ? pitch[n - 1] : 0;
         for (int i = 0; i < m; i++) {
            yaw[n + i] = y0 + (o.hasLook() ? o.yaw[i] : 0);
            pitch[n + i] = p0 + (o.hasLook() ? o.pitch[i] : 0);
         }

         this.yaw = yaw;
         this.pitch = pitch;
      }

      int[] clicks = java.util.Arrays.copyOf(this.clicks, this.clicks.length + o.clicks.length);
      for (int i = 0; i + 2 < o.clicks.length; i += 3) {
         clicks[this.clicks.length + i] = o.clicks[i] + n;
         clicks[this.clicks.length + i + 1] = o.clicks[i + 1];
         clicks[this.clicks.length + i + 2] = o.clicks[i + 2];
      }

      this.clicks = clicks;
      this.keys = keys;
      if (o.events != null && !o.events.isEmpty()) {
         if (this.events == null) {
            this.events = new java.util.ArrayList<>();
         }

         for (Event e : o.events) {
            Event c = e.copy();
            c.tick += n;
            this.events.add(c);
         }
      }

      return true;
   }

   /** Plays this many times, or 0 for forever. */
   public int plays() {
      return switch (this.repeat) {
         case ONCE -> 1;
         case COUNT -> this.count;
         case LOOP -> 0;
      };
   }

   public net.minecraft.network.chat.Component lengthText() {
      return time(this.length());
   }

   /** "3.2s" under a minute, "1:05" from there. */
   static net.minecraft.network.chat.Component time(int ticks) {
      int tenths = ticks / 2;
      if (tenths >= 600) {
         int s = ticks / 20;
         return net.minecraft.network.chat.Component.literal(String.format("%d:%02d", s / 60, s % 60));
      }

      return net.minecraft.network.chat.Component.translatable("handykit.seconds", tenths / 10 + "." + tenths % 10);
   }
}
