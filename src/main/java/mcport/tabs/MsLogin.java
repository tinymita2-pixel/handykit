package mcport.tabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Signing a second Microsoft account in, the way a launcher does it, without the game ever seeing a password: the
 * player opens Microsoft's own page in the browser and types a short code there (device code flow). What comes
 * back is a refresh token; from it a Minecraft login token is made whenever a tab needs one:
 * Microsoft account -> Xbox Live -> XSTS -> Minecraft services -> profile.
 *
 * Tokens never go into the log. Network calls block; call from a worker thread.
 */
final class MsLogin {
   /** The id the Minecraft launcher itself signs in with; Minecraft services only take logins made through it. */
   private static final String CLIENT_ID = "00000000402b5328";
   private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

   record DeviceCode(String userCode, String deviceCode, String uri, int interval, long expiresAt) {
   }

   /** A Minecraft session; refreshToken is what is kept to make the next one. */
   record Session(String name, UUID uuid, String accessToken, long expiresAt, String refreshToken) {
   }

   /** key: a translation key of handykit.accounts.error.*; detail: the error name the service gave, if any. */
   static final class LoginException extends IOException {
      final String key;
      final String detail;

      LoginException(String key, String detail) {
         super(key + (detail.isEmpty() ? "" : " " + detail));
         this.key = "handykit.accounts.error." + key;
         this.detail = detail;
      }

      LoginException(String key) {
         this(key, "");
      }
   }

   /** Told where a sign-in is: "xbox", "xsts", "minecraft", "profile". Names only, never a token. */
   interface Stage {
      void at(String stage);
   }

   private MsLogin() {
   }

   /** Asks Microsoft for a code the player types at the returned address. */
   static DeviceCode start() throws IOException, InterruptedException {
      JsonObject json = form("https://login.live.com/oauth20_connect.srf", "client_id", CLIENT_ID, "scope", SCOPE, "response_type", "device_code");
      if (!json.has("device_code")) {
         throw new LoginException("no_code", describe(json));
      }

      String uri = json.has("verification_uri") ? json.get("verification_uri").getAsString() : "https://www.microsoft.com/link";
      int interval = json.has("interval") ? Math.max(2, json.get("interval").getAsInt()) : 5;
      long expires = System.currentTimeMillis() + (json.has("expires_in") ? json.get("expires_in").getAsLong() : 900L) * 1000L;
      return new DeviceCode(json.get("user_code").getAsString(), json.get("device_code").getAsString(), uri, interval, expires);
   }

   /** One look at whether the player has finished on Microsoft's page: the session, or null while they have not. */
   static @Nullable Session poll(DeviceCode code, Stage stage) throws IOException, InterruptedException {
      JsonObject json = form(
         "https://login.live.com/oauth20_token.srf", "client_id", CLIENT_ID, "device_code", code.deviceCode(), "grant_type", "urn:ietf:params:oauth:grant-type:device_code"
      );
      if (json.has("access_token") && json.has("refresh_token")) {
         return minecraft(json.get("access_token").getAsString(), json.get("refresh_token").getAsString(), stage);
      }

      String error = json.has("error") ? json.get("error").getAsString() : "";
      if (error.equals("authorization_pending") || error.equals("slow_down")) {
         return null;
      }

      throw new LoginException("ended", describe(json));
   }

   /** A new Minecraft session from a kept refresh token (Microsoft hands out a new refresh token each time). */
   static Session refresh(String refreshToken, Stage stage) throws IOException, InterruptedException {
      JsonObject json = form(
         "https://login.live.com/oauth20_token.srf", "client_id", CLIENT_ID, "refresh_token", refreshToken, "grant_type", "refresh_token", "scope", SCOPE
      );
      if (!json.has("access_token")) {
         throw new LoginException("stale", describe(json));
      }

      String next = json.has("refresh_token") ? json.get("refresh_token").getAsString() : refreshToken;
      return minecraft(json.get("access_token").getAsString(), next, stage);
   }

