package app.dsh.remote;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The copy of the conversations the phone keeps so a PC that is off - or a phone with no network
 * at all - still shows something readable instead of "无法连接".
 *
 * <p>Two halves. {@link #sync} pulls the bridge's snapshot through the tunnel while the PC is up
 * and writes it to the app's own files directory (not the cache directory: "clear cache" must not
 * throw away the only copy of a conversation). {@link #page} renders it into the page the tunnel
 * serves when the PC cannot be reached at all - the snapshot is inlined, so the offline page needs
 * no further request and therefore nothing else can fail behind it.
 *
 * <p>The snapshot is the PC's own session logs, built by {@code tools/dsh-lan-bridge.cjs}; nothing
 * here interprets it beyond checking that it parses as JSON, and it is escaped for its one trip
 * through a {@code <script>} block.
 */
final class Offline {
  private static final String TAG = "dsh-remote";
  /** The bridge route that builds it; also the path this class fetches through the tunnel. */
  static final String ROUTE = "/__bridge/offline/snapshot.json";
  /** What {@code assets/offline.html} leaves for the snapshot; {@link #render} fills it in. */
  static final String MARKER = "/*__OFFLINE__*/null";
  /** A snapshot bigger than this is not one we wrote, so it is not worth keeping. */
  private static final long MAX_BYTES = 4L * 1024 * 1024;

  private static volatile String template;

  private Offline() {}

  /** Where the snapshot lives: the app's files directory, so it outlives a cache clear. */
  static File file(Context c) {
    return new File(new File(c.getFilesDir(), "offline"), "snapshot.json");
  }

  /** When the copy on disk was written, or 0 if there is none. */
  static long syncedAt(Context c) {
    File f = file(c);
    return f.isFile() ? f.lastModified() : 0;
  }

  /** The stored snapshot verbatim, or null when there is nothing usable on disk. */
  static String snapshot(Context c) {
    File f = file(c);
    if (!f.isFile() || f.length() == 0) return null;
    try {
      byte[] bytes = new byte[(int) f.length()];
      FileInputStream in = new FileInputStream(f);
      int off = 0;
      while (off < bytes.length) {
        int n = in.read(bytes, off, bytes.length - off);
        if (n < 0) break;
        off += n;
      }
      in.close();
      return new String(bytes, 0, off, StandardCharsets.UTF_8);
    } catch (IOException e) {
      Log.w(TAG, "cannot read the offline snapshot: " + e);
      return null;
    }
  }

  /**
   * Fetch a fresh snapshot through the App's own tunnel and keep it.
   *
   * <p>Through the tunnel rather than straight at the PC: the tunnel is already pointed at
   * whichever address answered, and when the PC is gone its fallback answers this route with a
   * plain 503 - which is a cheap way to find out there is nothing to fetch.
   *
   * @return true when a new copy landed.
   */
  static boolean sync(Context c, int tunnelPort, String key) {
    HttpURLConnection conn = null;
    File part = null;
    try {
      conn =
          (HttpURLConnection)
              new URL("http://localhost:" + tunnelPort + ROUTE).openConnection();
      conn.setConnectTimeout(4000);
      conn.setReadTimeout(30000);
      // The bridge's own gate, the same cookie Upstream.lanCandidates sends.
      conn.setRequestProperty("Cookie", "dsh-bridge=" + key);
      if (conn.getResponseCode() != 200) return false;
      File dir = file(c).getParentFile();
      dir.mkdirs();
      part = File.createTempFile("snapshot", ".part", dir);
      InputStream in = conn.getInputStream();
      FileOutputStream out = new FileOutputStream(part);
      long total = 0;
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
        total += n;
      }
      out.close();
      in.close();
      if (total < 2 || total > MAX_BYTES) return false;
      // rename(2) replaces an existing file, so a failed sync cannot destroy the old copy.
      if (!part.renameTo(file(c))) return false;
      part = null;
      return true;
    } catch (IOException e) {
      Log.i(TAG, "offline snapshot not fetched: " + e);
      return false;
    } finally {
      if (conn != null) conn.disconnect();
      if (part != null) part.delete();
    }
  }

  /**
   * The document the tunnel serves when the upstream cannot be reached: the shell's own colours
   * and the stored conversations, with the snapshot inlined so the page loads in one request.
   */
  static String page(Context c, String reason) {
    return render(template(c), reason, syncedAt(c), System.currentTimeMillis(), snapshot(c));
  }

  /**
   * The template with the marker replaced. Split out from {@link #page} so the JVM test can drive
   * it with a plain string: this is where an arbitrary session title meets a {@code <script>} block,
   * and that is exactly the part worth pinning down.
   */
  static String render(String template, String reason, long syncedAt, long now, String snapshot) {
    StringBuilder js = new StringBuilder(128);
    js.append("{\"why\":");
    js.append(escape(quote(reason == null ? "" : reason)));
    js.append(",\"syncedAt\":").append(syncedAt);
    js.append(",\"now\":").append(now);
    js.append(",\"snapshot\":");
    // `null` when nothing has ever been stored: the page says so rather than showing an empty list.
    js.append(snapshot == null ? "null" : escape(snapshot));
    js.append("}");
    return template.replace(MARKER, js.toString());
  }

  /**
   * A JSON literal is safe inside a {@code <script>} only once no {@code <} survives: a session
   * title is arbitrary text and {@code </script>} in one would end the block. {@code \u003c} is
   * still exactly the same JSON, and it is why the whole snapshot can be inlined at all.
   */
  private static String escape(String json) {
    return json.indexOf('<') < 0 ? json : json.replace("<", "\\u003c");
  }

  /**
   * A JSON string literal, escaped by hand rather than with {@code JSONObject.quote}: that method is
   * a stub in android.jar, so calling it would put the one piece of this class that has to be right
   * out of reach of the JVM test (see {@code android/test/OfflineTest.java}).
   */
  private static String quote(String s) {
    StringBuilder b = new StringBuilder(s.length() + 16).append('"');
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        case '\b': b.append("\\b"); break;
        case '\f': b.append("\\f"); break;
        default:
          // Below 0x20 there is no literal form in JSON, and U+2028/2029 end a JS line even inside
          // a string - either one would turn the inlined object into a syntax error.
          if (ch < 0x20 || ch == '\u2028' || ch == '\u2029') {
            b.append(String.format("\\u%04x", (int) ch));
          } else {
            b.append(ch);
          }
      }
    }
    return b.append('"').toString();
  }

  private static String template(Context c) {
    String t = template;
    if (t != null) return t;
    try {
      InputStream in = c.getAssets().open("offline.html");
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) > 0) buf.write(b, 0, n);
      in.close();
      t = new String(buf.toByteArray(), StandardCharsets.UTF_8);
      template = t;
      return t;
    } catch (IOException e) {
      // Only reachable from a broken build (the asset is packaged with the code), but a page
      // that says nothing at all is the one outcome this whole class exists to prevent.
      Log.w(TAG, "cannot read the offline page: " + e);
      return "<!doctype html><meta charset=\"utf-8\">"
          + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
          + "<body style=\"font:16px/1.6 system-ui,sans-serif;padding:28px\">"
          + "<p>连不上电脑上的 DSH，且本地离线页面读取失败。</p></body>";
    }
  }
}
