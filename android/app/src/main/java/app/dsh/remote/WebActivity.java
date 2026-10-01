package app.dsh.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A thin WebView around the desktop DSH, reached over a loopback tunnel (see {@link Tunnel}).
 *
 * <p>What it adds over the phone's browser: no address bar, the access key is stored instead of
 * living in the URL, file attachments work through the system picker, and the page is a genuine
 * loopback client - so DSH's settings / model pages behave exactly as they do on the PC.
 *
 * <p>The shell is deliberately plain framework widgets (no AndroidX): three states only - loading,
 * connected, or a page that names the address and the reason it failed.
 */
public class WebActivity extends Activity {
  private static final String TAG = "dsh-remote";
  private static final String PREFS = "dsh-remote";
  private static final int LOCAL_PORT = 17800;
  private static final int DEFAULT_PORT = 3080;
  private static final int PICK_FILES = 41;

  private Toolbar bar;
  private ProgressBar progress;
  private View overlay;
  private TextView ovTitle;
  private TextView ovReason;
  private WebView web;
  private Tunnel tunnel;
  private ValueCallback<Uri[]> pendingUpload;
  private String lastUrl;
  private String target = "";
  private boolean failed;
  /** Gesture-bar height in device px, and what the page has been told to leave room for. */
  private int gestureInsetPx;
  private int safeInsetPx;
  private int pushedInsetPx = -1;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    // Lets chrome://inspect on the PC drive this WebView over adb - the only way to measure the
    // real phone layout instead of guessing at it.
    WebView.setWebContentsDebuggingEnabled(true);
    setContentView(R.layout.activity_main);
    applyImmersive();

