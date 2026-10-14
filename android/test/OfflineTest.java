package app.dsh.remote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Checks the one part of the offline page that cannot be seen by looking at it: the snapshot is
 * inlined into a {@code <script>} block, and a session title is arbitrary text. Runs on the PC
 * because {@link Offline#render} touches nothing but strings.
 *
 * <p>Given a snapshot as a second argument it also walks the whole path a phone walks while the PC
 * is off: {@link Tunnel} with the App's fallback installed, serving the document.
 *
 * <pre>
 * javac -encoding UTF-8 -cp android-sdk\platforms\android-34\android.jar -d build\offlinetest ^
 *     app\src\main\java\app\dsh\remote\Offline.java app\src\main\java\app\dsh\remote\Tunnel.java ^
 *     test\OfflineTest.java
 * java -cp "build\offlinetest;android-sdk\platforms\android-34\android.jar" ^
 *     app.dsh.remote.OfflineTest app\src\main\assets\offline.html ..\.tmp\snapshot.json
 * </pre>
 */
public final class OfflineTest {
  public static void main(String[] args) throws Exception {
    String path = args.length > 0 ? args[0] : "app/src/main/assets/offline.html";
    String template = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    int ok = 0;

    ok += check("the template really carries the marker", template.contains(Offline.MARKER),
        Offline.MARKER);

    // A session title is whatever the human typed, and one of them can end the script block.
    String hostile = "{\"v\":1,\"sessions\":[{\"title\":\"</script><script>alert(1)</script>\","
        + "\"cwd\":\"E:\\\\a\\\\b\",\"messages\":[]}]}";
    String out = Offline.render(template, "127.0.0.1:3080 - ConnectException", 1700000000000L,
        1700000060000L, hostile);

    ok += check("the marker is gone", !out.contains(Offline.MARKER), "");
    ok += check("the snapshot is inlined", out.contains("window.__OFFLINE__ = {\"why\":"), "");
    ok += check("a </script> in a title cannot close the block",
        !out.contains("</script><script>alert(1)") && out.contains("\\u003c/script"), "");
    ok += check("no raw < survives in the injected object",
        !out.contains("<script>alert(1)") && out.contains("\\u003cscript>alert(1)"), "");
    ok += check("the reason is a quoted string", out.contains("\"why\":\"127.0.0.1:3080 - ConnectException\""),
        "");
    ok += check("both timestamps are inlined",
        out.contains("\"syncedAt\":1700000000000") && out.contains("\"now\":1700000060000"), "");

    // Quotes, backslashes and newlines in the reason must not break out of the string either.
    String tricky = Offline.render(template, "a\"b\\c\nd", 0L, 1L, null);
    ok += check("a quote and a backslash in the reason are escaped",
        tricky.contains("\"why\":\"a\\\"b\\\\c\\nd\"") && !tricky.contains("\"why\":\"a\"b"), "");
    ok += check("no snapshot renders as null, not as an empty object",
        tricky.contains("\"snapshot\":null}"), "");

    // End to end minus Android: the tunnel the App builds, carrying the offline page the App
    // renders, answering the one request a phone makes while the PC is switched off.
    int total = 9;
    if (args.length > 1) {
      final String tpl = template;
      final String snap = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
      final String title = firstTitle(snap);
      Tunnel t = new Tunnel("127.0.0.1", freePort(), 17830, new Tunnel.Fallback() {
        @Override
        public String page(String reason) {
          return Offline.render(tpl, reason, 1700000000000L, 1700000060000L, snap);
        }
      });
      t.start();
      String doc = get(t.port(), "/");
      String api = get(t.port(), "/api/session/list");
      t.stop();
      ok += check("a dead PC still answers the document with the offline page",
          doc.startsWith("HTTP/1.1 200") && doc.contains("text/html")
              && doc.contains("window.__OFFLINE__ = {\"why\":\""), firstLine(doc));
      ok += check("...carrying the real conversations", !title.isEmpty() && doc.contains(title),
          title);
      ok += check("...and still refusing the /api calls behind it",
          api.startsWith("HTTP/1.1 503"), firstLine(api));
      total += 3;
    }

    System.out.println(ok + "/" + total + " checks passed");
    if (ok != total) System.exit(1);
  }

  /** The first session title in a snapshot, as it appears inside the JSON text. */
  private static String firstTitle(String snapshot) {
    int at = snapshot.indexOf("\"title\":\"");
    if (at < 0) return "";
    int from = at + "\"title\":\"".length();
    int to = snapshot.indexOf('"', from);
    return to < 0 ? "" : snapshot.substring(from, to);
  }

  /** A one-shot HTTP/1.1 request through a tunnel, read until EOF. */
  private static String get(int port, String path) throws Exception {
    java.net.Socket s = new java.net.Socket();
    s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
    s.setSoTimeout(15000);
    s.getOutputStream().write(
        ("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    s.getOutputStream().flush();
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[16384];
    int n;
    while ((n = s.getInputStream().read(chunk)) > 0) buf.write(chunk, 0, n);
    s.close();
    return new String(buf.toByteArray(), StandardCharsets.UTF_8);
  }

  private static String firstLine(String response) {
    int cut = response.indexOf("\r\n");
    String line = cut < 0 ? response : response.substring(0, cut);
    return line.substring(0, Math.min(78, line.length()));
  }

  private static int freePort() throws Exception {
    java.net.ServerSocket probe = new java.net.ServerSocket(0);
    int p = probe.getLocalPort();
    probe.close();
    return p;
  }

  private static int check(String what, boolean pass, String detail) {
    System.out.println((pass ? "ok   " : "FAIL ") + what
        + (detail.isEmpty() ? "" : "  [" + detail + "]"));
    return pass ? 1 : 0;
  }
}
