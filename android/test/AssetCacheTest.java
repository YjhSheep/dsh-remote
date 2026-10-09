package app.dsh.remote;

/**
 * The filter that decides what may be cached, and the type the cached bytes are handed back as.
 *
 *   javac -encoding UTF-8 -cp ..\android-sdk\platforms\android-34\android.jar -d ..\build\cachetest ^
 *     app\src\main\java\app\dsh\remote\AssetCache.java test\AssetCacheTest.java
 *   java -cp "..\build\cachetest;..\android-sdk\platforms\android-34\android.jar" app.dsh.remote.AssetCacheTest
 *
 * android.jar is on the path only so AssetCache's imports resolve; nothing here calls the platform.
 * Both halves are worth a check because getting either wrong is silent: caching a polled
 * /dsh-whale/*.json freezes the widget on old numbers, and serving a /plugins/ combo as
 * octet-stream makes the browser refuse every plugin.
 */
public final class AssetCacheTest {
  public static void main(String[] args) {
    // Live data, live injection, the SSE stream, and the document that names the current build.
    String[] live = {
      "/",
      "/api/session/list",
      "/__bridge/mobile.css",
      "/__bridge/probe.js",
      "/plugins/events",
      "/dsh-whale/wait.json",
      "/dsh-whale/last-turn.json",
      "/dsh-whale/balance.json",
      "/dsh-whale/usage-settings.json",
      "/dsh-whale/api-models.json",
    };
    for (String path : live) {
      check(AssetCache.maxAge(path) == 0, path + " must not be cached");
    }

    // Content-hashed or rev-pinned: the URL changes whenever the bytes do, so it never expires.
    String[] immutable = {
      "/assets/index-5SrrfWpU.js",
      "/assets/vendor-CCJJTK99.js",
      "/assets/index-BPHePDI_.css",
      "/assets/langs/java-CylS5w8V.js",
      "/assets/fonts/KaTeX_Main-Regular-B22Nviop.woff2",
      "/plugins/",
      "/plugins/@deepseek-ai/dsh-client-modules/client.main.js",
    };
    for (String path : immutable) {
      check(AssetCache.maxAge(path) == Long.MAX_VALUE, path + " must be cached indefinitely");
    }

    // Plain names, so cached - but only until the window runs out.
    String[] plainNames = {
      "/dsh-whale/widget.js",
      "/dsh-whale/image.png",
      "/dsh-whale/rua.gif",
      "/dsh-whale/sound/press.mp3",
      "/dsh-whale/audio-fragment.wav",
    };
    for (String path : plainNames) {
      long age = AssetCache.maxAge(path);
      check(age > 0 && age < Long.MAX_VALUE, path + " must be cached but expiring, got " + age);
    }

    // Keyed by path + query, exactly as WebActivity builds it: the combo's query starts with '?'.
    check(
        AssetCache.mime("/plugins/??@a/b/client.js&rev=1f9243306061")
            .equals("application/javascript"),
        "combo mime");
    check(
        AssetCache.mime("/plugins/@a/b/client.main.js?rev=1f9243306061")
            .equals("application/javascript"),
        "chunk mime");
    check(AssetCache.mime("/assets/index-BPHePDI_.css").equals("text/css"), "css mime");
    check(AssetCache.mime("/assets/fonts/x-B22Nviop.woff2").equals("font/woff2"), "font mime");
    check(AssetCache.mime("/dsh-whale/image.png").equals("image/png"), "png mime");
    check(AssetCache.mime("/dsh-whale/sound/press.mp3").equals("audio/mpeg"), "mp3 mime");

    System.out.println("AssetCacheTest OK");
  }

  private static void check(boolean ok, String what) {
    if (!ok) throw new AssertionError(what);
  }
}
