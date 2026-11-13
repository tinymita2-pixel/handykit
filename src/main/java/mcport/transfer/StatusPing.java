package mcport.transfer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;

/** The server list's status query, answered with the version the server reports for a given client protocol. */
public final class StatusPing {
   /** A server that does not take the connection in this time is not asked: the join that follows would wait as long again. */
   private static final int CONNECT_MS = 1500;
   /** The whole question, from connecting to the last byte of the answer. */
   private static final long TOTAL_NANOS = 3_000_000_000L;

   private StatusPing() {
   }

   public record Result(int protocol, String name) {
   }

   public static Result query(ServerAddress target, int clientProtocol) throws IOException {
      InetSocketAddress address = ServerNameResolver.DEFAULT
         .resolveAddress(target)
         .map(ResolvedServerAddress::asInetSocketAddress)
         .orElseThrow(() -> new UnknownHostException(target.getHost()));
      long deadline = System.nanoTime() + TOTAL_NANOS;

      try (Socket socket = new Socket()) {
         socket.connect(address, CONNECT_MS);
         socket.setTcpNoDelay(true);

         ByteArrayOutputStream handshake = new ByteArrayOutputStream();
         DataOutputStream body = new DataOutputStream(handshake);
         writeVarInt(body, 0x00);
         writeVarInt(body, clientProtocol);
         // the same host the real connection sends, so proxies route the query to the same backend
         writeString(body, address.getHostString());
         body.writeShort(address.getPort());
         writeVarInt(body, 1);

         DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
         writeVarInt(out, handshake.size());
         out.write(handshake.toByteArray());
         writeVarInt(out, 1);
         out.writeByte(0x00);
         out.flush();

         DataInputStream in = new DataInputStream(new BufferedInputStream(new Timed(socket, deadline)));
         readVarInt(in);
         if (readVarInt(in) != 0x00) {
            throw new IOException("unexpected status packet");
         }

         int length = readVarInt(in);
         if (length < 0 || length > 1 << 21) {
            throw new IOException("bad status length " + length);
         }

         byte[] json = new byte[length];
         in.readFully(json);
         JsonObject version = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("version");
         if (version == null || !version.has("protocol")) {
            throw new IOException("status has no version");
         }

         return new Result(version.get("protocol").getAsInt(), version.has("name") ? version.get("name").getAsString() : "");
      }
   }

   /**
    * The socket's input with one deadline for everything read from it. A read timeout alone starts anew with
    * every read, so a server that sends its answer a few bytes at a time would hold the connector thread for as
    * long as it likes.
    */
   private static final class Timed extends FilterInputStream {
      private final Socket socket;
      private final long deadline;

      Timed(Socket socket, long deadline) throws IOException {
         super(socket.getInputStream());
         this.socket = socket;
         this.deadline = deadline;
      }

      private void arm() throws IOException {
         long left = (this.deadline - System.nanoTime()) / 1_000_000L;
         if (left <= 0L) {
            throw new SocketTimeoutException("the server took too long to answer the status query");
         }

         this.socket.setSoTimeout((int)Math.min(left, Integer.MAX_VALUE));
      }

      @Override
      public int read() throws IOException {
         this.arm();
         return super.read();
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
         this.arm();
         return super.read(bytes, offset, length);
      }
   }

   private static void writeVarInt(DataOutputStream out, int value) throws IOException {
      while ((value & -128) != 0) {
         out.writeByte(value & 127 | 128);
         value >>>= 7;
      }

      out.writeByte(value);
   }

   private static void writeString(DataOutputStream out, String value) throws IOException {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      writeVarInt(out, bytes.length);
      out.write(bytes);
   }

   private static int readVarInt(DataInputStream in) throws IOException {
      int value = 0;
      for (int shift = 0; shift < 35; shift += 7) {
         byte b = in.readByte();
         value |= (b & 127) << shift;
         if ((b & 128) == 0) {
            return value;
         }
      }

      throw new IOException("varint too big");
   }
}
