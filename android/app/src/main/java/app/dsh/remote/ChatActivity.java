package app.dsh.remote;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 会话页：一条消息流 + 一个输入框。
 *
 * <p>数据来自 {@code session/follow}：snapshot 里带着历史记录，之后每个 item 是一条新事件
 * （user/message、assistant/message、tool/call、tool/result…）。同一个 turn/step 的
 * assistant/message 会反复推送（流式），所以按 key 原地更新气泡，而不是每次追加。
 */
public class ChatActivity extends AppCompatActivity {

  public static final String EXTRA_SESSION = "session";

  private Toolbar bar;
  private ScrollView scroller;
  private LinearLayout transcript;
  private EditText input;
  private MaterialButton send;

  private DshClient client;
  private String sessionId;
  private boolean running;

  private final Map<String, TextView> slots = new HashMap<String, TextView>();
  private final Map<String, String> toolNames = new HashMap<String, String>();
  private final ExecutorService io = Executors.newSingleThreadExecutor();

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    sessionId = getIntent().getStringExtra(EXTRA_SESSION);
    if (sessionId == null || sessionId.isEmpty()) {
      toast("没有会话 id");
      finish();
      return;
    }
    setContentView(R.layout.activity_chat);

    bar = findViewById(R.id.bar);
    setSupportActionBar(bar);
    bar.setTitle(tail(sessionId));
    bar.setSubtitle(R.string.st_connecting);
    if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    Ui.edgeToEdge(this, findViewById(R.id.root), bar);

