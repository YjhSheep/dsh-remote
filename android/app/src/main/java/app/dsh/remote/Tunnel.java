package app.dsh.remote;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Raw TCP forwarder: localhost:&lt;local&gt; -&gt; &lt;host&gt;:&lt;port&gt; (the DSH LAN bridge on the PC).
 *
 * <p>The WebView only ever loads http://localhost:&lt;local&gt;/ (WebActivity.LOCAL_HOST), so DSH's own
 * hostname based trust check classifies the page as loopback - exactly what the desktop app sees -
 * and the settings / model pages work with no host-trust patch. The name is "localhost" and not
 * "127.0.0.1" for a wire reason: middleboxes reset a cleartext request whose Host is the literal
 * 127.0.0.1, so over a tunnel the literal would reach the bridge and never come back.
 *
 * <p>Forwarding bytes instead of interpreting HTTP means the same socket carries the document,
 * /api calls, the SSE stream at /plugins/events and WebSocket upgrades alike. The one thing it does
 * read is the request line, before the upstream is dialled: that is what lets a dead upstream be
 * answered with the offline page for a page load (see {@link Fallback}) and with a plain failure
 * for everything else, instead of handing HTML to an API call.
 */
final class Tunnel {
  /** A request head is a request line plus headers; this is far more than any client sends. */
  private static final int MAX_HEAD = 16384;
  /** How long a client gets to send its request line before the upstream is dialled regardless. */
  private static final int HEAD_TIMEOUT_MS = 5000;

  /** What to answer with when the upstream cannot be reached at all. */
  interface Fallback {
    /** The document to serve in place of the page the client asked for; never null. */
    String page(String reason);
  }

  private final String host;
  private final int port;
  /** May be null: a tunnel with nothing to offer a page load answers 502 as it always did. */
  private final Fallback fallback;
  private final ServerSocket server;
  private volatile boolean stopped;

  /** A tunnel with nothing to fall back on: a dead upstream answers 502, as it always did. */
  Tunnel(String host, int port, int wantedLocalPort) throws IOException {
    this(host, port, wantedLocalPort, null);
  }

  Tunnel(String host, int port, int wantedLocalPort, Fallback fallback) throws IOException {
    this.host = host;
    this.port = port;
    this.fallback = fallback;
    this.server = bind(wantedLocalPort);
  }

