package mcport.tabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.User;
import net.minecraft.core.UUIDUtil;
import org.jspecify.annotations.Nullable;

/**
 * The accounts a new server tab can log in with, next to the one the game was started with: other Microsoft
 * accounts the player signed in here, and plain names for servers that do not check accounts (offline mode).
 * Kept in config/handykit/accounts.json; the sign-in of a Microsoft account is stored encrypted for this Windows
 * user (DPAPI) and is only ever sent to Microsoft.
 */
public final class Accounts {
   public static final class Account {
      /** "microsoft" or "offline". */
      public String kind = "offline";
      public String name = "";
      public String uuid = "";
      /** The refresh token, protected; null for offline names. */
      @Nullable String secret;
      transient @Nullable String accessToken;
      transient long expiresAt;

      public boolean microsoft() {
         return "microsoft".equals(this.kind);
      }
   }

   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("handykit").resolve("accounts.json");
   private static @Nullable List<Account> accounts;
   /** What the next new tab logs in with; null is the account the game was started with. */
   private static @Nullable Account next;

   private Accounts() {
   }

   public static synchronized List<Account> list() {
      if (accounts == null) {
         List<Account> loaded = null;
         if (Files.exists(FILE)) {
            try {
               loaded = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), new TypeToken<List<Account>>() {
               }.getType());
            } catch (IOException | RuntimeException e) {
               Tabs.LOGGER.warn("Could not read {}", FILE, e);
            }
         }

         accounts = new ArrayList<>();
         if (loaded != null) {
            for (Account a : loaded) {
               if (a != null && a.name != null && !a.name.isBlank() && a.kind != null) {
                  accounts.add(a);
               }
            }
         }
      }

      return accounts;
   }

   private static synchronized void save() {
      try {
         Files.createDirectories(FILE.getParent());
         Files.writeString(FILE, GSON.toJson(list()), StandardCharsets.UTF_8);
      } catch (IOException e) {
         Tabs.LOGGER.warn("Could not write {}", FILE, e);
      }
   }

   /** A saved account by name; an offline name that is not saved yet is added. */
   public static synchronized @Nullable Account find(String name, boolean microsoft) {
      for (Account a : list()) {
         if (a.microsoft() == microsoft && a.name.equalsIgnoreCase(name)) {
            return a;
         }
      }

      return microsoft ? null : addOffline(name);
   }

   public static synchronized @Nullable Account next() {
      return next;
   }

   public static synchronized void choose(@Nullable Account account) {
      next = account;
   }

   /** A name for servers in offline mode; the same name again gives the same entry. */
   public static synchronized @Nullable Account addOffline(String name) {
      String clean = name.trim();
      if (clean.isEmpty() || clean.length() > 16 || !clean.chars().allMatch(c -> c > 32 && c != 127)) {
         return null;
      }

      for (Account a : list()) {
         if (!a.microsoft() && a.name.equalsIgnoreCase(clean)) {
            return a;
         }
      }

      Account account = new Account();
      account.kind = "offline";
      account.name = clean;
      account.uuid = UUIDUtil.createOfflinePlayerUUID(clean).toString();
      list().add(account);
      save();
      return account;
   }

   /** The account a finished Microsoft sign-in belongs to, added or brought up to date. */
   static synchronized Account addMicrosoft(MsLogin.Session session) {
      Account account = null;
      for (Account a : list()) {
         if (a.microsoft() && a.uuid.equals(session.uuid().toString())) {
            account = a;
         }
      }

      if (account == null) {
         account = new Account();
         account.kind = "microsoft";
         list().add(account);
      }

      store(account, session);
      return account;
   }

   private static void store(Account account, MsLogin.Session session) {
      account.name = session.name();
      account.uuid = session.uuid().toString();
      account.accessToken = session.accessToken();
      account.expiresAt = session.expiresAt();
      account.secret = protect(session.refreshToken());
      save();
   }

   public static synchronized void remove(Account account) {
      list().remove(account);
      if (next == account) {
         next = null;
      }

      save();
   }

   /**
    * For the account list: 1 a login token is at hand (until the returned time), 0 one is made when a tab joins,
    * -1 the stored sign-in cannot be opened any more (sign in again).
    */
   static synchronized long[] state(Account account) {
      if (account.accessToken != null && System.currentTimeMillis() < account.expiresAt) {
         return new long[]{1, account.expiresAt};
      }

      return new long[]{unprotect(account.secret) != null ? 0 : -1, 0};
   }

   /** The login as far as it is known without asking anyone; a Microsoft token may be missing or old. */
   static User user(Account account) {
      UUID uuid;
      try {
         uuid = UUID.fromString(account.uuid);
      } catch (IllegalArgumentException e) {
         uuid = UUIDUtil.createOfflinePlayerUUID(account.name);
      }

      String token = account.microsoft() && account.accessToken != null ? account.accessToken : "";
      return new User(account.name, uuid, token, Optional.empty(), Optional.empty());
   }

   /**
    * Blocking, for connector threads: a login whose token is good for joining a server right now. Offline names
    * come back as they are.
    */
   static User fresh(Account account) throws IOException, InterruptedException {
      if (account.microsoft()) {
         String kept;
         synchronized (Accounts.class) {
            if (account.accessToken != null && System.currentTimeMillis() < account.expiresAt - 10 * 60_000L) {
               return user(account);
            }

            kept = unprotect(account.secret);
         }

         if (kept == null) {
            throw new MsLogin.LoginException("not_stored", account.name);
         }

         MsLogin.Session session = MsLogin.refresh(kept, stage -> Tabs.LOGGER.info("Renewing the login of {}: {}", account.name, stage));
         synchronized (Accounts.class) {
            store(account, session);
         }
      }

      return user(account);
   }

   // ---------------------------------------------------------------- keeping the sign-in

   /** Windows ties the protected bytes to the logged-in Windows user; a copy of the file is useless elsewhere. */
   private static @Nullable String protect(String secret) {
      try {
         byte[] sealed = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(secret.getBytes(StandardCharsets.UTF_8));
         return "dpapi:" + Base64.getEncoder().encodeToString(sealed);
      } catch (Throwable t) {
         // not Windows, or DPAPI unavailable: the sign-in then lasts for this run of the game only
         Tabs.LOGGER.warn("The Microsoft sign-in cannot be stored protected here; it will be asked for again next time ({})", t.toString());
         SESSION_ONLY.put(secret.hashCode(), secret);
         return "memory:" + secret.hashCode();
      }
   }

   private static final java.util.Map<Integer, String> SESSION_ONLY = new java.util.concurrent.ConcurrentHashMap<>();

   private static @Nullable String unprotect(@Nullable String stored) {
      if (stored == null) {
         return null;
      }

      try {
         if (stored.startsWith("dpapi:")) {
            byte[] sealed = Base64.getDecoder().decode(stored.substring(6));
            return new String(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(sealed), StandardCharsets.UTF_8);
         }

         if (stored.startsWith("memory:")) {
            return SESSION_ONLY.get(Integer.parseInt(stored.substring(7)));
         }
      } catch (Throwable t) {
         Tabs.LOGGER.warn("Could not open the stored sign-in ({})", t.toString());
      }

      return null;
   }
}
