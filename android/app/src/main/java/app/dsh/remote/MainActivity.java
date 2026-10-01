package app.dsh.remote;

import android.content.Intent;
import android.os.Bundle;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生首页：列出电脑上的会话，点进去聊天。
 *
 * <p>说 DSH 自己的 HTTP 协议（见 {@link DshClient}），不依赖 WebView；旧的网页壳保留在
 * {@link WebActivity} 里当退路。
 */
public class MainActivity extends AppCompatActivity {

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
    if (!Prefs.ready(this)) {
      setup();
      return;
    }
    connect();
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
                      if (adapter.count() == 0) {
                        show("电脑上还没有会话，点右下角新建一个");
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