    bar = (Toolbar) findViewById(R.id.bar);
    bar.setTitle(R.string.app_name);
    bar.inflateMenu(R.menu.main);
    bar.setOnMenuItemClickListener(
        new Toolbar.OnMenuItemClickListener() {
          @Override
          public boolean onMenuItemClick(MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.action_setup) {
              showSetup();
              return true;
            }
            if (id == R.id.action_reload) {
              open();
              return true;
            }
            return false;
          }
        });

    progress = (ProgressBar) findViewById(R.id.progress);
    overlay = findViewById(R.id.overlay);
    ovTitle = (TextView) findViewById(R.id.ov_title);
    ovReason = (TextView) findViewById(R.id.ov_reason);
    findViewById(R.id.ov_retry).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            open();
          }
        });
    findViewById(R.id.ov_setup).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            showSetup();
          }
        });

    web = (WebView) findViewById(R.id.web);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setUseWideViewPort(true);
    s.setLoadWithOverviewMode(true);
    web.setWebViewClient(new Client());
    web.setWebChromeClient(new Chrome());

    if (prefs().getString("host", "").isEmpty()) {
      bar.setSubtitle(R.string.st_idle);
      showOverlay(R.string.setup_title, getString(R.string.setup_run));
      showSetup();
    } else {
      open();
    }
  }

  @Override
  protected void onDestroy() {
    stopTunnel();
    super.onDestroy();
  }

  private SharedPreferences prefs() {
    return getSharedPreferences(PREFS, MODE_PRIVATE);
  }

  private void stopTunnel() {
    if (tunnel != null) {
      tunnel.stop();
      tunnel = null;
    }
  }

  /**
   * Draw under the status and gesture bars, the way the DSH page is laid out anyway: the phone's
   * gesture pill then floats over the page's own background instead of sitting in a strip of our
   * colour, and it stops covering the composer's last row. We stay in charge of the insets - the
   * status bar becomes top padding, the keyboard becomes bottom padding (an edge-to-edge window is
   * no longer resized by android:windowSoftInputMode="adjustResize"), and the gesture bar's height
   * goes to the page as --dsh-safe-bottom so its bottom-anchored UI lifts itself; see
   * tools/bridge/mobile.css.
   *
   * <p>API 30+ only: below that the window resizes for the keyboard itself, and guessing the
   * keyboard inset from getSystemWindowInsetBottom() would break the keyboard. Older devices keep
   * the legacy opaque bars and non-edge-to-edge layout.
   *
   * <p>The insets go through androidx.core rather than the platform WindowInsets: the platform
   * version of getInsets()/WindowInsets.Type only exists from API 29/30, and this activity is also
   * installed on API 26 phones (lint NewApi on the plain calls; androidx also covers API 26 with the
   * same {@link WindowInsetsCompat} types).
   */
  private void applyImmersive() {
    if (Build.VERSION.SDK_INT < 30) return;
    Window w = getWindow();
    w.setDecorFitsSystemWindows(false);
    w.setStatusBarColor(Color.TRANSPARENT);
    w.setNavigationBarColor(Color.TRANSPARENT);
    // Without this the system paints a translucent scrim behind the gesture bar, i.e. exactly the
    // band we are trying to get rid of.
    w.setNavigationBarContrastEnforced(false);
    ViewCompat.setOnApplyWindowInsetsListener(
        findViewById(R.id.root),
        new OnApplyWindowInsetsListener() {
          @Override
          public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
            Insets bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(bars.left, bars.top, bars.right, ime);
            gestureInsetPx = bars.bottom;
            // With the keyboard up this WebView already ends above it, so the page must not add
            // the gesture-bar gap on top of that.
            safeInsetPx = ime > 0 ? 0 : bars.bottom;
            applySafeBottom(safeInsetPx);
            return insets;
          }
        });
  }

  /** Tell the page how much room its bottom-anchored UI must leave, in CSS px (insets are device
   * px, and the WebView's CSS px scale is the display density). */
  private void applySafeBottom(int px) {
    if (px == pushedInsetPx) return;
    pushedInsetPx = px;
    if (web == null) return;
    float css = px / getResources().getDisplayMetrics().density;
    web.evaluateJavascript(
        "document.documentElement.style.setProperty('--dsh-safe-bottom','" + css + "px')", null);
  }

  private void setStatus(int resId) {
    bar.setSubtitle(target.isEmpty() ? getString(resId) : getString(resId) + " · " + target);
  }

  /** WebView out of the way, one explaining panel in - used for every failure and for first run. */
  private void showOverlay(int titleRes, String reason) {
    failed = true;
    ovTitle.setText(titleRes);
    ovReason.setText(reason);
    progress.setVisibility(View.GONE);
    web.setVisibility(View.GONE);
    overlay.setVisibility(View.VISIBLE);
  }

  private void hideOverlay() {
    failed = false;
    overlay.setVisibility(View.GONE);
    web.setVisibility(View.VISIBLE);
  }

  /** Bind the loopback port, check the PC is actually reachable, then point the WebView at it. */
  private void open() {
    stopTunnel();
    SharedPreferences p = prefs();
    final String host = p.getString("host", "");
    final int port = p.getInt("port", DEFAULT_PORT);
    String key = p.getString("key", "");
    target = host + ":" + port;
    try {
      tunnel = new Tunnel(host, port, LOCAL_PORT);
    } catch (IOException e) {
      showProblem("127.0.0.1:" + LOCAL_PORT, "本机端口起不来：" + e);
      return;
    }
    tunnel.start();
    // ?k= makes the bridge answer 303 + Set-Cookie for host 127.0.0.1, so every later request
    // (document, /api, SSE, WS) is already inside the gate.
    lastUrl = "http://127.0.0.1:" + tunnel.port() + "/" + (key.isEmpty() ? "" : "?k=" + Uri.encode(key));
    Log.i(TAG, "load " + lastUrl + " -> " + target);
    setStatus(R.string.st_connecting);
    hideOverlay();
    progress.setProgress(0);
    progress.setVisibility(View.VISIBLE);
    // Probe the PC first: an unreachable or missing bridge would otherwise show up as a blank page
    // that says nothing about which of the two hops failed.
    Thread probe =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                final String problem = probeUpstream(host, port);
                runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (problem == null) {
                          web.loadUrl(lastUrl);
                        } else {
                          showProblem(target, problem);
                        }
                      }
                    });
              }
            },
            "dsh-probe");
    probe.setDaemon(true);
    probe.start();
  }

  /** @return null when the bridge accepts a connection, otherwise what went wrong. */
  private static String probeUpstream(String host, int port) {
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

  /** Says which address failed and why - the socket error names the phone's own address too. */
  private void showProblem(String target, String reason) {
    setStatus(R.string.st_failed);
    showOverlay(
        R.string.err_title,
        getString(R.string.err_target)
            + "："
            + target
            + "\n"
            + getString(R.string.err_reason)
            + "："
            + reason
            + "\n"
            + getString(R.string.err_webview)
            + "："
            + webViewLabel());
  }

  private String webViewLabel() {
    try {
      android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
      return pi == null ? "不可用" : pi.versionName;
    } catch (Exception e) {
      return "?";
    }
  }

  private void showSetup() {
    final SharedPreferences p = prefs();
    View form = LayoutInflater.from(this).inflate(R.layout.dialog_setup, null);
    final EditText link = (EditText) form.findViewById(R.id.setup_link);
    final EditText key = (EditText) form.findViewById(R.id.setup_key);
    final TextView probe = (TextView) form.findViewById(R.id.setup_probe);
    TextView note = (TextView) form.findViewById(R.id.setup_note);
    link.setText(p.getString("link", ""));
    key.setText(p.getString("key", ""));
    String host = p.getString("host", "");
    if (!host.isEmpty()) {
      note.setText(
          getString(R.string.setup_note_map, LOCAL_PORT, host, p.getInt("port", DEFAULT_PORT)));
    }
    form.findViewById(R.id.setup_test)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View v) {
                test(link.getText().toString(), key.getText().toString(), probe);
              }
            });
    new AlertDialog.Builder(this)
        .setTitle(R.string.action_setup)
        .setView(form)
        .setPositiveButton(
            R.string.connect,
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                save(link.getText().toString(), key.getText().toString());
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /** Round-trips the address as typed, without saving it - the form's own answer to "why not". */
  private void test(String raw, String typedKey, final TextView probe) {
    final String[] t = parse(raw, typedKey);
    if (t == null) return;
    probe.setVisibility(View.VISIBLE);
    probe.setTextColor(getColor(R.color.ink_dim));
    probe.setText(getString(R.string.probe_testing) + " " + t[0] + ":" + t[1]);
    Thread thread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                final String problem = probeUpstream(t[0], Integer.parseInt(t[1]));
                runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (problem == null) {
                          probe.setTextColor(getColor(R.color.ok));
                          probe.setText(getString(R.string.probe_ok) + " " + t[0] + ":" + t[1]);
                        } else {
                          probe.setTextColor(getColor(R.color.bad));
                          probe.setText(getString(R.string.probe_fail) + "：" + problem);
                        }
                      }
                    });
              }
            },
            "dsh-test");
    thread.setDaemon(true);
    thread.start();
  }

  /** Accepts "192.168.31.216:3080" or the full "http://.../?k=KEY" the bridge prints. */
  private String[] parse(String raw, String typedKey) {
    String text = raw.trim();
    if (text.isEmpty()) {
      toast(getString(R.string.err_no_addr));
      return null;
    }
    if (!text.contains("://")) text = "http://" + text;
    Uri u = Uri.parse(text);
    String host = u.getHost();
    if (host == null || host.isEmpty()) {
      toast("地址看不懂：" + raw);
      return null;
    }
    int port = u.getPort() > 0 ? u.getPort() : DEFAULT_PORT;
    String key = typedKey.trim();
    if (key.isEmpty() && u.getQueryParameter("k") != null) key = u.getQueryParameter("k");
    return new String[] {host, String.valueOf(port), key};
  }

  private void save(String raw, String typedKey) {
    String[] t = parse(raw, typedKey);
    if (t == null) return;
    prefs()
        .edit()
        .putString("link", "http://" + t[0] + ":" + t[1])
        .putString("host", t[0])
        .putInt("port", Integer.parseInt(t[1]))
        .putString("key", t[2])
        .apply();
    open();
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    if (req == PICK_FILES) {
      ValueCallback<Uri[]> cb = pendingUpload;
      pendingUpload = null;
      if (cb != null) cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
      return;
    }
    super.onActivityResult(req, res, data);
  }

  private void toast(String msg) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
  }

  private final class Client extends WebViewClient {
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
      Uri u = req.getUrl();
      if ("127.0.0.1".equals(u.getHost())) return false; // keep the tunnel in charge
      try {
        startActivity(new Intent(Intent.ACTION_VIEW, u));
      } catch (Exception e) {
        Log.w(TAG, "cannot open " + u + ": " + e);
      }
      return true;
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
      if (failed) return;
      // A fresh document has no --dsh-safe-bottom on it: let the next push go through.
      pushedInsetPx = -1;
      setStatus(R.string.st_connecting);
      progress.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
      if (failed) return; // a late finish must not paper over the failure panel
      applySafeBottom(safeInsetPx);
      hideOverlay();
      setStatus(R.string.st_connected);
      progress.setVisibility(View.GONE);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
      if (!req.isForMainFrame()) return;
      String reason = String.valueOf(err.getDescription());
      Log.w(TAG, "load failed: " + reason + " @ " + req.getUrl());
      // On the screen, not just in logcat: this is the only evidence available when the phone
      // cannot be attached to a PC.
      showProblem(String.valueOf(req.getUrl()), reason);
    }
  }

  private final class Chrome extends WebChromeClient {
    @Override
    public void onProgressChanged(WebView view, int value) {
      if (failed) return;
      progress.setProgress(value);
      if (value >= 100) progress.setVisibility(View.GONE);
    }

    @Override
    public boolean onShowFileChooser(
        WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
      if (pendingUpload != null) pendingUpload.onReceiveValue(null);
      pendingUpload = cb;
      try {
        startActivityForResult(params.createIntent(), PICK_FILES);
        return true;
      } catch (Exception e) {
        Log.w(TAG, "file picker failed: " + e);
        pendingUpload = null;
        return false;
      }
    }
  }
}
