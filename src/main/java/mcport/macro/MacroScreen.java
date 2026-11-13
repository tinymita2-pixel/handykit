package mcport.macro;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import mcport.logviewer.LogScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/** Macro settings: auto click, chat presets, keyword replies and recordings, plus a way into the log viewer. */
public final class MacroScreen extends Screen {
   private static final int ROW = 24;
   private static final int TOP = 12;
   private static final int SIDE = 78;
   private static final int GAP = 4;
   private static final int KEY_W = 76;
   private static final double[] CPS = {0.5, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 16, 18, 20};
   private static final double[] DELAY = {0, 0.2, 0.5, 1, 1.5, 2, 3, 5, 10};
   private static final double[] COOLDOWN = {0, 1, 2, 3, 5, 10, 15, 30, 60, 120, 300};
   private static final int MAX_RULES = 50;

   enum Tab {
      CLICK,
      PRESETS,
      REPLY,
      RECORD,
      IGNORE,
      TABS
   }

   private static Tab savedTab = Tab.CLICK;
   private static final Map<Tab, Integer> SAVED_SCROLL = new EnumMap<>(Tab.class);

   private record Label(int x, Supplier<Component> text, int color, boolean right) {
   }

   private record Placed(AbstractWidget widget, @Nullable BooleanSupplier when) {
   }

   private static final class Row {
      final int y;
      final List<Placed> widgets = new ArrayList<>();
      final List<Label> labels = new ArrayList<>();

      Row(int y) {
         this.y = y;
      }
   }

   private record KeyButton(Button button, KeyMapping key, @Nullable Component prefix) {
   }

   private final @Nullable Screen parent;
   private Tab tab;
   private final List<Row> rows = new ArrayList<>();
   private final List<KeyButton> keyButtons = new ArrayList<>();
   private final List<Button> tabButtons = new ArrayList<>();
   private final List<Runnable> refreshers = new ArrayList<>();
   private @Nullable KeyMapping capturing;
   private int scroll;
   private int x0;
   private int w;
   private int bottom;
   private boolean dirty;
   private boolean recordingsDirty;
   /** The settings key is still down from the press that opened this screen: its repeats do not close it again. */
   private boolean openKeyHeld;

   public MacroScreen(@Nullable Screen parent) {
      super(Component.translatable("handykit.title"));
      this.parent = parent;
      this.openKeyHeld = Keys.held(MacroClient.SETTINGS);
      this.tab = savedTab == Tab.IGNORE && !Features.IGNORE ? Tab.CLICK : savedTab;
      this.scroll = SAVED_SCROLL.getOrDefault(this.tab, 0);
   }

   public boolean capturingKey() {
      return this.capturing != null;
   }

   /**
    * The settings on this screen: those of the server on screen when it was laid out (its own profile, or the shared
    * ones). Kept, so that what was changed is saved where it was changed, also when that server is gone by then.
    */
   private MacroConfig editing;

   private MacroConfig config() {
      if (this.editing == null) {
         this.editing = MacroConfig.get();
      }

      return this.editing;
   }

   // ---------------------------------------------------------------- layout

