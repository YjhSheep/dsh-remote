package app.dsh.remote;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * End-to-end check for {@link Tunnel}, runnable on the PC because Tunnel uses nothing but the JDK.
 *
 * <pre>
 * javac -encoding UTF-8 -d ..\build\tunneltest app\java\app\dsh\remote\Tunnel.java test\TunnelTest.java
 * java -cp ..\build\tunneltest app.dsh.remote.TunnelTest 3080 &lt;bridge-key&gt;
 * </pre>
 *
 * Asserts: the port is the requested one (with fallback when taken), the gate handshake over the
 * tunnel hands out the bridge cookie, the follow-up request carrying that cookie returns the app
 * shell, and a dead upstream yields the 502 page.
 */
public final class TunnelTest {
  public static void main(String[] args) throws Exception {
    int upstreamPort = args.length > 0 ? Integer.parseInt(args[0]) : 3080;
    String key = args.length > 1 ? args[1] : "";
    int ok = 0;

    Tunnel t = new Tunnel("127.0.0.1", upstreamPort, 17800);
    t.start();
    ok += check("binds the requested port", t.port() == 17800, "port=" + t.port());

    Tunnel second = new Tunnel("127.0.0.1", upstreamPort, 17800);
    ok += check("steps aside when the port is taken", second.port() == 17801,
        "port=" + second.port());
    second.stop();

    // What the WebView does on first launch: ?k= buys the gate cookie, then the document follows.
    String handshake = request(t.port(), "/?k=" + key, null).head;
    ok += check("gate handshake over the tunnel", handshake.startsWith("http/1.1 303")
        && handshake.contains("set-cookie: dsh-bridge=" + key.toLowerCase()),
        firstLine(handshake));

    String response = request(t.port(), "/", "dsh-bridge=" + key).all;
    ok += check("carries the document with the gate cookie", response.startsWith("HTTP/1.1 200")
        && response.contains("DeepSeek Harness"), firstLine(response));
    ok += check("document body is whole", response.length() > 5000, response.length() + " bytes");
    t.stop();

    Tunnel dead = new Tunnel("127.0.0.1", freePort(), 17810);
    dead.start();
    String err = request(dead.port(), "/", null).all;
    ok += check("answers 502 when the upstream is dead", err.startsWith("HTTP/1.1 502"),
        firstLine(err));
    dead.stop();

    System.out.println(ok + "/6 checks passed");
    if (ok != 6) System.exit(1);
  }

  private static String firstLine(String response) {
    int cut = response.indexOf("\r\n");
    return (cut < 0 ? response : response.substring(0, cut)).substring(
        0, Math.min(78, cut < 0 ? response.length() : cut));
  }

  private static int check(String what, boolean pass, String detail) {
    System.out.println((pass ? "ok   " : "FAIL ") + what + "  [" + detail + "]");
    return pass ? 1 : 0;
  }

  private static final class Reply {
    final String head;
    final String all;

    Reply(String all) {
      this.all = all;
      int cut = all.indexOf("\r\n\r\n");
      this.head = (cut < 0 ? all : all.substring(0, cut)).toLowerCase();
    }
  }

  /** A one-shot HTTP/1.1 request through the tunnel, read until EOF. */
  private static Reply request(int port, String path, String cookie) throws Exception {
    Socket s = new Socket();
    s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
    s.setSoTimeout(15000);
    String req = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + (cookie == null ? "" : "Cookie: " + cookie + "\r\n") + "Connection: close\r\n\r\n";
    OutputStream out = s.getOutputStream();
    out.write(req.getBytes(StandardCharsets.UTF_8));
    out.flush();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    InputStream in = s.getInputStream();
    byte[] chunk = new byte[16384];
    int n;
    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
    s.close();
    return new Reply(new String(buf.toByteArray(), StandardCharsets.UTF_8));
  }

  private static int freePort() throws Exception {
    java.net.ServerSocket probe = new java.net.ServerSocket(0);
    int p = probe.getLocalPort();
    probe.close();
    return p;
  }
}