   private static Session minecraft(String msaToken, String refreshToken, Stage stage) throws IOException, InterruptedException {
      // the ticket format differs between kinds of Microsoft tokens; the launcher's kind takes "t=", others "d=" or nothing
      stage.at("xbox");
      JsonObject xbl = null;
      String xblStatus = "";
      for (String prefix : new String[]{"t=", "", "d="}) {
         JsonObject properties = new JsonObject();
         properties.addProperty("AuthMethod", "RPS");
         properties.addProperty("SiteName", "user.auth.xboxlive.com");
         properties.addProperty("RpsTicket", prefix + msaToken);
         JsonObject body = new JsonObject();
         body.add("Properties", properties);
         body.addProperty("RelyingParty", "http://auth.xboxlive.com");
         body.addProperty("TokenType", "JWT");
         JsonObject answer = json("https://user.auth.xboxlive.com/user/authenticate", body, null);
         if (answer.has("Token")) {
            xbl = answer;
            break;
         }

         xblStatus = status(answer);
      }

      if (xbl == null) {
         throw new LoginException("xbox", xblStatus);
      }

      stage.at("xsts");

      JsonObject properties = new JsonObject();
      properties.addProperty("SandboxId", "RETAIL");
      JsonArray tokens = new JsonArray();
      tokens.add(xbl.get("Token").getAsString());
      properties.add("UserTokens", tokens);
      JsonObject body = new JsonObject();
      body.add("Properties", properties);
      body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
      body.addProperty("TokenType", "JWT");
      JsonObject xsts = json("https://xsts.auth.xboxlive.com/xsts/authorize", body, null);
      if (!xsts.has("Token")) {
         long code = xsts.has("XErr") ? xsts.get("XErr").getAsLong() : 0;
         throw new LoginException(code == 2148916233L ? "no_xbox_profile" : code == 2148916238L ? "child" : "xbox_refused", code != 0 ? Long.toString(code) : status(xsts));
      }

      stage.at("minecraft");

      String hash = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
      JsonObject login = new JsonObject();
      login.addProperty("identityToken", "XBL3.0 x=" + hash + ";" + xsts.get("Token").getAsString());
      JsonObject mc = json("https://api.minecraftservices.com/authentication/login_with_xbox", login, null);
      if (!mc.has("access_token")) {
         // what the service says about it is a sentence about the app or the account, not a secret
         String why = mc.has("errorMessage") && mc.get("errorMessage").isJsonPrimitive() ? mc.get("errorMessage").getAsString() : describe(mc);
         throw new LoginException("minecraft", (status(mc) + " " + why).trim());
      }

      stage.at("profile");

      String access = mc.get("access_token").getAsString();
      long expires = System.currentTimeMillis() + (mc.has("expires_in") ? mc.get("expires_in").getAsLong() : 86400L) * 1000L;
      JsonObject profile = json("https://api.minecraftservices.com/minecraft/profile", null, access);
      if (!profile.has("id") || !profile.has("name")) {
         if (status(profile).equals("404")) {
            throw new LoginException("no_game");
         }

         throw new LoginException("profile", status(profile));
      }

      String id = profile.get("id").getAsString().replace("-", "");
      UUID uuid = new UUID(Long.parseUnsignedLong(id.substring(0, 16), 16), Long.parseUnsignedLong(id.substring(16), 16));
      return new Session(profile.get("name").getAsString(), uuid, access, expires, refreshToken);
   }

   private static JsonObject form(String url, String... pairs) throws IOException, InterruptedException {
      StringBuilder body = new StringBuilder();
      for (int i = 0; i + 1 < pairs.length; i += 2) {
         if (body.length() > 0) {
            body.append('&');
         }

         body.append(pairs[i]).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
      }

      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
         .timeout(Duration.ofSeconds(20))
         .header("Content-Type", "application/x-www-form-urlencoded")
         .header("Accept", "application/json")
         .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
         .build();
      return parse(HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
   }

   private static JsonObject json(String url, @Nullable JsonObject body, @Nullable String bearer) throws IOException, InterruptedException {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
      if (bearer != null) {
         request.header("Authorization", "Bearer " + bearer);
      }

      if (body != null) {
         request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      } else {
         request.GET();
      }

      return parse(HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
   }

   private static JsonObject parse(HttpResponse<String> response) {
      JsonObject json;
      try {
         json = JsonParser.parseString(response.body()).getAsJsonObject();
      } catch (RuntimeException e) {
         // an error page or an empty answer: keep only the status, never the body
         json = new JsonObject();
         json.addProperty("error", "http_" + response.statusCode());
      }

      json.addProperty("_status", response.statusCode());
      return json;
   }

   /** The HTTP status of an answer, as text. */
   private static String status(JsonObject json) {
      return json.has("_status") ? json.get("_status").getAsString() : "";
   }

   /** The error name Microsoft gave, for the player; never a token. */
   private static String describe(JsonObject json) {
      return json.has("error") && json.get("error").isJsonPrimitive() ? json.get("error").getAsString() : "";
   }
}
