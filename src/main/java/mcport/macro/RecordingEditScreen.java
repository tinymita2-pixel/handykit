package mcport.macro;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * Editing one recording: cut it to a stretch, play it faster or slower, drop events, put another recording behind
 * it, add a "wait until the chat says ..." step and a "repeat until I hold N of an item" goal.
 *
 * The left panel is a prediction of what the recording will do, worked out from its inputs alone: the path the
 * player would walk on open ground seen from above, a marker that moves along it, and a caption for the step under
 * the cursor. It plays, pauses and steps like a storyboard. It knows nothing of walls, so it shows intent, not result.
 */
public final class RecordingEditScreen extends Screen {
   private static final int M = 10;
   private static final int LINE_Y = 34;
   private static final int LINE_H = 22;
   private static final int PANEL_Y = 74;
   // blocks per tick on flat ground
   private static final float WALK = 0.2158F;
   private static final float SPRINT = 0.2806F;
   private static final float SNEAK = 0.0647F;

   private final Screen parent;
   private final Recording original;
   private final Recording work;
   private int cursor;
   private int from;
   private int to;
   private boolean playing = true;
   private int appendIndex = -1;
   private float[] px = new float[0];
   private float[] pz = new float[0];
   /** First tick of every step: a stretch where the same keys are held, or an event happens. */
   private final IntArrayList steps = new IntArrayList();
   private EditBox name;
   private EditBox wait;
   private EditBox item;
   private EditBox count;
   private int listScroll;
   private boolean draggingLine;
   /** The path as the pixels it crosses, in the order it crosses them: where on screen, and the tick it gets there. */
   private int[] pathX = new int[0];
   private int[] pathY = new int[0];
   private int[] pathT = new int[0];
   /** How the walk is laid on the panel; worked out with the path when the recording or the size of the screen changes. */
   private float pathScale = 1.0F;
   private float pathMidX;
   private float pathMidZ;
   private int pathFor = -1;
   /** The caption of the tick under the cursor, put into words once per tick instead of once per frame. */
   private int captionTick = -1;
   private Component captionText = Component.empty();

   public RecordingEditScreen(Screen parent, Recording recording) {
      super(Component.translatable("handykit.edit.title"));
      this.parent = parent;
      this.original = recording;
      this.work = recording.copy();
      this.to = this.work.length();
      this.predict();
   }

   // ---------------------------------------------------------------- what the recording would do

   private void predict() {
      Recording r = this.work;
      int n = r.length();
      this.px = new float[n];
      this.pz = new float[n];
      float x = 0;
      float z = 0;
      this.steps.clear();
      int last = Integer.MIN_VALUE;
      int ev = 0;
      for (int t = 0; t < n; t++) {
         int mask = r.keys[t];
         boolean event = false;
         while (r.events != null && ev < r.events.size() && r.events.get(ev).tick <= t) {
            event |= r.events.get(ev).tick == t;
            ev++;
         }

         if (mask != last || event) {
            this.steps.add(t);
            last = mask;
         }

         float forward = ((mask & 1) != 0 ? 1 : 0) - ((mask & 2) != 0 ? 1 : 0);
         float left = ((mask & 4) != 0 ? 1 : 0) - ((mask & 8) != 0 ? 1 : 0);
         if (forward != 0 || left != 0) {
            float length = (float)Math.sqrt(forward * forward + left * left);
            float speed = (mask & 32) != 0 ? SNEAK : (mask & 64) != 0 && forward > 0 ? SPRINT : WALK;
            double yaw = Math.toRadians(r.hasLook() ? r.yaw[t] : 0);
            // looking along +z at the start; left of that is +x
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            x += (float)((forward * fx + left * fz) / length * speed);
            z += (float)((forward * fz - left * fx) / length * speed);
         }

         this.px[t] = x;
         this.pz[t] = z;
      }

      this.cursor = Math.clamp(this.cursor, 0, Math.max(0, n - 1));
      this.from = Math.clamp(this.from, 0, Math.max(0, n - 1));
      this.to = Math.clamp(this.to, this.from + 1, n);
      this.pathFor = -1;
      this.captionTick = -1;
   }