   @Override
   protected void init() {
      MacroConfig now = MacroConfig.get();
      if (this.editing != null && this.editing != now) {
         // other settings from here on (the profile was turned on or off, the server changed): the old ones are written first
         this.save();
      }

      this.editing = now;
      this.rows.clear();
      this.keyButtons.clear();
      this.tabButtons.clear();
      this.refreshers.clear();
      // sections down the left side, their settings to the right
      int left = 8 + SIDE + 14;
      this.w = Math.min(this.width - left - 14, 440);
      this.x0 = left + Math.max(0, (this.width - left - 14 - this.w) / 2);
      this.bottom = this.height - 32;

      int ty = TOP + 4;
      for (Tab t : Tab.values()) {
         if (t == Tab.IGNORE && !Features.IGNORE) {
            continue;
         }

         Button b = Button.builder(Component.translatable("handykit.tab." + t.name().toLowerCase(java.util.Locale.ROOT)), btn -> this.switchTab(t))
            .bounds(8, ty, SIDE, 20)
            .build();
         this.tabButtons.add(this.addRenderableWidget(b));
         ty += 22;
      }

      this.addRenderableWidget(Button.builder(Component.translatable("handykit.tab.log"), btn -> this.minecraft.gui.setScreen(new LogScreen(this)))
         .bounds(8, ty + 6, SIDE, 20)
         .build());
      String server = mcport.tabs.Tabs.contextAddress();
      if (server != null) {
         // this server gets its own copy of the settings (presets, replies, ignore list, clickers), or shares the common ones
         boolean own = MacroConfig.hasProfile(server);
         this.addRenderableWidget(Button.builder(labeled("handykit.profile", onOff(own)), btn -> {
            this.save();
            if (own) {
               MacroConfig.removeProfile(server);
            } else {
               MacroConfig.createProfile(server);
            }

            this.rebuildWidgets();
         }).bounds(8, this.height - 26, SIDE + 40, 20).build());
      }

      this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> this.onClose()).bounds(this.x0 + this.w / 2 - 75, this.height - 26, 150, 20).build());

      switch (this.tab) {
         case CLICK -> this.buildClick();
         case PRESETS -> this.buildPresets();
         case REPLY -> this.buildReply();
         case RECORD -> this.buildRecord();
         case IGNORE -> this.buildIgnore();
         case TABS -> this.buildTabs();
      }

      this.refreshKeys();
      this.layoutRows();
   }

   private void switchTab(Tab t) {
      if (t == this.tab) {
         return;
      }

      SAVED_SCROLL.put(this.tab, this.scroll);
      this.tab = t;
      this.scroll = SAVED_SCROLL.getOrDefault(t, 0);
      this.capturing = null;
      this.rebuildWidgets();
   }

   private Row row() {
      Row r = new Row(this.rows.size() * ROW);
      this.rows.add(r);
      return r;
   }

   private <T extends AbstractWidget> T add(Row row, T widget) {
      return this.add(row, widget, null);
   }

   private <T extends AbstractWidget> T add(Row row, T widget, @Nullable BooleanSupplier when) {
      this.addRenderableWidget(widget);
      row.widgets.add(new Placed(widget, when));
      return widget;
   }

   private int contentHeight() {
      return this.rows.size() * ROW;
   }

   private int rowTop(Row row) {
      return TOP + 4 + row.y - this.scroll;
   }

   private boolean shown(Row row) {
      int top = this.rowTop(row);
      return top >= TOP && top + ROW <= this.bottom + 2;
   }

   private void layoutRows() {
      this.scroll = Math.clamp(this.scroll, 0, Math.max(0, this.contentHeight() + 8 - (this.bottom - TOP)));
      for (Row row : this.rows) {
         int top = this.rowTop(row);
         boolean shown = this.shown(row);
         for (Placed p : row.widgets) {
            p.widget().setY(top + 2);
            p.widget().visible = shown && (p.when() == null || p.when().getAsBoolean());
         }
      }
   }

   private void markDirty() {
      this.dirty = true;
   }

   // ---------------------------------------------------------------- widgets

   private Button button(int x, int width, Supplier<Component> message, Runnable press) {
      Button b = Button.builder(message.get(), btn -> {
         press.run();
         btn.setMessage(message.get());
      }).bounds(x, 0, width, 20).build();
      this.refreshers.add(() -> b.setMessage(message.get()));
      return b;
   }

   private static Component onOff(boolean on) {
      return on ? CommonComponents.OPTION_ON.copy().withStyle(ChatFormatting.GREEN) : CommonComponents.OPTION_OFF.copy().withStyle(ChatFormatting.GRAY);
   }

   private static Component labeled(String key, Component value) {
      return Component.translatable(key).append(": ").append(value);
   }

   private EditBox edit(int x, int width, String value, int maxLength, java.util.function.Consumer<String> onChange) {
      EditBox box = new EditBox(this.font, x, 0, width, 20, Component.empty());
      box.setMaxLength(maxLength);
      box.setValue(value);
      box.setResponder(onChange);
      return box;
   }

   private Button keyButton(int x, int width, KeyMapping key, @Nullable Component prefix) {
      Button b = Button.builder(Component.empty(), btn -> {
         this.capturing = key;
         this.refreshKeys();
      }).bounds(x, 0, width, 20).build();
      this.keyButtons.add(new KeyButton(b, key, prefix));
      return b;
   }

   private void refreshKeys() {
      for (KeyButton kb : this.keyButtons) {
         MutableComponent name = kb.key().getTranslatedKeyMessage().copy();
         Component text;
         if (kb.key() == this.capturing) {
            text = Component.literal("> ").append(name.withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE)).append(" <").withStyle(ChatFormatting.YELLOW);
         } else {
            if (this.conflicts(kb.key())) {
               name.withStyle(ChatFormatting.RED);
            }

            text = kb.prefix() == null ? name : kb.prefix().copy().append(": ").append(name);
         }

         kb.button().setMessage(text);
      }
   }

   private boolean conflicts(KeyMapping key) {
      if (key.isUnbound()) {
         return false;
      }

      for (KeyMapping other : this.minecraft.options.keyMappings) {
         // same rule as vanilla's controls screen: two keys left on their defaults (V and F3+V, ...) are not a clash
         if (other != key && other.same(key) && (!other.isDefault() || !key.isDefault())) {
            return true;
         }
      }

      return false;
   }

   private void refreshAll() {
      this.refreshers.forEach(Runnable::run);
      this.refreshKeys();
   }

   // ---------------------------------------------------------------- tabs

   private void buildClick() {
      int labelW = Math.max(this.font.width(Component.translatable("handykit.click.left")), this.font.width(Component.translatable("handykit.click.right"))) + 10;
      for (AutoClicker.Side side : new AutoClicker.Side[]{MacroClient.CLICKER.left, MacroClient.CLICKER.right}) {
         MacroConfig.Clicker c = side.config();
         Row r = this.row();
         int x = this.x0;
         r.labels.add(new Label(x, () -> Component.translatable(side.left ? "handykit.click.left" : "handykit.click.right"), 0xFFFFFFFF, false));
         x += labelW;
         this.add(r, this.button(x, 50, () -> onOff(side.on), () -> MacroClient.CLICKER.toggle(this.minecraft, side)));
         x += 50 + GAP;
         MacroConfig.ClickMode[] modes = side.left ? MacroConfig.ClickMode.values() : new MacroConfig.ClickMode[]{MacroConfig.ClickMode.CLICK, MacroConfig.ClickMode.HOLD};
         StepSlider[] slider = new StepSlider[1];
         this.add(r, this.button(x, 86, () -> Component.translatable("handykit.mode." + c.mode.name().toLowerCase(java.util.Locale.ROOT)), () -> {
            int i = java.util.Arrays.asList(modes).indexOf(c.mode);
            c.mode = modes[(i + 1) % modes.length];
            slider[0].active = c.mode == MacroConfig.ClickMode.CLICK;
            this.markDirty();
         }));
         x += 86 + GAP;
         int keyX = this.x0 + this.w - KEY_W;
         slider[0] = this.add(r, new StepSlider(x, keyX - GAP - x, CPS, c.cps, v -> Component.translatable("handykit.click.cps", MacroClient.cps(v)), v -> {
            c.cps = v;
            this.markDirty();
         }));
         slider[0].active = c.mode == MacroConfig.ClickMode.CLICK;
         this.add(r, this.keyButton(keyX, KEY_W, side.left ? MacroClient.LEFT : MacroClient.RIGHT, null));
      }
   }

   private void buildPresets() {
      MacroConfig config = this.config();
      Row top = this.row();
      this.add(top, this.button(this.x0, this.w, () -> labeled("handykit.unsigned", onOff(config.unsignedChat)), () -> {
         config.unsignedChat = !config.unsignedChat;
         this.markDirty();
         if (!config.unsignedChat && this.minecraft.getConnection() != null) {
            // signing can start right away; dropping an already open signed session needs a rejoin
            this.minecraft.getConnection().prepareKeyPair();
         }
      }));
      List<String> presets = config.presets;
      int numW = this.font.width("10") + 6;
      for (int i = 0; i < presets.size(); i++) {
         int index = i;
         Row r = this.row();
         r.labels.add(new Label(this.x0 + numW - 6, () -> Component.literal(Integer.toString(index + 1)), 0xFFA0A0A0, true));
         int boxX = this.x0 + numW;
         int keyX = this.x0 + this.w - KEY_W;
         this.add(r, this.edit(boxX, keyX - GAP - boxX, presets.get(i), 256, v -> {
            presets.set(index, v);
            this.markDirty();
         }));
         this.add(r, this.keyButton(keyX, KEY_W, MacroClient.PRESET[i], null));
      }
   }

   private void buildReply() {
      MacroConfig config = this.config();
      Row top = this.row();
      int toggleW = 110;
      this.add(top, this.button(this.x0, toggleW, () -> labeled("handykit.reply.on", onOff(config.replyOn)), () -> {
         config.replyOn = !config.replyOn;
         this.markDirty();
      }));
      int sx = this.x0 + toggleW + GAP;
      int sw = (this.x0 + this.w - sx - GAP) / 2;
      this.add(top, new StepSlider(sx, sw, DELAY, config.replyDelay, v -> Component.translatable("handykit.reply.delay", MacroClient.cps(v)), v -> {
         config.replyDelay = v;
         this.markDirty();
      }));
      this.add(top, new StepSlider(sx + sw + GAP, this.x0 + this.w - (sx + sw + GAP), COOLDOWN, config.replyCooldown,
         v -> Component.translatable("handykit.reply.cooldown", MacroClient.cps(v)), v -> {
            config.replyCooldown = v;
            this.markDirty();
         }));

      int modeW = 50;
      int flex = this.w - 20 - modeW - 20 - GAP * 4;
      int keywordW = flex * 2 / 5;
      for (MacroConfig.Rule rule : config.rules) {
         Row r = this.row();
         int x = this.x0;
         this.add(r, this.button(x, 20, () -> rule.on ? Component.literal("✔").withStyle(ChatFormatting.GREEN) : Component.literal("✖").withStyle(ChatFormatting.DARK_GRAY), () -> {
            rule.on = !rule.on;
            this.markDirty();
         }));
         x += 20 + GAP;
         EditBox keyword = this.add(r, this.edit(x, keywordW, rule.keyword, 128, v -> {
            rule.keyword = v;
            this.markDirty();
         }));
         keyword.setHint(Component.translatable("handykit.reply.keyword").withStyle(ChatFormatting.DARK_GRAY));
         x += keywordW + GAP;
         this.add(r, this.button(x, modeW, () -> Component.translatable(rule.exact ? "handykit.reply.exact" : "handykit.reply.contains"), () -> {
            rule.exact = !rule.exact;
            this.markDirty();
         }));
         x += modeW + GAP;
         int replyW = this.x0 + this.w - 20 - GAP - x;
         EditBox reply = this.add(r, this.edit(x, replyW, rule.reply, 256, v -> {
            rule.reply = v;
            this.markDirty();
         }));
         reply.setHint(Component.translatable("handykit.reply.reply").withStyle(ChatFormatting.DARK_GRAY));
         this.add(r, this.button(this.x0 + this.w - 20, 20, () -> Component.literal("✕").withStyle(ChatFormatting.RED), () -> {
            config.rules.remove(rule);
            this.markDirty();
            this.rebuildWidgets();
         }));
      }

      if (config.rules.size() < MAX_RULES) {
         Row r = this.row();
         this.add(r, this.button(this.x0, 80, () -> Component.translatable("handykit.reply.add"), () -> {
            config.rules.add(new MacroConfig.Rule());
            this.markDirty();
            this.scroll = Integer.MAX_VALUE;
            this.rebuildWidgets();
         }));
      }
   }

   /** Server tabs: which tabs are heard, the account new tabs log in with, the key to the next tab. */
   private void buildTabs() {
      // everything here is the same for every server, whatever profile is open
      MacroConfig shared = MacroConfig.base();
      int half = (this.w - GAP) / 2;
      Row sound = this.row();
      this.add(sound, this.button(this.x0, half,
         () -> labeled("handykit.tabs.sound", Component.translatable("handykit.tabs.sound." + shared.tabSound.name().toLowerCase(java.util.Locale.ROOT))), () -> {
            MacroConfig.TabSound[] all = MacroConfig.TabSound.values();
            shared.tabSound = all[(shared.tabSound.ordinal() + 1) % all.length];
            shared.save();
            // the volume button beside it shows only for "quieter"
            this.layoutRows();
         }));
      this.add(sound, this.button(this.x0 + half + GAP, half,
         () -> labeled("handykit.tabs.quiet", Component.literal(Math.round(shared.tabQuietVolume * 100) + "%")), () -> {
            int now = Math.round(shared.tabQuietVolume * 100);
            int next = 10;
            for (int step : new int[]{10, 20, 30, 50, 70}) {
               if (step > now) {
                  next = step;
                  break;
               }
            }

            shared.tabQuietVolume = next / 100.0F;
            shared.save();
         }), () -> shared.tabSound == MacroConfig.TabSound.QUIET);
      Row account = this.row();
      this.add(account, this.button(this.x0, half, () -> labeled("handykit.tabs.account", Component.literal(mcport.tabs.Tabs.nextAccountName())),
         () -> this.minecraft.gui.setScreen(new mcport.tabs.AccountScreen(this))));
      this.add(account, this.keyButton(this.x0 + half + GAP, half, mcport.tabs.TabsClient.NEXT, Component.translatable("key.handykit.tab.next")));

      Row load = this.row();
      this.add(load, this.button(this.x0, half,
         () -> labeled("handykit.tabs.back_view", shared.tabBackgroundView <= 0 ? CommonComponents.OPTION_OFF : Component.literal(Integer.toString(shared.tabBackgroundView))), () -> {
            shared.tabBackgroundView = next(shared.tabBackgroundView, 0, 2, 4, 6, 8);
            shared.save();
         }));
      this.add(load, this.keyButton(this.x0 + half + GAP, half, mcport.memo.Memo.KEY, Component.translatable("key.handykit.memo")));

      Row alert = this.row();
      int third = (this.w - GAP * 2) / 3;
      this.add(alert, this.button(this.x0, third, () -> labeled("handykit.tabs.alert_whisper", onOff(shared.tabAlertWhisper)), () -> {
         shared.tabAlertWhisper = !shared.tabAlertWhisper;
         shared.save();
      }));
      this.add(alert, this.button(this.x0 + third + GAP, third, () -> labeled("handykit.tabs.alert_mention", onOff(shared.tabAlertMention)), () -> {
         shared.tabAlertMention = !shared.tabAlertMention;
         shared.save();
      }));
      this.add(alert, this.button(this.x0 + (third + GAP) * 2, third, () -> labeled("handykit.tabs.alert_sound", onOff(shared.tabAlertSound)), () -> {
         shared.tabAlertSound = !shared.tabAlertSound;
         shared.save();
      }));
      Row words = this.row();
      EditBox wordBox = this.add(words, this.edit(this.x0, this.w, shared.tabAlertWords, 256, v -> {
         shared.tabAlertWords = v;
         this.sharedDirty = true;
      }));
      wordBox.setHint(Component.translatable("handykit.tabs.alert_words").withStyle(ChatFormatting.DARK_GRAY));

      Row rejoin = this.row();
      this.add(rejoin, this.button(this.x0, third, () -> labeled("handykit.tabs.rejoin", onOff(shared.tabReconnect)), () -> {
         shared.tabReconnect = !shared.tabReconnect;
         shared.save();
         this.layoutRows();
      }));
      this.add(rejoin, this.button(this.x0 + third + GAP, third, () -> Component.translatable("handykit.tabs.rejoin_tries", shared.tabReconnectTries), () -> {
         shared.tabReconnectTries = next(shared.tabReconnectTries, 3, 5, 10, 20, 50);
         shared.save();
      }), () -> shared.tabReconnect);
      this.add(rejoin, this.button(this.x0 + (third + GAP) * 2, third, () -> Component.translatable("handykit.tabs.rejoin_delay", shared.tabReconnectDelay), () -> {
         shared.tabReconnectDelay = next(shared.tabReconnectDelay, 3, 5, 10, 30, 60);
         shared.save();
      }), () -> shared.tabReconnect);

      Row keep = this.row();
      Button saveSet = this.add(keep, this.button(this.x0, half, () -> Component.translatable("handykit.tabs.save_set"), () -> {
         MacroConfig.TabSet set = mcport.tabs.Tabs.snapshot(Component.translatable("handykit.tabs.set_name", shared.tabSets.size() + 1).getString());
         if (!set.tabs.isEmpty()) {
            shared.tabSets.add(set);
            shared.save();
            this.rebuildWidgets();
         }
      }));
      saveSet.active = !mcport.tabs.Tabs.tabs().isEmpty();
      int openW = 44;
      int autoW = 96;
      int setNameW = this.w - openW - autoW - 20 - 40 - GAP * 4;
      for (MacroConfig.TabSet set : List.copyOf(shared.tabSets)) {
         Row r = this.row();
         int x = this.x0;
         this.add(r, this.edit(x, setNameW, set.name, 48, v -> {
            set.name = v;
            this.sharedDirty = true;
         }));
         x += setNameW + GAP;
         r.labels.add(new Label(x + 40, () -> Component.translatable("handykit.tabs.set_size", set.tabs.size()), 0xFFB0B0B0, true));
         x += 40 + GAP;
         this.add(r, this.button(x, openW, () -> Component.translatable("handykit.tabs.set_open"), () -> {
            this.onClose();
            mcport.tabs.Tabs.openSet(set);
         }));
         x += openW + GAP;
         this.add(r, this.button(x, autoW, () -> labeled("handykit.tabs.set_auto", onOff(set.autoOpen)), () -> {
            boolean on = !set.autoOpen;
            // one set opens by itself at most
            shared.tabSets.forEach(s -> s.autoOpen = false);
            set.autoOpen = on;
            shared.save();
            this.refreshAll();
         }));
         this.add(r, this.button(this.x0 + this.w - 20, 20, () -> Component.literal("✕").withStyle(ChatFormatting.RED), () -> {
            shared.tabSets.remove(set);
            shared.save();
            this.rebuildWidgets();
         }));
      }
   }

   private boolean sharedDirty;

   private static int next(int now, int... steps) {
      for (int i = 0; i < steps.length; i++) {
         if (steps[i] == now) {
            return steps[(i + 1) % steps.length];
         }
      }

      return steps[0];
   }

   private void buildIgnore() {
      MacroConfig config = this.config();
      Row top = this.row();
      int half = (this.w - KEY_W - GAP * 2) / 2;
      this.add(top, this.button(this.x0, half, () -> labeled("handykit.ignore.hide_all", onOff(config.hideAllPlayers)), () -> {
         config.hideAllPlayers = !config.hideAllPlayers;
         this.markDirty();
      }));
      this.add(top, this.button(this.x0 + half + GAP, half, () -> labeled("handykit.ignore.mute_all", onOff(config.muteAllPlayers)), () -> {
         config.muteAllPlayers = !config.muteAllPlayers;
         this.markDirty();
      }));
      this.add(top, this.keyButton(this.x0 + this.w - KEY_W, KEY_W, MacroClient.HIDE_ALL, null));

      int partW = 60;
      int nameW = this.w - partW * 3 - 20 - GAP * 4;
      for (MacroConfig.Ignored entry : config.ignored) {
         Row r = this.row();
         int x = this.x0;
         EditBox name = this.add(r, this.edit(x, nameW, entry.name, 32, v -> {
            entry.name = v;
            config.ignoreVersion++;
            this.markDirty();
         }));
         name.setHint(Component.translatable("handykit.ignore.name").withStyle(ChatFormatting.DARK_GRAY));
         x += nameW + GAP;
         this.add(r, this.button(x, partW, () -> part("handykit.ignore.chat", entry.chat), () -> {
            entry.chat = !entry.chat;
            this.markDirty();
         }));
         x += partW + GAP;
         this.add(r, this.button(x, partW, () -> part("handykit.ignore.render", entry.render), () -> {
            entry.render = !entry.render;
            this.markDirty();
         }));
         x += partW + GAP;
         this.add(r, this.button(x, partW, () -> part("handykit.ignore.sound", entry.sound), () -> {
            entry.sound = !entry.sound;
            config.ignoreVersion++;
            this.markDirty();
         }));
         this.add(r, this.button(this.x0 + this.w - 20, 20, () -> Component.literal("✕").withStyle(ChatFormatting.RED), () -> {
            config.ignored.remove(entry);
            config.ignoreVersion++;
            this.markDirty();
            this.rebuildWidgets();
         }));
      }

      if (config.ignored.size() < MAX_RULES) {
         Row r = this.row();
         this.add(r, this.button(this.x0, 80, () -> Component.translatable("handykit.reply.add"), () -> {
            config.ignored.add(new MacroConfig.Ignored());
            config.ignoreVersion++;
            this.markDirty();
            this.scroll = Integer.MAX_VALUE;
            this.rebuildWidgets();
         }));
      }
   }

   /** One part of a player (chat, body, sound): red and struck out while it is being hidden. */
   private static Component part(String key, boolean hidden) {
      return hidden
         ? Component.translatable(key).withStyle(ChatFormatting.RED, ChatFormatting.STRIKETHROUGH)
         : Component.translatable(key).withStyle(ChatFormatting.GRAY);
   }

   private void buildRecord() {
      MacroConfig config = this.config();
      // the recordings and which one is selected are the same for every server
      MacroConfig shared = MacroConfig.base();
      Row top = this.row();
      int kw = 100;
      this.add(top, this.keyButton(this.x0, kw, MacroClient.RECORD, Component.translatable("handykit.record.record")));
      this.add(top, this.keyButton(this.x0 + kw + GAP, kw, MacroClient.PLAY, Component.translatable("handykit.record.play")));
      int lx = this.x0 + (kw + GAP) * 2;
      this.add(top, this.button(lx, this.x0 + this.w - lx, () -> labeled("handykit.record.look", onOff(config.recordLook)), () -> {
         config.recordLook = !config.recordLook;
         this.markDirty();
      }));

      if (config.recordings.isEmpty()) {
         Row r = this.row();
         r.labels.add(new Label(this.width / 2, () -> Component.translatable("handykit.record.empty", MacroClient.RECORD.getTranslatedKeyMessage()), 0xFF909090, false));
         return;
      }

      int lenW = 44;
      int repeatW = 64;
      int countW = 36;
      int nameW = this.w - 20 - lenW - repeatW - countW - 20 - 20 - 20 - GAP * 7;
      for (int i = 0; i < config.recordings.size(); i++) {
         Recording rec = config.recordings.get(i);
         int index = i;
         Row r = this.row();
         int x = this.x0;
         this.add(r, this.button(x, 20, () -> shared.selected == index ? Component.literal("●").withStyle(ChatFormatting.GREEN) : Component.literal("○").withStyle(ChatFormatting.DARK_GRAY), () -> {
            shared.selected = index;
            this.sharedDirty = true;
            this.refreshAll();
         }));
         x += 20 + GAP;
         this.add(r, this.edit(x, nameW, rec.name, 64, v -> {
            rec.name = v;
            this.recordingsDirty = true;
         }));
         x += nameW + GAP;
         r.labels.add(new Label(x + lenW, rec::lengthText, 0xFFB0B0B0, true));
         x += lenW + GAP;
         this.add(r, this.button(x, repeatW, () -> Component.translatable("handykit.repeat." + rec.repeat.name().toLowerCase(java.util.Locale.ROOT)), () -> {
            MacroConfig.Repeat[] all = MacroConfig.Repeat.values();
            rec.repeat = all[(rec.repeat.ordinal() + 1) % all.length];
            this.recordingsDirty = true;
            this.layoutRows();
         }));
         x += repeatW + GAP;
         EditBox count = this.add(r, this.edit(x, countW, Integer.toString(rec.count), 4, v -> {
            try {
               int n = Integer.parseInt(v.trim());
               if (n >= 1) {
                  rec.count = n;
                  this.recordingsDirty = true;
               }
            } catch (NumberFormatException ignored) {
            }
         }), () -> rec.repeat == MacroConfig.Repeat.COUNT);
         count.setCentered(true);
         x += countW + GAP;
         Button play = this.add(r, this.button(x, 20, () -> Component.literal("▶").withStyle(ChatFormatting.GREEN), () -> {
            if (MacroClient.recorder().recording()) {
               // what was being recorded is kept before something else plays
               MacroClient.finishRecording(this.minecraft);
            }

            shared.selected = index;
            this.sharedDirty = true;
            this.onClose();
            MacroClient.recorder().startPlayback(this.minecraft, rec);
         }));
         play.active = this.minecraft.player != null;
         x += 20 + GAP;
         this.add(r, this.button(x, 20, () -> Component.literal("✎"), () -> {
            this.save();
            this.minecraft.gui.setScreen(new RecordingEditScreen(this, rec));
         }));
         this.add(r, this.button(this.x0 + this.w - 20, 20, () -> Component.literal("✕").withStyle(ChatFormatting.RED), () -> {
            // wherever it is playing, also in a tab behind the screen
            MacroClient.stopPlaying(rec);
            shared.recordings.remove(rec);
            if (shared.selected >= index && shared.selected > 0) {
               shared.selected--;
            }

            this.sharedDirty = true;
            this.recordingsDirty = true;
            this.rebuildWidgets();
         }));
      }
   }

   // ---------------------------------------------------------------- drawing

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      super.extractRenderState(g, mouseX, mouseY, a);
      Button current = this.tabButtons.get(this.tab.ordinal());
      g.fill(current.getX() - 4, current.getY() + 2, current.getX() - 2, current.getY() + 18, 0xFFFFFFFF);
      g.enableScissor(0, TOP, this.width, this.bottom + 2);
      for (Row row : this.rows) {
         if (!this.shown(row)) {
            continue;
         }

         int y = this.rowTop(row) + 8;
         for (Label label : row.labels) {
            Component text = label.text().get();
            int x = label.right() ? label.x() - this.font.width(text) : label.x();
            if (label.x() == this.width / 2 && !label.right()) {
               x = label.x() - this.font.width(text) / 2;
            }

            g.text(this.font, text, x, y, label.color(), true);
         }
      }

      g.disableScissor();
      int max = this.contentHeight() + 8 - (this.bottom - TOP);
      if (max > 0) {
         int trackH = this.bottom - TOP;
         int thumbH = Math.max(12, trackH * trackH / (trackH + max));
         int thumbY = TOP + (trackH - thumbH) * this.scroll / max;
         int bx = this.x0 + this.w + 6;
         g.fill(bx, TOP, bx + 3, this.bottom, 0x50000000);
         g.fill(bx, thumbY, bx + 3, thumbY + thumbH, 0xFF8A8A8A);
      }
   }

   // ---------------------------------------------------------------- input

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
      if (super.mouseScrolled(mouseX, mouseY, horizontal, vertical)) {
         return true;
      }

      this.scroll -= (int)Math.round(vertical * 16);
      this.layoutRows();
      return true;
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (this.capturing != null) {
         this.capturing.setKey(InputConstants.Type.MOUSE.getOrCreate(event.button()));
         this.finishCapture();
         return true;
      }

      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if (this.capturing != null) {
         this.capturing.setKey(event.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(event));
         this.finishCapture();
         return true;
      }

      if (MacroClient.SETTINGS.matches(event) && !(this.getFocused() instanceof EditBox)) {
         if (!this.openKeyHeld) {
            this.onClose();
         }

         return true;
      }

      return super.keyPressed(event);
   }

   private void finishCapture() {
      this.capturing = null;
      KeyMapping.resetMapping();
      this.minecraft.options.save();
      this.refreshAll();
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   @Override
   public void tick() {
      super.tick();
      if (this.openKeyHeld && !Keys.held(MacroClient.SETTINGS)) {
         this.openKeyHeld = false;
      }
   }

   @Override
   public void onClose() {
      this.minecraft.gui.setScreen(this.parent);
   }

   @Override
   public void removed() {
      savedTab = this.tab;
      SAVED_SCROLL.put(this.tab, this.scroll);
      this.save();
   }

   private void save() {
      if (this.dirty) {
         this.dirty = false;
         this.config().save();
      }

      if (this.sharedDirty) {
         this.sharedDirty = false;
         MacroConfig.base().save();
      }

      if (this.recordingsDirty) {
         this.recordingsDirty = false;
         MacroConfig.base().saveRecordings();
      }
   }

   // ---------------------------------------------------------------- slider over fixed steps

   private static final class StepSlider extends AbstractSliderButton {
      private final double[] steps;
      private final DoubleFunction<Component> label;
      private final DoubleConsumer apply;

      StepSlider(int x, int width, double[] steps, double current, DoubleFunction<Component> label, DoubleConsumer apply) {
         super(x, 0, width, 20, Component.empty(), nearest(steps, current) / (double)(steps.length - 1));
         this.steps = steps;
         this.label = label;
         this.apply = apply;
         this.updateMessage();
      }

      private static int nearest(double[] steps, double v) {
         int best = 0;
         for (int i = 1; i < steps.length; i++) {
            if (Math.abs(steps[i] - v) < Math.abs(steps[best] - v)) {
               best = i;
            }
         }

         return best;
      }

      private double current() {
         return this.steps[(int)Math.round(this.value * (this.steps.length - 1))];
      }

      @Override
      protected void updateMessage() {
         if (this.steps != null) {
            this.setMessage(this.label.apply(this.current()));
         }
      }

      @Override
      protected void applyValue() {
         this.apply.accept(this.current());
      }
   }
}
