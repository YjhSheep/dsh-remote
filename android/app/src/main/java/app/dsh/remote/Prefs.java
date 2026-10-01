package app.dsh.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 连接配置的唯一出处：网页版壳（WebActivity）和原生页（MainActivity/ChatActivity）读写同一份 prefs，
 * 所以两个界面看到的是同一台电脑、同一个密钥。
 *
 * <p>键名与旧版网页壳完全一致（link/host/port/key），另加 scheme 以支持 https 隧道。
 */
public final class Prefs {

  public static final String NAME = "dsh-remote";
  public static final int DEFAULT_PORT = 3080;

  private Prefs() {}

  private static SharedPreferences of(Context c) {
    return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
  }

  public static String host(Context c) {
    return of(c).getString("host", "");
  }

  public static int port(Context c) {
    return of(c).getInt("port", DEFAULT_PORT);
  }

  public static String key(Context c) {
    return of(c).getString("key", "");
  }

  public static String scheme(Context c) {
    String s = of(c).getString("scheme", "http");
    return "https".equals(s) ? "https" : "http";
  }

  public static String link(Context c) {
    return of(c).getString("link", "");
  }

  /** 是否已经填过电脑地址。 */
  public static boolean ready(Context c) {
    String h = host(c);
    return h != null && !h.isEmpty();
  }

  /**
   * 接受桥接打印的两种写法：{@code 192.168.31.216:3080} 或完整的 {@code http://.../?k=KEY}。
   *
   * @return {host, port, key, scheme}
   * @throws IllegalArgumentException 地址为空或看不懂（消息直接拿来提示用户）
   */
  public static String[] parse(String raw, String typedKey) {
    String text = raw == null ? "" : raw.trim();
    if (text.isEmpty()) throw new IllegalArgumentException("请填电脑地址");
    boolean secure = text.startsWith("https://") || text.startsWith("wss://");
    if (!text.contains("://")) text = "http://" + text;
    Uri u = Uri.parse(text);
    String host = u.getHost();
    if (host == null || host.isEmpty()) throw new IllegalArgumentException("地址看不懂：" + raw);
    int port = u.getPort() > 0 ? u.getPort() : DEFAULT_PORT;
    String key = typedKey == null ? "" : typedKey.trim();
    if (key.isEmpty() && u.getQueryParameter("k") != null) key = u.getQueryParameter("k");
    return new String[] {host, String.valueOf(port), key == null ? "" : key, secure ? "https" : "http"};
  }

  /** 解析并落盘，解析失败会抛 {@link IllegalArgumentException}。 */
  public static void save(Context c, String raw, String typedKey) {
    String[] t = parse(raw, typedKey);
    store(c, t[0], Integer.parseInt(t[1]), t[2], t[3]);
  }

  public static void store(Context c, String host, int port, String key, String scheme) {
    String s = "https".equals(scheme) ? "https" : "http";
    of(c)
        .edit()
        .putString("link", s + "://" + host + ":" + port)
        .putString("host", host)
        .putInt("port", port)
        .putString("key", key == null ? "" : key)
        .putString("scheme", s)
        .apply();
  }

  /**
   * 打开一条到桥接的 TCP 连接，只用来回答"电脑是不是通的"。
   *
   * @return null 表示能连上，否则返回原因
   */
  public static String probe(String host, int port) {
    Socket s = new Socket();
    try {
      s.connect(new InetSocketAddress(host, port), 4000);
      return null;
    } catch (IOException e) {
      return e.toString();
    } finally {
      try {
        s.close();
      } catch (IOException ignored) {
        // nothing to clean up
      }
    }
  }
}