   /** The last step that starts at or before the tick. */
   private int stepOf(int tick) {
      int low = 0;
      int high = this.steps.size() - 1;
      while (low < high) {
         int mid = (low + high + 1) >>> 1;
         if (this.steps.getInt(mid) <= tick) {
            low = mid;
         } else {
            high = mid - 1;
         }
      }

      return low;
   }

   private static void part(List<Component> out, int mask, int bit, String key) {
      if ((mask & 1 << bit) != 0) {
         out.add(Component.translatable("handykit.edit.act." + key));
      }
   }

   /** What happens at a tick, in words: the keys held, and the events tied to it. */
   private Component caption(int tick) {
      Recording r = this.work;
      List<Component> parts = new ArrayList<>();
      if (r.events != null) {
         for (Recording.Event e : r.events) {
            if (e.tick == tick) {
               parts.add(describe(e));
            }
         }
      }

      int mask = r.keys[tick];
      part(parts, mask, 6, "sprint");
      part(parts, mask, 5, "sneak");
      part(parts, mask, 0, "forward");
      part(parts, mask, 1, "back");
      part(parts, mask, 2, "left");
      part(parts, mask, 3, "right");
      part(parts, mask, 4, "jump");
      part(parts, mask, 7, "attack");
      part(parts, mask, 8, "use");
      for (int i = 0; i + 2 < r.clicks.length; i += 3) {
         if (r.clicks[i] == tick) {
            int key = r.clicks[i + 1];
            String name = key == 7 ? "attack" : key == 8 ? "use" : key == 9 ? "pick" : key == 10 ? "drop" : key == 11 ? "swap" : null;
            if (name != null && (mask & 1 << key) == 0) {
               parts.add(Component.translatable("handykit.edit.act." + name));
            }
         }
      }

      if (parts.isEmpty()) {
         return Component.translatable("handykit.edit.act.idle");
      }

      MutableComponent text = Component.empty();
      for (int i = 0; i < parts.size(); i++) {
         if (i > 0) {
            text.append(", ");
         }

         text.append(parts.get(i));
      }

      return text;
   }

   private static Component describe(Recording.Event e) {
      String text = e.text == null ? "" : e.text;
      return switch (e.kind) {
         case "chat" -> Component.translatable("handykit.edit.ev.chat", text);
         case "command" -> Component.translatable("handykit.edit.ev.command", text);
         case "inventory" -> Component.translatable("handykit.edit.ev.inventory");
         case "click" -> Component.translatable("handykit.edit.ev.click", e.slot);
         case "close" -> Component.translatable("handykit.edit.ev.close");
         case "wait_chat" -> Component.translatable("handykit.edit.ev.wait", text);
         default -> Component.literal(e.kind);
      };
   }

   // ---------------------------------------------------------------- widgets