  private static ServerSocket bind(int wanted) throws IOException {
    // Bind 127.0.0.1 explicitly: getLoopbackAddress() can hand back ::1 (the v6 loopback) on some
    // devices. The WebView asks for "localhost", which resolves to ::1 as well, but a refused v6
    // attempt just falls through to 127.0.0.1 - the same thing every IPv4-only dev server relies on.
    // Binding the name would be wrong anyway: a v6-only listener refuses the v4 attempt.
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    IOException last = null;
    for (int p = wanted; p < wanted + 8; p++) {
      try {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(loopback, p), 16);
        return s;
      } catch (IOException e) {
        last = e;
      }
    }
    throw last != null ? last : new IOException("no free loopback port from " + wanted);
  }

  int port() {
    return server.getLocalPort();
  }

  void start() {
    // Anonymous classes, not lambdas: android.jar has no java.lang.invoke.LambdaMetafactory, so
    // javac fails on a lambda whenever it compiles against -bootclasspath android.jar.
    Thread t = new Thread(new Runnable() {
      public void run() {
        acceptLoop();
      }
    }, "dsh-tunnel");
    t.setDaemon(true);
    t.start();
  }

  void stop() {
    stopped = true;
    try {
      server.close();
    } catch (IOException ignored) {
      // closing a listening socket is best effort
    }
  }

  private void acceptLoop() {
    while (!stopped) {
      final Socket client;
      try {
        client = server.accept();
      } catch (IOException e) {
        if (stopped) return;
        continue;
      }
      Thread t = new Thread(new Runnable() {
        public void run() {
          handle(client);
        }
      }, "dsh-tunnel-conn");
      t.setDaemon(true);
      t.start();
    }
  }

  private void handle(Socket client) {
    Socket up = new Socket();
    InputStream fromClient;
    try {
      client.setTcpNoDelay(true);
      up.setTcpNoDelay(true);
      // Buffered, and kept: the head read below may have pulled bytes past the request line into
      // this stream, and a second getInputStream() on the socket would lose them.
      fromClient = new BufferedInputStream(client.getInputStream(), 16384);
    } catch (IOException e) {
      close(up);
      close(client);
      return;
    }
    byte[] head = readHead(client, fromClient);
    try {
      up.connect(new InetSocketAddress(host, port), 8000);
    } catch (IOException e) {
      answer(client, head, host + ":" + port + " - " + e);
      close(up);
      close(client);
      return;
    }
    try {
      OutputStream toUp = up.getOutputStream();
      toUp.write(head);
      toUp.flush();
    } catch (IOException e) {
      close(up);
      close(client);
      return;
    }
    Thread back = new Thread(new Runnable() {
      public void run() {
        copy(up, client);
      }
    }, "dsh-tunnel-back");
    back.setDaemon(true);
    back.start();
    copy(fromClient, up);
    try {
      back.join(20000);
    } catch (InterruptedException ignored) {
      // fall through to the close below
    }
    close(up);
    close(client);
  }

  /**
   * The request head, read before the upstream is even dialled. It is handed back so it can be
   * replayed upstream verbatim - nothing here rewrites a request, this only needs to know whether
   * the one that arrived is a page load.
   *
   * <p>The client's read timeout is put back to "wait forever" afterwards: leaving it on would
   * apply to the long-lived streams this same socket goes on to carry (SSE, WebSocket) and cut an
   * idle one short.
   */
  private static byte[] readHead(Socket client, InputStream in) {
    ByteArrayOutputStream head = new ByteArrayOutputStream(512);
    // The last four bytes as one int, so the blank line that ends a head is spotted without
    // copying the buffer once per byte.
    int tail = 0;
    try {
      client.setSoTimeout(HEAD_TIMEOUT_MS);
      int b;
      while (head.size() < MAX_HEAD && (b = in.read()) >= 0) {
        head.write(b);
        tail = (tail << 8) | b;
        if (tail == 0x0d0a0d0a) break;
      }
    } catch (IOException ignored) {
      // A client that says nothing, or says it too slowly: forward whatever did arrive.
    }
    try {
      client.setSoTimeout(0);
    } catch (IOException ignored) {
      // the socket is already gone
    }
    return head.toByteArray();
  }

  /**
   * True for the one request the offline fallback may answer: the document itself.
   *
   * <p>Deliberately narrow. Everything else behind a page load - /api, the SSE stream, the polled
   * /dsh-whale JSON, the bridge's injected files - has to fail as itself, because a fetch handed a
   * 200 and an HTML body does not report an error, it reports nonsense. The App only ever navigates
   * to the root, so that is all that is recognised here; a new navigation target would show up as a
   * 503 rather than as HTML delivered somewhere it does not belong.
   */
  static boolean isDocument(byte[] head) {
    String[] parts = firstLine(head).split(" ");
    if (parts.length < 2 || !"GET".equals(parts[0])) return false;
    String target = parts[1];
    int query = target.indexOf('?');
    if (query >= 0) target = target.substring(0, query);
    return target.isEmpty() || target.equals("/") || target.equals("/index.html");
  }

  private static String firstLine(byte[] head) {
    String text = new String(head, StandardCharsets.ISO_8859_1);
    int cut = text.indexOf('\r');
    if (cut < 0) cut = text.indexOf('\n');
    return cut < 0 ? text : text.substring(0, cut);
  }

  /** Answer a request the upstream never saw. */
  private void answer(Socket client, byte[] head, String reason) {
    if (!isDocument(head)) {
      respond(
          client,
          "503 Service Unavailable",
          "text/plain; charset=utf-8",
          ("offline: " + reason + "\n").getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (fallback == null) {
      serviceUnavailable(client, reason);
      return;
    }
    respond(
        client,
        "200 OK",
        "text/html; charset=utf-8",
        fallback.page(reason).getBytes(StandardCharsets.UTF_8));
  }

  private static void respond(Socket client, String status, String type, byte[] body) {
    try {
      StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
      head.append("Content-Type: ").append(type).append("\r\n");
      head.append("Content-Length: ").append(body.length).append("\r\n");
      head.append("Cache-Control: no-store\r\n");
      head.append("Connection: close\r\n\r\n");
      OutputStream out = client.getOutputStream();
      out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
    } catch (IOException ignored) {
      // the client is already gone
    }
  }

  private static void copy(Socket from, Socket to) {
    try {
      copy(from.getInputStream(), to);
    } catch (IOException ignored) {
      // peer closed, or the phone changed network
    }
  }

  private static void copy(InputStream in, Socket to) {
    byte[] buf = new byte[16384];
    try {
      OutputStream out = to.getOutputStream();
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
        out.flush();
      }
    } catch (IOException ignored) {
      // peer closed, or the phone changed network
    }
    try {
      to.shutdownOutput(); // half close: the other direction may still have data
    } catch (IOException ignored) {
      // already closed
    }
  }

  /**
   * Answer the request ourselves so the WebView shows something readable instead of hanging. The
   * reason is printed because "cannot connect" alone does not say whether the PC is unreachable or
   * simply not running the bridge. Only reached when the tunnel has no offline page to offer.
   */
  private static void serviceUnavailable(Socket client, String reason) {
    String body = "无法连接电脑上的 DSH。请确认桥接正在电脑上运行，且手机与电脑在同一网络。";
    byte[] html = ("<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<style>body{font:16px/1.6 system-ui,sans-serif;padding:28px;color:#333}"
        + "p.why{color:#888;font-size:13px;word-break:break-all}</style></head>"
        + "<body><p>" + body + "</p><p class=\"why\">" + escape(reason) + "</p></body></html>")
        .getBytes(StandardCharsets.UTF_8);
    respond(client, "502 Bad Gateway", "text/html; charset=utf-8", html);
  }

  static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static void close(Socket s) {
    try {
      s.close();
    } catch (IOException ignored) {
      // nothing left to do
    }
  }
}