    scroller = findViewById(R.id.scroller);
    transcript = findViewById(R.id.transcript);
    input = findViewById(R.id.input);
    send = findViewById(R.id.send);
    send.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            if (running) cancelTurn();
            else send();
          }
        });

    connect();
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (client != null) {
      client.close();
      client = null;
    }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  // ------------------------------------------------------------------ 连接

  private void connect() {
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
                  }
                });
          }
        });

    JSONObject request =
        obj(
            "address", obj("kind", "session", "sessionId", sessionId),
            "assistantStream", Boolean.TRUE,
            "maxMessages", Integer.valueOf(200));
    client.open(
        "session/follow",
        obj("request", request),
        new DshClient.Stream() {
          @Override
          public void onItem(final JSONObject value) {
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    render(value);
                  }
                });
          }

          @Override
          public void onEnd() {}

          @Override
          public void onError(final String code, final String message) {
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    setRunning(false);
                    bar.setSubtitle(R.string.st_failed);
                    slot("e:stream", R.color.bubble_error, false)
                        .setText("打不开这个会话：" + human(code, message));
                    scrollToEnd();
                  }
                });
          }
        });
  }

  // ------------------------------------------------------------------ 渲染

  private void render(JSONObject value) {
    String type = value.optString("type", "");
    if ("snapshot".equals(type)) {
      JSONObject projections = value.optJSONObject("projections");
      if (projections != null) {
        JSONObject values = projections.optJSONObject("values");
        if (values != null && !values.optString("title", "").isEmpty()) {
          bar.setTitle(values.optString("title"));
        }
      }
      JSONArray records = value.optJSONArray("records");
      for (int i = 0; records != null && i < records.length(); i++) {
        JSONObject record = records.optJSONObject(i);
        if (record == null) continue;
        JSONObject event = record.optJSONObject("event");
        if (event != null) handle(event);
      }
      scrollToEnd();
      return;
    }
    if ("event".equals(type)) {
      JSONObject event = value.optJSONObject("event");
      if (event != null) {
        handle(event);
        scrollToEnd();
      }
      return;
    }
    if ("projections".equals(type)) {
      JSONObject values = value.optJSONObject("values");
      if (values != null && !values.optString("title", "").isEmpty()) {
        bar.setTitle(values.optString("title"));
      }
    }
  }

  private void handle(JSONObject event) {
    String type = event.optString("type", "");
    JSONObject data = event.optJSONObject("data");
    if (data == null) return;

    if ("user/message".equals(type)) {
      String key = "u:" + data.optString("id", String.valueOf(event.optInt("seq", 0)));
      String text = text(data.optJSONArray("content"));
      if (!text.isEmpty()) slot(key, R.color.bubble_user, false).setText(text);
      return;
    }

    if ("assistant/message".equals(type)) {
      String key = "a:" + data.optInt("turn", 0) + ":" + data.optInt("step", 0);
      JSONObject message = data.optJSONObject("message");
      String text = message == null ? "" : text(message.optJSONArray("content"));
      if (text.isEmpty()) {
        // 只有工具调用或思考的那几帧：不占气泡，但要让状态栏知道还在跑
        if (!data.optBoolean("stream", false)) setRunning(false);
        return;
      }
      slot(key, R.color.bubble_agent, false).setText(text);
      if (!data.optBoolean("stream", false)) setRunning(false);
      return;
    }

    if ("tool/call".equals(type)) {
      String callId = data.optString("callId", "");
      String name = data.optString("name", "工具");
      toolNames.put(callId, name);
      slot("c:" + callId, R.color.bubble_tool, true).setText("🔧 " + name);
      return;
    }

    if ("tool/result".equals(type)) {
      JSONObject message = data.optJSONObject("message");
      String callId = message == null ? "" : message.optString("toolCallId", "");
      String name = toolNames.get(callId);
      if (name == null) name = "工具";
      boolean failed = message != null && message.optBoolean("isError", false);
      String text = message == null ? "" : text(message.optJSONArray("content"));
      String body = "↳ " + name + (failed ? "（失败）" : "") + "\n" + text;
      slot("r:" + callId, failed ? R.color.bubble_error : R.color.bubble_tool, true).setText(body);
    }
  }

  /** 取一条气泡；同一个 key 再来就原地更新，不新增。 */
  private TextView slot(String key, int colorRes, boolean monospace) {
    TextView tv = slots.get(key);
    if (tv == null) {
      tv = new TextView(this);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.setMargins(dp(12), dp(6), dp(12), 0);
      tv.setLayoutParams(lp);
      tv.setPadding(dp(12), dp(10), dp(12), dp(10));
      tv.setTextSize(15f);
      tv.setTextIsSelectable(true);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(ContextCompat.getColor(this, colorRes));
      bg.setCornerRadius(dp(12));
      tv.setBackground(bg);
      if (monospace) tv.setTypeface(Typeface.MONOSPACE);
      transcript.addView(tv);
      slots.put(key, tv);
    }
    return tv;
  }

  private void scrollToEnd() {
    scroller.post(
        new Runnable() {
          @Override
          public void run() {
            scroller.fullScroll(View.FOCUS_DOWN);
          }
        });
  }

  private void setRunning(boolean value) {
    running = value;
    send.setText(value ? "停止" : "发送");
    send.setEnabled(client != null);
  }

  // ------------------------------------------------------------------ 发送

  private void send() {
    final String text = input.getText().toString().trim();
    if (text.isEmpty()) return;
    final DshClient c = client;
    if (c == null) {
      toast("还没连上电脑");
      return;
    }
    input.setText("");
    setRunning(true);
    io.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              JSONObject request =
                  obj(
                      "requestId", UUID.randomUUID().toString(),
                      "sessionId", sessionId,
                      "mode", "queue",
                      "content", parts(text));
              c.call("session/prompt", obj("request", request));
            } catch (final Exception e) {
              runOnUiThread(
                  new Runnable() {
                    @Override
                    public void run() {
                      setRunning(false);
                      toast(MainActivity.friendly(e));
                    }
                  });
            }
          }
        });
  }

  private void cancelTurn() {
    final DshClient c = client;
    if (c == null) return;
    setRunning(false);
    io.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              c.call("session/cancel", obj("request", obj("sessionId", sessionId)));
            } catch (final Exception e) {
              runOnUiThread(
                  new Runnable() {
                    @Override
                    public void run() {
                      toast(MainActivity.friendly(e));
                    }
                  });
            }
          }
        });
  }

  // ------------------------------------------------------------------ 杂项

  private static JSONArray parts(String text) {
    JSONArray content = new JSONArray();
    content.put(obj("type", "text", "text", text));
    return content;
  }

  private static String text(JSONArray content) {
    if (content == null) return "";
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < content.length(); i++) {
      JSONObject part = content.optJSONObject(i);
      if (part == null) continue;
      if (!"text".equals(part.optString("type", ""))) continue; // 思考过程不展示
      String t = part.optString("text", "");
      if (b.length() > 0) b.append('\n');
      b.append(t);
    }
    return b.toString();
  }

  /** 小工具：省掉满屏的 try/catch JSONException。 */
  private static JSONObject obj(Object... kv) {
    JSONObject o = new JSONObject();
    try {
      for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return o;
  }

  private static String tail(String id) {
    int i = id.lastIndexOf('-');
    return i >= 0 ? "会话 · " + id.substring(i + 1) : "会话";
  }

  /** 把服务端的流错误翻译成人能看的一句话。 */
  private static String human(String code, String message) {
    if ("session/agent-busy".equals(code)) return "这是子代理会话，得从父会话里打开";
    if (message == null || message.isEmpty()) return code;
    return message + "（" + code + "）";
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private void toast(String msg) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
  }
}