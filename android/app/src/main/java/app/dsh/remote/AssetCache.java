package app.dsh.remote;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Disk cache for the shell's immutable build outputs.
 *
 * <p>A measured open pulled 10.26 MB over 185 requests - 5.3 MB of it one /plugins/ combo, most of
 * the rest /assets/ bundles and the whale's media - through a cross-border tunnel doing 40-75 KB/s.
 * That is the two-to-four minutes of waiting, and none of those bytes can have changed: /assets/
 * files are named by content hash, and a /plugins/ combo carries a rev derived from each entry's
 * mtime, ctime and size, so a new build asks for a new URL. Keeping them on disk is free of
 * staleness, and the page then paints from the cache while its API calls refresh the data.
 *
 * <p>Only those GETs are intercepted. /api, SSE, WebSocket, the polled /dsh-whale/*.json and the
 * injected /__bridge/* files (editing mobile.css and refreshing the phone is the style loop) go
 * straight to the network. Anything unrecognised, any miss, any non-200, any failed write returns
 * null and hands the request back to the WebView's own stack, so this can only make a load faster.
 */
final class AssetCache {
  private static final String TAG = "dsh-remote";
  /** Never expires: the URL itself changes whenever the bytes do. */
  private static final long FOREVER = Long.MAX_VALUE;
  /** The whale's media is addressed by plain name, so a cached copy does go stale eventually. */
  private static final long MEDIA_AGE_MS = 7L * 24 * 60 * 60 * 1000;
  /** The live set is ~8 MB; this only ever fires on debris left by old builds. */
  private static final long MAX_BYTES = 64L * 1024 * 1024;
  private static final String[] MEDIA = {
    ".js", ".css", ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".mp3", ".wav", ".woff2"
  };

  private final File dir;

  AssetCache(Context ctx) {
    dir = new File(ctx.getCacheDir(), "shell");
  }

  /**
   * How long a cached copy of {@code path} stays good, or 0 to leave the request alone. The
   * /dsh-whale/ case is why this is a window rather than a boolean: those names carry no hash, so
   * only a time limit keeps an edited image from being pinned forever.
   */
  static long maxAge(String path) {
    if (path.startsWith("/assets/")) return FOREVER;
    // /plugins/ serves two script shapes - the /plugins/??<a,b,...>&rev= combo, whose path is bare,
    // and /plugins/<pkg>/client.<name>.js?rev=<rev> chunks - plus /plugins/events, the SSE stream,
    // which must keep flowing.
    if (path.equals("/plugins/") || (path.startsWith("/plugins/") && path.endsWith(".js"))) {
      return FOREVER;
    }
    if (!path.startsWith("/dsh-whale/")) return 0;
    String name = path.toLowerCase(Locale.US);
    for (String ext : MEDIA) {
      if (name.endsWith(ext)) return MEDIA_AGE_MS;
    }
    return 0;
  }

  /**
   * The cached copy of {@code key}, or a freshly downloaded one. {@code url} is what the page asked
   * for; {@code key} is its path and query, which outlives the tunnel picking a different port.
   */
  WebResourceResponse serve(String url, String key, long maxAge) {
    File body = file(key);
    if (body.isFile()
        && body.length() > 0
        && (maxAge == FOREVER || System.currentTimeMillis() - body.lastModified() <= maxAge)) {
      try {
        return reply(body, key);
      } catch (IOException e) {
        Log.w(TAG, "cached copy unreadable, refetching " + key + ": " + e);
      }
    }
    return fetch(url, key, body);
  }

  private WebResourceResponse fetch(String url, String key, File body) {
    HttpURLConnection c = null;
    File part = null;
    try {
      c = (HttpURLConnection) new URL(url).openConnection();
      c.setConnectTimeout(8000);
      c.setReadTimeout(30000);
      // Accept-Encoding is deliberately left alone: Android then gunzips transparently and hands
      // back plain bytes, which is what the WebView wants and what gets stored.
      String cookie = CookieManager.getInstance().getCookie(url);
      if (cookie != null) c.setRequestProperty("Cookie", cookie);
      if (c.getResponseCode() != 200) return null;
      dir.mkdirs();
      // A unique temp name per fetch: two threads racing on one URL cannot interleave into a
      // half-written file, and both are writing the same bytes anyway.
      part = File.createTempFile("shell", ".part", dir);
      InputStream in = c.getInputStream();
      OutputStream out = new FileOutputStream(part);
      long total = 0;
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
        total += n;
      }
      out.close();
      in.close();
      if (total == 0 || !part.renameTo(body)) return null;
      part = null;
      trim();
      return reply(body, key);
    } catch (IOException e) {
      Log.w(TAG, "cannot fill cache, leaving " + key + " to the WebView: " + e);
      return null;
    } finally {
      if (c != null) c.disconnect();
      if (part != null) part.delete();
    }
  }

  private WebResourceResponse reply(File body, String key) throws IOException {
    Map<String, String> headers = new HashMap<String, String>();
    headers.put("Content-Length", String.valueOf(body.length()));
    String mime = mime(key);
    return new WebResourceResponse(mime, charset(mime), 200, "OK", headers, new FileInputStream(body));
  }

  /** Everything under /plugins/ is a JavaScript combo, and a combo's URL has no extension to go by. */
  static String mime(String key) {
    String name = key.toLowerCase(Locale.US);
    int q = name.indexOf('?');
    if (q >= 0) name = name.substring(0, q);
    if (name.endsWith(".js")) return "application/javascript";
    if (name.endsWith(".css")) return "text/css";
    if (name.endsWith(".svg")) return "image/svg+xml";
    if (name.endsWith(".png")) return "image/png";
    if (name.endsWith(".gif")) return "image/gif";
    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
    if (name.endsWith(".webp")) return "image/webp";
    if (name.endsWith(".ico")) return "image/x-icon";
    if (name.endsWith(".mp3")) return "audio/mpeg";
    if (name.endsWith(".wav")) return "audio/wav";
    if (name.endsWith(".woff2")) return "font/woff2";
    if (name.startsWith("/plugins/")) return "application/javascript";
    return "application/octet-stream";
  }

  private static String charset(String mime) {
    boolean text =
        mime.startsWith("text/")
            || mime.equals("application/javascript")
            || mime.equals("image/svg+xml");
    return text ? "utf-8" : null;
  }

  private void trim() {
    File[] files = dir.listFiles();
    if (files == null) return;
    long total = 0;
    for (File f : files) {
      total += f.length();
    }
    if (total <= MAX_BYTES) return;
    Log.w(TAG, "shell cache over " + MAX_BYTES + " bytes, dropping " + files.length + " files");
    for (File f : files) {
      f.delete();
    }
  }

  private File file(String key) {
    return new File(dir, name(key) + ".bin");
  }

  private static String name(String key) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
      StringBuilder sb = new StringBuilder(h.length * 2);
      for (byte b : h) {
        sb.append(Character.forDigit((b >> 4) & 0xf, 16));
        sb.append(Character.forDigit(b & 0xf, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      // SHA-256 and UTF-8 are both guaranteed present; a collision here would only mean a refetch.
      return Integer.toHexString(key.hashCode());
    }
  }
}
