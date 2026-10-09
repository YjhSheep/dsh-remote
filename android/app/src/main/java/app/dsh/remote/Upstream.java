package app.dsh.remote;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which address the app talks to, whether it answers, and which one to try first.
 *
 * <p>One copy of the discovery rules for both the native screens and the WebView shell: the
 * configured address is the beacon, the bridge answers {@code /__bridge/lan.json} with the PC's
 * live LAN addresses, the last winner is cached in prefs (that covers the beacon being briefly
 * unreachable right after boot), and the first candidate a plain TCP connect reaches wins. The
 * beacon is what DSH calls last, so a same-LAN phone stops paying for a public round trip on every
 * request.
 */
public final class Upstream {

  private static final String TAG = "dsh-remote";

  /** A candidate probe wants a much shorter patience than the full pre-load check. */
  public static final int PROBE_TIMEOUT_MS = 700;

  public static final int BEACON_TIMEOUT_MS = 2000;

  private Upstream() {}

  /** What a client connects to: the winner, the scheme it speaks, and whether it is the LAN one. */
  public static final class Link {
    public final String host;
    public final int port;
    public final String scheme;
    public final boolean lan;

    Link(String host, int port, String scheme, boolean lan) {
      this.host = host;
      this.port = port;
      this.scheme = scheme;
      this.lan = lan;
    }

    public String label() {
      return host + ":" + port;
    }
  }

  /** Reads the stored address, picks the best one for the current network, and describes it. */
  public static Link pick(Context c, String key) {
    String host = Prefs.host(c);
    int port = Prefs.port(c);
    String[] win = choose(c, host, port, key);
    int winPort = Integer.parseInt(win[1]);
    boolean lan = !(win[0].equals(host) && winPort == port);
    // A LAN candidate is always the bridge itself, which speaks plain HTTP (no TLS in
    // tools/dsh-lan-bridge.cjs); only the configured address can be https.
    return new Link(win[0], winPort, lan ? "http" : Prefs.scheme(c), lan);
  }

  /**
   * The best address to use, in order: the cached LAN winner, then everything the beacon knows
   * about, then the beacon itself.
   *
   * @return {@code {host, port}} of the winner, falling back to the configured address when nothing
   *     answers.
   */
  public static String[] choose(Context c, String beaconHost, int beaconPort, String key) {
    List<String[]> candidates = new ArrayList<String[]>();
    String cached = Prefs.lanHost(c);
    if (!cached.isEmpty()) addCandidate(candidates, cached, Prefs.lanPort(c));
    for (String[] a : lanCandidates(beaconHost, beaconPort, key)) {
      addCandidate(candidates, a[0], Integer.parseInt(a[1]));
    }
    for (String[] candidate : candidates) {
      int port = Integer.parseInt(candidate[1]);
      if (probe(candidate[0], port, PROBE_TIMEOUT_MS) == null) {
        Log.i(
            TAG,
            "upstream "
                + candidate[0]
                + ":"
                + port
                + " answered directly ("
                + candidates.size()
                + " candidate(s))");
        Prefs.rememberLan(c, candidate[0], port);
        return candidate;
      }
      Log.i(TAG, "upstream " + candidate[0] + ":" + port + " did not answer");
    }
    if (!candidates.isEmpty()) {
      Log.i(TAG, "no LAN address answered, using the configured " + beaconHost + ":" + beaconPort);
    }
    return new String[] {beaconHost, String.valueOf(beaconPort)};
  }

  /** Keeps the candidate list free of duplicates without reordering it (first wins the probe). */
  private static void addCandidate(List<String[]> list, String host, int port) {
    if (host == null || host.isEmpty() || port <= 0) return;
    for (String[] c : list) {
      if (c[0].equals(host) && Integer.parseInt(c[1]) == port) return;
    }
    list.add(new String[] {host, String.valueOf(port)});
  }

  /**
   * The PC's live LAN address list, read through the beacon ({@code /__bridge/lan.json}).
   *
   * <p>Empty whenever the beacon does not answer - which is normal on a phone that just left one
   * network for another - because the cached address is then the only lead left.
   */
  public static List<String[]> lanCandidates(String host, int port, String key) {
    List<String[]> out = new ArrayList<String[]>();
    HttpURLConnection c = null;
    try {
      c =
          (HttpURLConnection)
              new URL("http://" + host + ":" + port + "/__bridge/lan.json").openConnection();
      c.setConnectTimeout(BEACON_TIMEOUT_MS);
      c.setReadTimeout(BEACON_TIMEOUT_MS);
      c.setRequestProperty("Cookie", "dsh-bridge=" + key);
      int code = c.getResponseCode();
      if (code != 200) {
        Log.i(TAG, "lan.json -> " + code + " via the configured " + host + ":" + port);
        return out;
      }
      StringBuilder body = new StringBuilder();
      BufferedReader reader =
          new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
      String line;
      while ((line = reader.readLine()) != null) body.append(line);
      reader.close();
      JSONObject json = new JSONObject(body.toString());
      JSONArray addresses = json.optJSONArray("addresses");
      int lanPort = json.optInt("port", Prefs.DEFAULT_PORT);
      for (int i = 0; addresses != null && i < addresses.length(); i++) {
        String a = addresses.optString(i, "");
        if (!a.isEmpty()) out.add(new String[] {a, String.valueOf(lanPort)});
      }
      Log.i(TAG, "lan.json -> " + out.size() + " candidate(s) via the configured " + host + ":" + port);
    } catch (Exception e) {
      Log.i(TAG, "lan.json failed via the configured " + host + ":" + port + ": " + e);
    } finally {
      if (c != null) c.disconnect();
    }
    return out;
  }

  /** @return null when the bridge accepts a connection, otherwise what went wrong. */
  public static String probe(String host, int port) {
    return probe(host, port, 4000);
  }

  /** The same check with the caller's own patience - a candidate probe wants a much shorter one. */
  public static String probe(String host, int port, int timeoutMs) {
    Socket s = new Socket();
    try {
      s.connect(new InetSocketAddress(host, port), timeoutMs);
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
