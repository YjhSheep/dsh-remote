package app.dsh.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * The one place that knows the stored connection settings and how an address is written.
 *
 * <p>Keys: {@code host}, {@code port}, {@code key}, {@code scheme}, {@code link}, plus {@code
 * lanHost}/{@code lanPort} - the LAN address that last answered, cached by {@link Upstream} so a
 * phone that just booted still reaches the PC while the beacon is not up yet.
 */
public final class Prefs {

  public static final String NAME = "dsh-remote";
  public static final int DEFAULT_PORT = 3080;

  private Prefs() {}

  private static SharedPreferences of(Context c) {
    return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
  }

  public static String host(Context c) {
    return of(c).getString("host", "").trim();
  }

  public static int port(Context c) {
    return of(c).getInt("port", DEFAULT_PORT);
  }

  public static String key(Context c) {
    return of(c).getString("key", "");
  }

  /** {@code "http"} or {@code "https"}: what the configured address speaks. */
  public static String scheme(Context c) {
    String s = of(c).getString("scheme", "http");
    return "https".equals(s) ? "https" : "http";
  }

  /** As typed and saved, for showing back in the setup dialog. */
  public static String link(Context c) {
    String link = of(c).getString("link", "");
    if (!link.isEmpty()) return link;
    String host = host(c);
    return host.isEmpty() ? "" : scheme(c) + "://" + host + ":" + port(c);
  }

  public static boolean ready(Context c) {
    return !host(c).isEmpty();
  }

  /** The cached LAN winner as {@code "ip:port"}, or {@code ""} when there is none yet. */
  public static String lan(Context c) {
    String host = lanHost(c);
    return host.isEmpty() ? "" : host + ":" + lanPort(c);
  }

  public static String lanHost(Context c) {
    return of(c).getString("lanHost", "").trim();
  }

  public static int lanPort(Context c) {
    return of(c).getInt("lanPort", 0);
  }

  public static void rememberLan(Context c, String host, int port) {
    of(c).edit().putString("lanHost", host).putInt("lanPort", port).apply();
  }

  /**
   * Accepts "192.168.31.216:3080" or the full "http://.../?k=KEY" the bridge prints.
   *
   * @return {@code {host, port, key, scheme}}
   * @throws IllegalArgumentException with a message meant for the user, verbatim.
   */
  public static String[] parse(Context c, String raw, String typedKey) {
    String text = raw.trim();
    if (text.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.err_no_addr));
    if (!text.contains("://")) text = "http://" + text;
    Uri u = Uri.parse(text);
    String host = u.getHost();
    if (host == null || host.isEmpty()) throw new IllegalArgumentException("地址看不懂：" + raw);
    int port = u.getPort() > 0 ? u.getPort() : DEFAULT_PORT;
    String key = typedKey.trim();
    if (key.isEmpty() && u.getQueryParameter("k") != null) key = u.getQueryParameter("k");
    String scheme = "https".equals(u.getScheme()) || "wss".equals(u.getScheme()) ? "https" : "http";
    return new String[] {host, String.valueOf(port), key, scheme};
  }

  /** Parses and saves in one step; throws the same way {@link #parse} does. */
  public static void save(Context c, String raw, String typedKey) {
    String[] t = parse(c, raw, typedKey);
    store(c, t[0], Integer.parseInt(t[1]), t[2], t[3]);
  }

  public static void store(Context c, String host, int port, String key, String scheme) {
    of(c)
        .edit()
        .putString("link", scheme + "://" + host + ":" + port)
        .putString("host", host)
        .putInt("port", port)
        .putString("key", key)
        .putString("scheme", scheme)
        .apply();
  }
}
