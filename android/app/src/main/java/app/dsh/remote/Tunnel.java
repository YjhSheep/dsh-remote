package app.dsh.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Raw TCP forwarder: 127.0.0.1:&lt;local&gt; -&gt; &lt;host&gt;:&lt;port&gt; (the DSH LAN bridge on the PC).
 *
 * <p>The WebView only ever loads http://127.0.0.1:&lt;local&gt;/, so DSH's own hostname based
 * trust check classifies the page as loopback - exactly what the desktop app sees - and the
 * settings / model pages work with no host-trust patch.
 *
 * <p>Forwarding bytes instead of interpreting HTTP means the same socket carries the document,
 * /api calls, the SSE stream at /plugins/events and WebSocket upgrades alike.
 */
final class Tunnel {
  private final String host;
  private final int port;
  private final ServerSocket server;
  private volatile boolean stopped;

  Tunnel(String host, int port, int wantedLocalPort) throws IOException {
    this.host = host;
    this.port = port;
    this.server = bind(wantedLocalPort);
  }

  private static ServerSocket bind(int wanted) throws IOException {
    // Bind 127.0.0.1 explicitly: getLoopbackAddress() can hand back ::1 (the v6 loopback) on some
    // devices, and the WebView always asks for 127.0.0.1 - a v6-only listener refuses it.
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
    try {
      client.setTcpNoDelay(true);
      up.setTcpNoDelay(true);
      up.connect(new InetSocketAddress(host, port), 8000);
    } catch (IOException e) {
      serviceUnavailable(client, host + ":" + port + " - " + e);
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
    copy(client, up);
    try {
      back.join(20000);
    } catch (InterruptedException ignored) {
      // fall through to the close below
    }
    close(up);
    close(client);
  }

  private static void copy(Socket from, Socket to) {
    byte[] buf = new byte[16384];
    try {
      InputStream in = from.getInputStream();
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
   * simply not running the bridge.
   */
  private static void serviceUnavailable(Socket client, String reason) {
    String body = "无法连接电脑上的 DSH。请确认桥接正在电脑上运行，且手机与电脑在同一网络。";
    byte[] html = ("<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<style>body{font:16px/1.6 system-ui,sans-serif;padding:28px;color:#333}"
        + "p.why{color:#888;font-size:13px;word-break:break-all}</style></head>"
        + "<body><p>" + body + "</p><p class=\"why\">" + escape(reason) + "</p></body></html>")
        .getBytes(StandardCharsets.UTF_8);
    try {
      StringBuilder head = new StringBuilder("HTTP/1.1 502 Bad Gateway\r\n");
      head.append("Content-Type: text/html; charset=utf-8\r\n");
      head.append("Content-Length: ").append(html.length).append("\r\n");
      head.append("Connection: close\r\n\r\n");
      OutputStream out = client.getOutputStream();
      out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
      out.write(html);
      out.flush();
    } catch (IOException ignored) {
      // the client is already gone
    }
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
