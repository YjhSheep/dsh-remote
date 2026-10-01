package app.dsh.remote;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 原生首页：列出电脑上的会话，点进去聊天。
 *
 * <p>说 DSH 自己的 HTTP 协议（见 {@link DshClient}），不依赖 WebView；旧的网页壳保留在
 * {@link WebActivity} 里当退路。
 */
public class MainActivity extends AppCompatActivity {

  /** 真机验收用的日志通道：uiautomator 在 MIUI 上取不到界面，只能靠 App 自己回报状态。 */
  private static final String TAG = "dsh-remote";

  private Toolbar bar;
  private RecyclerView list;
  private TextView state;
  private View stateBox;
  private FloatingActionButton fab;

  private SessionAdapter adapter;
  private DshClient client;
  private String workspaceId;
  private boolean loading;

  private final ExecutorService io = Executors.newSingleThreadExecutor();

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_sessions);

    bar = findViewById(R.id.bar);
    setSupportActionBar(bar);
    Ui.edgeToEdge(this, findViewById(R.id.root), bar);

    state = findViewById(R.id.state);
    stateBox = findViewById(R.id.state_box);
    fab = findViewById(R.id.fab);
    list = findViewById(R.id.list);
    list.setLayoutManager(new LinearLayoutManager(this));
    adapter =
        new SessionAdapter(
            new SessionAdapter.OnPick() {
              @Override
              public void onPick(JSONObject session) {
                openChat(session.optString("sessionId", ""));
              }
            });
    list.setAdapter(adapter);

    fab.setEnabled(false);
    fab.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            newSession();
          }
        });
  }

  @Override
  protected void onStart() {
    super.onStart();
    boolean dark =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    Log.i(
        TAG,
        "start ready="
            + Prefs.ready(this)
            + " dark="
            + dark
            + " api="
            + Build.VERSION.SDK_INT
            + " app="
            + appVersion());
    if (!Prefs.ready(this)) {
      setup();
      return;
    }
    connect();
    maybeSelfCheck();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    // MIUI 上 Activity 已经在前台时再 am start 只走 onNewIntent，不重跑 onStart，
    // 无头自检（--ez selfcheck true）必须在这里也接一次才不会被吞掉。
    setIntent(intent);
    maybeSelfCheck();
  }

  // 数据线验收用：adb shell am start -n app.dsh.remote/.MainActivity --ez selfcheck true
  private void maybeSelfCheck() {
    if (getIntent() != null && getIntent().getBooleanExtra("selfcheck", false)) {
      getIntent().removeExtra("selfcheck");
      selfCheck();
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (client != null) {
      client.close();
      client = null;
    }
    loading = false;
    workspaceId = null;
    fab.setEnabled(false);
  }

  // ------------------------------------------------------------------ 连接

  private void connect() {
    if (client != null) return;
    bar.setSubtitle(R.string.st_connecting);
    client =
        new DshClient(Prefs.host(this), Prefs.port(this), Prefs.key(this), Prefs.scheme(this));
    client.setStateListener(
        new DshClient.State() {
          @Override
          public void onState(final boolean online, final String reason) {
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    bar.setSubtitle(online ? R.string.st_connected : R.string.st_failed);
                    if (!online) {
                      show(
                          getString(R.string.err_title)
                              + (reason == null || reason.isEmpty() ? "" : "：" + reason));
                    }
                  }
                });
          }
        });

    // $events 只用来认连接代际：桥接或 DSH 重启后它会重新 ready，这时把列表刷一遍。
    client.open(
        "$events",
        new JSONObject(),
        new DshClient.Stream() {
          @Override
          public void onItem(JSONObject value) {
            if ("ready".equals(value.optString("type", ""))) refresh();
          }

          @Override
          public void onEnd() {}
        });

    // 新建会话要 workspaceId，而 workspace 命名空间没有 list 端点，只能从 follow 的 baseline 取。
    client.open(
        "workspace/follow",
        new JSONObject(),
        new DshClient.Stream() {
          @Override
          public void onItem(JSONObject value) {
            if (!"baseline".equals(value.optString("type", ""))) return;
            JSONObject v = value.optJSONObject("value");
            JSONArray items = v == null ? null : v.optJSONArray("items");
            if (items == null || items.length() == 0) return;
            JSONObject first = items.optJSONObject(0);
            if (first == null) return;
            workspaceId = first.optString("workspaceId", "");
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    fab.setEnabled(true);
                  }
                });
          }

          @Override
          public void onEnd() {}
        });

    refresh();
  }

  private void refresh() {
    final DshClient c = client;
    if (c == null || loading) return;
    loading = true;
    io.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              JSONObject args = new JSONObject();
              args.put("_request", new JSONObject());
              final JSONObject res = c.call("session/list", args);
              runOnUiThread(
                  new Runnable() {
                    @Override
                    public void run() {
                      loading = false;
                      JSONArray items = res.optJSONArray("items");
                      adapter.setSessions(items);
                      bar.setSubtitle(R.string.st_connected);
                      Log.i(
                          TAG,
                          "sessions n="
                              + adapter.count()
                              + " first="
                              + adapter.titleAt(0)
                              + " ws="
                              + workspaceId);
                      if (adapter.count() == 0) {
                        show(getString(R.string.sessions_empty));
                      } else {
                        stateBox.setVisibility(View.GONE);
                      }
                    }
                  });
            } catch (final Exception e) {
              runOnUiThread(
                  new Runnable() {
                    @Override
                    public void run() {
                      loading = false;
                      show(friendly(e));
                    }
                  });
            }
          }
        });
  }

  private void newSession() {
    final DshClient c = client;
    if (c == null) {
      toast("还没连上电脑");
      return;
    }
    final String ws = workspaceId;
    io.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              JSONObject request = new JSONObject();
              if (ws != null && !ws.isEmpty()) request.put("workspaceId", ws);
              JSONObject args = new JSONObject();
              args.put("request", request);
              final JSONObject res = c.call("session/create", args);
              runOnUiThread(
                  new Runnable() {
                    @Override
                    public void run() {
                      String id = res.optString("sessionId", "");
                      if (id.isEmpty()) {
                        toast("新建失败：响应里没有 sessionId");
                        return;
                      }
                      openChat(id);
                    }
                  });
            } catch (final Exception e) {
              runOnUiThread(
                  new Runnable() {
                    @Override
                    public void run() {
                      toast(friendly(e));
                    }
                  });
            }
          }
        });
  }

  private void openChat(String sessionId) {
    if (sessionId == null || sessionId.isEmpty()) {
      toast("这个会话没有 id");
      return;
    }
    Intent i = new Intent(this, ChatActivity.class);
    i.putExtra(ChatActivity.EXTRA_SESSION, sessionId);
    startActivity(i);
  }

  // ------------------------------------------------------------------ 设置

  private void setup() {
    View v = LayoutInflater.from(this).inflate(R.layout.dialog_connect, null);
    final EditText addr = v.findViewById(R.id.addr);
    final EditText key = v.findViewById(R.id.key);
    String host = Prefs.host(this);
    if (!host.isEmpty()) addr.setText(host + ":" + Prefs.port(this));
    key.setText(Prefs.key(this));

    final AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(R.string.setup_title)
            .setView(v)
            .setPositiveButton(R.string.connect, null)
            .setNeutralButton(R.string.probe_test, null)
            .setNegativeButton(R.string.cancel, null)
            .create();
    dialog.show();
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View x) {
                try {
                  Prefs.save(MainActivity.this, addr.getText().toString(), key.getText().toString());
                } catch (IllegalArgumentException e) {
                  toast(e.getMessage());
                  return;
                }
                dialog.dismiss();
                connect();
              }
            });
    dialog
        .getButton(AlertDialog.BUTTON_NEUTRAL)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View x) {
                final String[] t;
                try {
                  t = Prefs.parse(addr.getText().toString(), key.getText().toString());
                } catch (IllegalArgumentException e) {
                  toast(e.getMessage());
                  return;
                }
                toast(getString(R.string.probe_testing));
                io.execute(
                    new Runnable() {
                      @Override
                      public void run() {
                        final String problem = Prefs.probe(t[0], Integer.parseInt(t[1]));
                        runOnUiThread(
                            new Runnable() {
                              @Override
                              public void run() {
                                toast(
                                    problem == null
                                        ? getString(R.string.probe_ok)
                                        : getString(R.string.probe_fail) + "：" + problem);
                              }
                            });
                      }
                    });
              }
            });
  }

  // ------------------------------------------------------------------ 自检

  /**
   * 手机上的一键自检：把电脑上 tools\client-check 验的三件事在这儿跑一遍（HTTP 通不通、WebSocket 收不收
   * 得到 ready、会话流拿不拿得到 snapshot），排障不用插数据线；结果可以复制粘回来。
   */
  private void selfCheck() {
    if (!Prefs.ready(this)) {
      setup();
      return;
    }
    final String host = Prefs.host(this);
    final int port = Prefs.port(this);
    final String key = Prefs.key(this);
    final String scheme = Prefs.scheme(this);
    toast(getString(R.string.check_running));
    io.execute(
        new Runnable() {
          @Override
          public void run() {
            StringBuilder sb = new StringBuilder();
            sb.append("目标 ").append(scheme).append("://").append(host).append(':').append(port).append('\n');
            sb.append("密钥 ").append(key.isEmpty() ? "没填" : key.length() + " 位").append('\n');
            // 真机上的问题往往不在协议而在渲染，所以把设备环境一起带回电脑。
            boolean dark =
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            sb.append("设备 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
            sb.append("界面 ").append(dark ? "深色" : "浅色")
                .append(" / App ").append(appVersion()).append('\n');

            DshClient c = new DshClient(host, port, key, scheme);
            JSONObject picked = null;
            try {
              JSONObject args = new JSONObject();
              args.put("_request", new JSONObject());
              JSONObject res = c.call("session/list", args);
              JSONArray items = res.optJSONArray("items");
              int total = items == null ? 0 : items.length();
              if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                  JSONObject it = items.optJSONObject(i);
                  if (it != null && !"subagent".equals(it.optString("origin", ""))) {
                    picked = it;
                    break;
                  }
                }
              }
              sb.append(
                  total == 0
                      ? "1. HTTP 通了，但电脑上一个会话都没有\n"
                      : "1. HTTP 通了，会话 " + total + " 个\n");
            } catch (Exception e) {
              sb.append("1. HTTP 不通：").append(friendly(e)).append('\n');
            }

            final CountDownLatch ready = new CountDownLatch(1);
            c.open(
                "$events",
                new JSONObject(),
                new DshClient.Stream() {
                  @Override
                  public void onItem(JSONObject value) {
                    if ("ready".equals(value.optString("type", ""))) ready.countDown();
                  }

                  @Override
                  public void onEnd() {}
                });
            try {
              sb.append(
                  ready.await(8, TimeUnit.SECONDS)
                      ? "2. WebSocket 通了（收到 ready）\n"
                      : "2. WebSocket 没通：8 秒没等到 ready\n");
            } catch (InterruptedException e) {
              sb.append("2. WebSocket 检查被打断\n");
            }

            final JSONObject chosen = picked;
            JSONObject follow = chosen == null ? null : followArgs(chosen.optString("sessionId", ""));
            if (follow == null) {
              sb.append("3. 跳过会话流：没有可打开的会话\n");
            } else {
              final CountDownLatch snapshot = new CountDownLatch(1);
              final String[] failed = new String[1];
              c.open(
                  "session/follow",
                  follow,
                  new DshClient.Stream() {
                    @Override
                    public void onItem(JSONObject value) {
                      if ("snapshot".equals(value.optString("type", ""))) snapshot.countDown();
                    }

                    @Override
                    public void onError(String code, String message) {
                      failed[0] = code + "，" + message;
                    }

                    @Override
                    public void onEnd() {}
                  });
              try {
                sb.append(
                    snapshot.await(10, TimeUnit.SECONDS)
                        ? "3. 会话流通了（拿到 snapshot）\n"
                        : "3. 会话流没通："
                            + (failed[0] == null ? "10 秒没等到 snapshot" : failed[0])
                            + "\n");
              } catch (InterruptedException e) {
                sb.append("3. 会话流检查被打断\n");
              }
            }
            c.close();

            final String report = sb.toString();
            for (String line : report.split("\n")) Log.i(TAG, "SELFCHECK " + line);
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.check_title)
                        .setMessage(report)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(
                            R.string.check_copy,
                            new DialogInterface.OnClickListener() {
                              @Override
                              public void onClick(DialogInterface d, int which) {
                                ClipboardManager cm =
                                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("dsh-selfcheck", report));
                                toast(getString(R.string.check_copied));
                              }
                            })
                        .show();
                  }
                });
          }
        });
  }

  /** session/follow 的参数（键名错一个服务端就拒，所以集中在这儿，别散落各处）。 */
  private static JSONObject followArgs(String sessionId) {
    try {
      JSONObject address = new JSONObject();
      address.put("kind", "session");
      address.put("sessionId", sessionId);
      JSONObject request = new JSONObject();
      request.put("address", address);
      request.put("assistantStream", true);
      request.put("maxMessages", 50);
      JSONObject args = new JSONObject();
      args.put("request", request);
      return args;
    } catch (Exception e) {
      return null;
    }
  }

  // ------------------------------------------------------------------ 菜单

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.sessions, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_refresh) {
      refresh();
      return true;
    }
    if (id == R.id.action_check) {
      selfCheck();
      return true;
    }
    if (id == R.id.action_setup) {
      setup();
      return true;
    }
    if (id == R.id.action_web) {
      startActivity(new Intent(this, WebActivity.class));
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  // ------------------------------------------------------------------ 杂项

  /** 装了哪个包（真机回报问题时第一句就该答这个）。 */
  private String appVersion() {
    try {
      return getPackageManager()
          .getPackageInfo(getPackageName(), 0)
          .versionName;
    } catch (PackageManager.NameNotFoundException e) {
      return "?";
    }
  }

  private void show(String msg) {
    state.setText(msg);
    stateBox.setVisibility(View.VISIBLE);
  }

  private void toast(String msg) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
  }

  static String friendly(Exception e) {
    String m = e.getMessage();
    if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
    if (e instanceof DshClient.RpcException) return "电脑返回错误：" + m;
    return m;
  }
}