   @Override
   protected void init() {
      Recording r = this.work;
      this.name = new EditBox(this.font, M, 8, 200, 18, Component.empty());
      this.name.setMaxLength(64);
      this.name.setValue(r.name);
      this.name.setResponder(v -> r.name = v);
      this.addRenderableWidget(this.name);

      int y = this.height - 78;
      int x = M;
      x = this.small(x, y, 20, () -> Component.literal(this.playing ? "Ⅱ" : "▶"), () -> this.playing = !this.playing);
      x = this.small(x, y, 20, () -> Component.literal("◀"), () -> this.step(-1));
      x = this.small(x, y, 20, () -> Component.literal("▶"), () -> this.step(1));
      x += 6;
      x = this.small(x, y, 58, () -> Component.translatable("handykit.edit.from"), () -> {
         this.from = Math.min(this.cursor, this.to - 1);
      });
      x = this.small(x, y, 58, () -> Component.translatable("handykit.edit.to"), () -> {
         this.to = Math.max(this.cursor + 1, this.from + 1);
      });
      x = this.small(x, y, 48, () -> Component.translatable("handykit.edit.trim"), () -> {
         r.trim(this.from, this.to);
         this.cursor = 0;
         this.from = 0;
         this.to = r.length();
         this.predict();
      });
      x += 6;
      x = this.small(x, y, 30, () -> Component.literal("×2"), () -> this.speed(2.0));
      x = this.small(x, y, 30, () -> Component.literal("×½"), () -> this.speed(0.5));
      x += 6;
      List<Recording> all = MacroConfig.base().recordings;
      int appendW = Math.min(150, this.width - M - x - 34);
      if (all.size() > 1 && appendW >= 60) {
         x = this.small(x, y, appendW, () -> Component.translatable("handykit.edit.append", this.appendName(all)), () -> {
            do {
               this.appendIndex = (this.appendIndex + 1) % all.size();
            } while (all.get(this.appendIndex) == this.original);
         });
         this.small(x, y, 30, () -> Component.literal("+"), () -> {
            if (this.appendIndex >= 0 && this.appendIndex < all.size() && all.get(this.appendIndex) != this.original && r.append(all.get(this.appendIndex))) {
               this.to = r.length();
               this.predict();
            }
         });
      }

      y = this.height - 54;
      this.wait = new EditBox(this.font, M, y, 150, 20, Component.empty());
      this.wait.setMaxLength(64);
      this.wait.setHint(Component.translatable("handykit.edit.wait_hint").withStyle(ChatFormatting.DARK_GRAY));
      this.addRenderableWidget(this.wait);
      this.small(M + 154, y, 64, () -> Component.translatable("handykit.edit.wait_add"), () -> {
         String text = this.wait.getValue().trim();
         if (!text.isEmpty()) {
            Recording.Event e = new Recording.Event();
            e.tick = this.cursor;
            e.kind = "wait_chat";
            e.text = text;
            if (r.events == null) {
               r.events = new ArrayList<>();
            }

            r.events.add(e);
            r.events.sort(java.util.Comparator.comparingInt(v -> v.tick));
            this.wait.setValue("");
            this.predict();
         }
      });
      int ix = M + 154 + 64 + 14;
      this.item = new EditBox(this.font, ix, y, 150, 20, Component.empty());
      this.item.setMaxLength(96);
      this.item.setValue(r.untilItem == null ? "" : r.untilItem);
      this.item.setHint(Component.translatable("handykit.edit.item_hint").withStyle(ChatFormatting.DARK_GRAY));
      this.item.setResponder(v -> r.untilItem = v.isBlank() ? null : v.trim());
      this.addRenderableWidget(this.item);
      this.count = new EditBox(this.font, ix + 154, y, 40, 20, Component.empty());
      this.count.setMaxLength(5);
      this.count.setValue(Integer.toString(Math.max(1, r.untilCount)));
      this.count.setResponder(v -> {
         try {
            r.untilCount = Math.max(1, Integer.parseInt(v.trim()));
         } catch (NumberFormatException ignored) {
         }
      });
      this.addRenderableWidget(this.count);

      this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
         MacroClient.stopPlaying(this.original);
         this.original.set(this.work);
         MacroConfig.base().saveRecordings();
         this.onClose();
      }).bounds(this.width / 2 - 102, this.height - 28, 100, 20).build());
      this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose()).bounds(this.width / 2 + 2, this.height - 28, 100, 20).build());
   }

   private final List<Runnable> refreshers = new ArrayList<>();

   private int small(int x, int y, int w, java.util.function.Supplier<Component> text, Runnable press) {
      Button b = Button.builder(text.get(), btn -> {
         press.run();
         this.refreshers.forEach(Runnable::run);
      }).bounds(x, y, w, 20).build();
      this.refreshers.add(() -> b.setMessage(text.get()));
      this.addRenderableWidget(b);
      return x + w + 2;
   }

   @Override
   protected void rebuildWidgets() {
      this.refreshers.clear();
      super.rebuildWidgets();
   }

   private String appendName(List<Recording> all) {
      return this.appendIndex >= 0 && this.appendIndex < all.size() && all.get(this.appendIndex) != this.original ? all.get(this.appendIndex).name : "…";
   }

   private void speed(double factor) {
      double at = this.work.length() <= 1 ? 0 : this.cursor / (double)(this.work.length() - 1);
      if (!this.work.speed(factor)) {
         // no pause to shorten or lengthen, or it would get too long
         return;
      }

      this.from = 0;
      this.to = this.work.length();
      this.cursor = (int)Math.round(at * (this.work.length() - 1));
      this.predict();
   }

   private void step(int direction) {
      this.playing = false;
      int at = this.stepOf(this.cursor);
      if (direction < 0 && this.steps.getInt(at) < this.cursor) {
         this.cursor = this.steps.getInt(at);
      } else {
         this.cursor = this.steps.getInt(Math.clamp(at + direction, 0, this.steps.size() - 1));
      }
   }

   @Override
   public void tick() {
      if (this.playing && this.work.length() > 1) {
         this.cursor++;
         if (this.cursor >= this.to) {
            this.cursor = this.from;
         }
      }
   }

   // ---------------------------------------------------------------- mouse

   private int lineW() {
      return this.width - M * 2;
   }

   private int tickAt(double mx) {
      int n = this.work.length();
      return Math.clamp((int)((mx - M) / this.lineW() * n), 0, n - 1);
   }

   private int listX() {
      return this.width / 2 + 5;
   }

   private int panelBottom() {
      return this.height - 88;
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      double mx = event.x();
      double my = event.y();
      if (my >= LINE_Y && my < LINE_Y + LINE_H && mx >= M && mx < this.width - M) {
         this.playing = false;
         this.draggingLine = true;
         this.cursor = this.tickAt(mx);
         return true;
      }

      List<Recording.Event> events = this.work.events;
      if (events != null && mx >= this.listX() && mx < this.width - M && my >= PANEL_Y && my < this.panelBottom()) {
         int row = this.listScroll + (int)((my - PANEL_Y - 2) / 11);
         if (row >= 0 && row < events.size()) {
            if (mx >= this.width - M - 12) {
               events.remove(row);
               this.predict();
            } else {
               this.playing = false;
               this.cursor = Math.clamp(events.get(row).tick, 0, this.work.length() - 1);
            }
         }

         return true;
      }

      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
      if (this.draggingLine) {
         this.cursor = this.tickAt(event.x());
         return true;
      }

      return super.mouseDragged(event, dx, dy);
   }

   @Override
   public boolean mouseReleased(MouseButtonEvent event) {
      this.draggingLine = false;
      return super.mouseReleased(event);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
      List<Recording.Event> events = this.work.events;
      if (events != null && mouseX >= this.listX()) {
         int visible = Math.max(1, (this.panelBottom() - PANEL_Y - 4) / 11);
         this.listScroll = Math.clamp(this.listScroll - (int)Math.signum(vertical), 0, Math.max(0, events.size() - visible));
         return true;
      }

      return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
   }

   @Override
   public void onClose() {
      this.minecraft.gui.setScreen(this.parent);
   }

   // ---------------------------------------------------------------- drawing

   private int lineX(int tick) {
      return M + (int)((long)tick * this.lineW() / Math.max(1, this.work.length()));
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      super.extractRenderState(g, mouseX, mouseY, a);
      Recording r = this.work;
      int n = r.length();
      g.text(this.font, Recording.time(n), M + 208, 13, 0xFFB0B0B0, false);

      // the line of time: movement, attack and use as three bands, events as marks, the kept stretch bright
      g.fill(M, LINE_Y, this.width - M, LINE_Y + LINE_H, 0xA0101010);
      int w = this.lineW();
      // one rectangle per stretch of columns that look alike, not one per column
      int[] began = new int[3];
      int[] shade = new int[3];
      for (int x = 0; x <= w; x++) {
         int move = 0;
         int attack = 0;
         int use = 0;
         if (x < w) {
            int t = Math.min(n - 1, (int)((long)x * n / w));
            int mask = r.keys[t];
            boolean kept = t >= this.from && t < this.to;
            move = (mask & 0x7F) == 0 ? 0 : kept ? 0xFF5090F0 : 0xFF304870;
            attack = (mask & 1 << 7) == 0 ? 0 : kept ? 0xFFF05050 : 0xFF703030;
            use = (mask & 1 << 8) == 0 ? 0 : kept ? 0xFF50D070 : 0xFF306040;
         }

         for (int band = 0; band < 3; band++) {
            int now = band == 0 ? move : band == 1 ? attack : use;
            if (now != shade[band]) {
               if (shade[band] != 0) {
                  g.fill(M + began[band], LINE_Y + 2 + band * 6, M + x, LINE_Y + 7 + band * 6, shade[band]);
               }

               began[band] = x;
               shade[band] = now;
            }
         }
      }

      for (int i = 0; i + 2 < r.clicks.length; i += 3) {
         int band = r.clicks[i + 1] == 7 ? 8 : r.clicks[i + 1] == 8 ? 14 : -1;
         if (band >= 0) {
            int x = this.lineX(r.clicks[i]);
            g.fill(x, LINE_Y + band, x + 2, LINE_Y + band + 5, band == 8 ? 0xFFFF9090 : 0xFF90FFB0);
         }
      }

      if (r.events != null) {
         for (Recording.Event e : r.events) {
            int x = this.lineX(e.tick);
            g.fill(x, LINE_Y, x + 1, LINE_Y + LINE_H, "wait_chat".equals(e.kind) ? 0xFFFF9040 : 0xFFFFD040);
         }
      }

      g.fill(this.lineX(this.from) - 1, LINE_Y - 2, this.lineX(this.from), LINE_Y + LINE_H + 2, 0xFFFFFFFF);
      g.fill(this.lineX(this.to), LINE_Y - 2, this.lineX(this.to) + 1, LINE_Y + LINE_H + 2, 0xFFFFFFFF);
      int cx = this.lineX(this.cursor);
      g.fill(cx, LINE_Y - 3, cx + 1, LINE_Y + LINE_H + 3, 0xFFFFE080);

      // the step under the cursor, in words
      int step = this.stepOf(this.cursor);
      int stepEnd = step + 1 < this.steps.size() ? this.steps.getInt(step + 1) : n;
      Component head = Component.translatable("handykit.edit.step", step + 1, this.steps.size()).withStyle(ChatFormatting.GRAY);
      g.text(this.font, head, M, 61, -1, false);
      int hx = M + this.font.width(head) + 8;
      if (this.captionTick != this.cursor) {
         this.captionText = this.caption(this.cursor);
         this.captionTick = this.cursor;
      }

      Component what = this.captionText;
      g.text(this.font, what, hx, 61, 0xFFFFFFFF, false);
      g.text(this.font, Recording.time(stepEnd - this.steps.getInt(step)), hx + this.font.width(what) + 8, 61, 0xFF909090, false);

      this.drawPrediction(g);
      this.drawEvents(g, mouseX, mouseY);
      g.text(this.font, Component.translatable("handykit.edit.until"), this.item.getX() + 154 + 44, this.item.getY() + 6, 0xFFB0B0B0, false);
   }

   /** The walk the inputs amount to, seen from above with the starting direction pointing up. */
   private void drawPrediction(GuiGraphicsExtractor g) {
      int x0 = M;
      int y0 = PANEL_Y;
      int x1 = this.width / 2 - 5;
      int y1 = this.panelBottom();
      g.fill(x0, y0, x1, y1, 0xA0101010);
      int n = this.work.length();
      int cx = (x0 + x1) / 2;
      int cy = (y0 + y1) / 2;
      if (this.pathFor != this.width * 65536 + this.height) {
         this.layPath(x1 - x0, y1 - y0, cx, cy);
         this.pathFor = this.width * 65536 + this.height;
      }

      float scale = this.pathScale;
      float midX = this.pathMidX;
      float midZ = this.pathMidZ;
      // one block squares, faint
      if (scale >= 3) {
         for (float gx = (float)Math.floor(midX - (x1 - x0) / 2 / scale); gx * scale < midX * scale + (x1 - x0) / 2; gx++) {
            int sx = cx - (int)((gx - midX) * scale);
            if (sx > x0 && sx < x1) {
               g.fill(sx, y0, sx + 1, y1, 0x18FFFFFF);
            }
         }

         for (float gz = (float)Math.floor(midZ - (y1 - y0) / 2 / scale); gz * scale < midZ * scale + (y1 - y0) / 2; gz++) {
            int sy = cy - (int)((gz - midZ) * scale);
            if (sy > y0 && sy < y1) {
               g.fill(x0, sy, x1, sy + 1, 0x18FFFFFF);
            }
         }
      }

      // the path: straight stretches of one colour go out as one rectangle each
      int runX0 = 0;
      int runY0 = 0;
      int runX1 = 0;
      int runY1 = 0;
      int runColor = 0;
      for (int i = 0; i < this.pathX.length; i++) {
         int sx = this.pathX[i];
         int sy = this.pathY[i];
         int at = this.pathT[i];
         boolean kept = at >= this.from && at < this.to;
         int color = !kept ? 0xFF404040 : at <= this.cursor ? 0xFFFFE080 : 0xFF6890C0;
         if (color == runColor) {
            boolean flat = runY1 - runY0 == 2;
            boolean tall = runX1 - runX0 == 2;
            if (flat && sy == runY0 && sx == runX1 - 1) {
               runX1 = sx + 2;
               continue;
            }

            if (flat && sy == runY0 && sx == runX0 - 1) {
               runX0 = sx;
               continue;
            }

            if (tall && sx == runX0 && sy == runY1 - 1) {
               runY1 = sy + 2;
               continue;
            }

            if (tall && sx == runX0 && sy == runY0 - 1) {
               runY0 = sy;
               continue;
            }
         }

         if (runColor != 0) {
            g.fill(runX0, runY0, runX1, runY1, runColor);
         }

         runX0 = sx;
         runY0 = sy;
         runX1 = sx + 2;
         runY1 = sy + 2;
         runColor = color;
      }

      if (runColor != 0) {
         g.fill(runX0, runY0, runX1, runY1, runColor);
      }

      Recording r = this.work;
      for (int i = 0; i + 2 < r.clicks.length; i += 3) {
         int t = Math.min(n - 1, r.clicks[i]);
         int color = r.clicks[i + 1] == 7 ? 0xFFF05050 : r.clicks[i + 1] == 8 ? 0xFF50D070 : 0;
         if (color != 0) {
            int sx = cx - Math.round((this.px[t] - midX) * scale);
            int sy = cy - Math.round((this.pz[t] - midZ) * scale);
            g.fill(sx - 1, sy - 1, sx + 3, sy + 3, color);
         }
      }

      if (r.events != null) {
         for (Recording.Event e : r.events) {
            int t = Math.clamp(e.tick, 0, n - 1);
            int sx = cx - Math.round((this.px[t] - midX) * scale);
            int sy = cy - Math.round((this.pz[t] - midZ) * scale);
            g.fill(sx - 2, sy - 2, sx + 4, sy + 4, 0xFF000000);
            g.fill(sx - 1, sy - 1, sx + 3, sy + 3, "wait_chat".equals(e.kind) ? 0xFFFF9040 : 0xFFFFD040);
         }
      }

      // the player where the cursor is, with a line the way it looks
      int t = Math.clamp(this.cursor, 0, n - 1);
      int sx = cx - Math.round((this.px[t] - midX) * scale);
      int sy = cy - Math.round((this.pz[t] - midZ) * scale);
      double yaw = Math.toRadians(r.hasLook() ? r.yaw[t] : 0);
      for (int i = 2; i <= 8; i++) {
         int lx = sx + 1 + (int)Math.round(Math.sin(yaw) * i);
         int ly = sy + 1 - (int)Math.round(Math.cos(yaw) * i);
         g.fill(lx, ly, lx + 1, ly + 1, 0xFFFFFFFF);
      }

      g.fill(sx - 2, sy - 2, sx + 4, sy + 4, 0xFF000000);
      g.fill(sx - 1, sy - 1, sx + 3, sy + 3, 0xFFFFFFFF);
      // + 0 turns a negative zero into a plain one
      float ahead = (n == 0 ? 0 : this.pz[n - 1]) + 0.0F;
      float aside = (n == 0 ? 0 : -this.px[n - 1]) + 0.0F;
      g.text(this.font, Component.translatable("handykit.edit.predicted", String.format("%.1f", ahead), String.format("%.1f", aside)), x0 + 4, y1 - 11, 0xFF909090, false);
   }

   /**
    * Where the walk falls on a panel of this size with this middle, and the pixels it crosses in order: one point
    * per pixel it enters, not one per tick (an hour of standing still is one point).
    */
   private void layPath(int width, int height, int cx, int cy) {
      int n = this.work.length();
      float minX = 0;
      float maxX = 0;
      float minZ = 0;
      float maxZ = 0;
      for (int t = 0; t < n; t++) {
         minX = Math.min(minX, this.px[t]);
         maxX = Math.max(maxX, this.px[t]);
         minZ = Math.min(minZ, this.pz[t]);
         maxZ = Math.max(maxZ, this.pz[t]);
      }

      float span = Math.max(4.0F, Math.max(maxX - minX, maxZ - minZ));
      this.pathScale = (Math.min(width, height) - 24) / span;
      this.pathMidX = (minX + maxX) / 2;
      this.pathMidZ = (minZ + maxZ) / 2;
      IntArrayList xs = new IntArrayList();
      IntArrayList ys = new IntArrayList();
      IntArrayList ts = new IntArrayList();
      int lastX = Integer.MIN_VALUE;
      int lastY = 0;
      for (int t = 0; t < n; t++) {
         // +x is to the left of the starting direction, +z ahead: left on screen, up on screen
         int sx = cx - Math.round((this.px[t] - this.pathMidX) * this.pathScale);
         int sy = cy - Math.round((this.pz[t] - this.pathMidZ) * this.pathScale);
         if (sx != lastX || sy != lastY) {
            xs.add(sx);
            ys.add(sy);
            ts.add(t);
            lastX = sx;
            lastY = sy;
         }
      }

      this.pathX = xs.toIntArray();
      this.pathY = ys.toIntArray();
      this.pathT = ts.toIntArray();
   }

   private void drawEvents(GuiGraphicsExtractor g, int mouseX, int mouseY) {
      int x0 = this.listX();
      int x1 = this.width - M;
      int y1 = this.panelBottom();
      g.fill(x0, PANEL_Y, x1, y1, 0xA0101010);
      List<Recording.Event> events = this.work.events;
      if (events == null || events.isEmpty()) {
         return;
      }

      int visible = Math.max(1, (y1 - PANEL_Y - 4) / 11);
      this.listScroll = Math.clamp(this.listScroll, 0, Math.max(0, events.size() - visible));
      for (int i = this.listScroll; i < events.size() && i < this.listScroll + visible; i++) {
         Recording.Event e = events.get(i);
         int y = PANEL_Y + 2 + (i - this.listScroll) * 11;
         boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y + 11;
         boolean now = e.tick == this.cursor;
         if (hover || now) {
            g.fill(x0 + 1, y, x1 - 1, y + 11, now ? 0xFF505030 : 0xFF303030);
         }

         Component time = Recording.time(e.tick);
         g.text(this.font, time, x0 + 4, y + 2, 0xFF909090, false);
         String text = this.font.plainSubstrByWidth(describe(e).getString(), x1 - x0 - 60);
         g.text(this.font, text, x0 + 40, y + 2, "wait_chat".equals(e.kind) ? 0xFFFFB060 : 0xFFE0E0E0, false);
         if (hover) {
            g.text(this.font, "✕", x1 - 10, y + 2, mouseX >= x1 - 12 ? 0xFFFF6060 : 0xFFA0A0A0, false);
         }
      }
   }
}
