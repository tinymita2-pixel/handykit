package mcport.macro;

import com.mojang.authlib.GameProfile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.util.StringUtil;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/** Chat presets and keyword replies. Everything goes out from the client tick, never from inside a packet handler. */
final class ChatMacros {
   /** Separators servers put between a name and the message, used to find the message part of a formatted line. */
   private static final String[] SEPARATORS = {": ", "> ", "» ", ">> ", "| "};
   private static final long ECHO_MS = 5000;
   /** What the server writes back to a reply arrives within this time; such a line does not set the same rule off again. */
   private static final long ANSWER_MS = 2000;

   /** A reply waiting for its delay; it goes back to the server the line came from, which may be a background tab. */
   private record Pending(long at, String text, ClientPacketListener target, MacroConfig.Rule rule) {
   }

   private record Sent(long at, String text) {
   }

   /** True while a keyword reply goes out, so a recording in progress does not take it for the player typing. */
   static boolean automatic;
   private final List<Pending> queue = new ArrayList<>();
   private final ArrayDeque<Sent> recent = new ArrayDeque<>();
   private final Map<MacroConfig.Rule, Long> fired = new IdentityHashMap<>();
   /** When the reply of a rule last went out. */
   private final Map<MacroConfig.Rule, Long> replied = new IdentityHashMap<>();

   void sendPreset(Minecraft mc, int index) {
      List<String> presets = MacroConfig.get().presets;
      if (index >= 0 && index < presets.size()) {
         this.send(mc.getConnection(), presets.get(index));
      }
   }

   /** Sends like the chat box does: a leading slash runs a command. */
   void send(@Nullable ClientPacketListener target, String text) {
      if (target == null || text == null || !target.getConnection().isConnected() || target.getConnection().getPacketListener() != target) {
         // gone, or the server has moved the connection on (back to configuration): a chat packet would break it
         return;
      }

      String msg = StringUtil.trimChatMessage(StringUtils.normalizeSpace(text.trim()));
      if (msg.isEmpty()) {
         return;
      }

      this.recent.addLast(new Sent(System.currentTimeMillis(), norm(msg)));
      if (msg.startsWith("/")) {
         target.sendCommand(msg.substring(1));
      } else {
         target.sendChat(msg);
      }
   }

   void tick(Minecraft mc) {
      long now = System.currentTimeMillis();
      while (!this.recent.isEmpty() && now - this.recent.peekFirst().at() > ECHO_MS) {
         this.recent.removeFirst();
      }

      for (Iterator<Pending> it = this.queue.iterator(); it.hasNext(); ) {
         Pending p = it.next();
         if (now >= p.at()) {
            it.remove();
            this.replied.put(p.rule(), now);
            automatic = true;
            try {
               this.send(p.target(), p.text());
            } finally {
               automatic = false;
            }
         }
      }
   }

   /** text: the whole line as plain text. */
   void onPlayerChat(Minecraft mc, String text, @Nullable PlayerChatMessage signed, @Nullable GameProfile sender) {
      if (sender != null && mc.player != null && mcport.tabs.Tabs.isOwnPlayer(sender.id())) {
         // my own line, also when it was another of my tabs that said it
         return;
      }

      String body = null;
      if (signed != null) {
         body = signed.unsignedContent() != null ? signed.unsignedContent().getString() : signed.signedContent();
      }

      this.onMessage(mc, text, body);
   }

   /** A line the server wrote itself (not the one over the hotbar). */
   void onGameMessage(Minecraft mc, String text) {
      this.onMessage(mc, text, null);
   }

   private void onMessage(Minecraft mc, String text, @Nullable String body) {
      MacroConfig config = MacroConfig.get();
      if (!config.replyOn || config.rules.isEmpty() || mc.player == null) {
         return;
      }

      String full = norm(text);
      if (full.isEmpty() || this.isEcho(full) || this.heardInOtherTab(full)) {
         return;
      }

      List<String> parts = new ArrayList<>(2);
      if (body != null) {
         parts.add(norm(body));
      } else {
         List<String> mine = null;
         boolean split = false;
         for (String sep : SEPARATORS) {
            int at = full.indexOf(sep);
            if (at >= 0) {
               // "[rank] name: hi" from one of our own names is our own line coming back from the server
               String head = full.substring(0, at);
               if (mine == null) {
                  mine = mcport.tabs.Tabs.ownNames();
               }

               for (String me : mine) {
                  if (!me.isEmpty() && head.contains(me)) {
                     return;
                  }
               }

               parts.add(full.substring(at + sep.length()).trim());
               split = true;
            }
         }

         if (!split) {
            parts.add(full);
         }
      }

      long now = System.currentTimeMillis();
      for (MacroConfig.Rule rule : config.rules) {
         String keyword = rule.normKeyword();
         if (!rule.on || keyword.isEmpty() || rule.reply.isBlank() || !matches(rule.exact, keyword, full, parts)) {
            continue;
         }

         Long last = this.fired.get(rule);
         if (last != null && now - last < (long)(config.replyCooldown * 1000)) {
            return;
         }

         // A reply whose answer from the server holds the keyword again (a command that prints it) would set the
         // rule off once more, and with no cooldown without end. Only lines the server wrote itself can be that
         // answer; what another player said (body) is always theirs.
         Long out = body == null ? this.replied.get(rule) : null;
         if (out != null && now - out < ANSWER_MS) {
            return;
         }

         this.fired.put(rule, now);
         this.queue.add(new Pending(now + (long)(config.replyDelay * 1000), rule.reply, mc.getConnection(), rule));
         return;
      }
   }

   private static boolean matches(boolean exact, String keyword, String full, List<String> parts) {
      if (exact) {
         if (full.equals(keyword)) {
            return true;
         }

         for (String p : parts) {
            if (p.equals(keyword)) {
               return true;
            }
         }

         return false;
      }

      for (String p : parts) {
         if (p.contains(keyword)) {
            return true;
         }
      }

      return false;
   }

   private record Heard(String text, int tab, long at) {
   }

   private final java.util.ArrayDeque<Heard> heard = new java.util.ArrayDeque<>();

   /** Two of my tabs on one server both get every line; only the first one to hear it answers. */
   private boolean heardInOtherTab(String full) {
      long now = System.currentTimeMillis();
      while (!this.heard.isEmpty() && now - this.heard.peekFirst().at() > 1500) {
         this.heard.removeFirst();
      }

      int tab = mcport.tabs.Tabs.contextId();
      for (Heard h : this.heard) {
         if (h.tab() != tab && h.text().equals(full)) {
            return true;
         }
      }

      this.heard.addLast(new Heard(full, tab, now));
      return false;
   }

   private boolean isEcho(String full) {
      for (Sent s : this.recent) {
         if (full.contains(s.text())) {
            return true;
         }
      }

      return false;
   }

   static String norm(String s) {
      return StringUtils.normalizeSpace(s).toLowerCase(Locale.ROOT);
   }
}
