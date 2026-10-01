package app.dsh.remote;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 直接说 DSH 的 HTTP 协议：一元 RPC 走 {@code POST /api/&lt;namespace&gt;/&lt;method&gt;}，
 * 流式数据走 {@code /api/remote.mux} 的 WebSocket。
 *
 * <p>经由电脑上的桥接时，桥接会把 Host 改写成 127.0.0.1:19387 并注入 DSH 自己的鉴权 cookie，
 * 所以这里只需要带桥接的闸门 cookie；<b>URL 里绝不能出现 {@code k=}</b>——闸门会把带 k 的请求
 * 303 成只带 cookie 的跳转，真正的 /api 请求永远拿不到。
 *
 * <p>回调都在后台线程上触发，界面线程的更新由调用方自己 post。
 */
public final class DshClient {

  public static final String MUX_PATH = "/api/remote.mux";

  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  /** 一路流（session/follow、$events、workspace/follow…）的回调。 */
  public interface Stream {
    void onItem(JSONObject value);

    void onEnd();
  }

  /** 连接状态变化。 */
  public interface State {
    void onState(boolean online, String reason);
  }

  /** 服务端返回 result.ok === false 时抛出的错误。 */
  public static final class RpcException extends Exception {
    public final String code;

    RpcException(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  private static final class Sub {
    final String endpoint;
    final String argsJson;
    final Stream stream;

    Sub(String endpoint, String argsJson, Stream stream) {
      this.endpoint = endpoint;
      this.argsJson = argsJson;
      this.stream = stream;
    }
  }

  private final OkHttpClient rpc;
  private final OkHttpClient webs;

  private final String host;
  private final int port;
  private final String key;
  private final boolean secure;

  private final Map<String, Sub> subs = new HashMap<String, Sub>();
  private final Deque<String[]> outbox = new ArrayDeque<String[]>();
  private final Object lock = new Object();

  private WebSocket socket;
  private boolean connecting;
  private int retryMs = 1000;
  private State state;

  public DshClient(String host, int port, String key, String scheme) {
    this.host = host;
    this.port = port;
    this.key = key == null ? "" : key;
    boolean secure = "https".equals(scheme);
    this.rpc =
        new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build();
    this.webs =
        new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // mux 每 2s ping，长连接靠 ping 保活
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    this.secure = secure;
  }

  public void setStateListener(State s) {
    this.state = s;
  }

  private String authority() {
    return host + ":" + port;
  }

  private String base() {
    return (secure ? "https://" : "http://") + authority();
  }

  // ---------------------------------------------------------------- 一元 RPC

  /**
   * 调一个端点，返回 result.value。
   *
   * @param args payload.args 的内容：多数端点是 {@code {"request":{…}}}，session/list 是
   *     {@code {"_request":{…}}}
   */
  public JSONObject call(String method, JSONObject args) throws IOException, RpcException {
    JSONObject body = new JSONObject();
    try {
      body.put("type", "client-request");
      body.put("rpcId", UUID.randomUUID().toString());
      body.put("method", method);
      JSONObject payload = new JSONObject();
      payload.put("args", args == null ? new JSONObject() : args);
      body.put("payload", payload);
    } catch (JSONException e) {
      throw new IOException("请求构造失败：" + e.getMessage());
    }

    Request.Builder rb =
        new Request.Builder()
            .url(base() + "/api/" + method)
            .post(RequestBody.create(body.toString(), JSON));
    if (!key.isEmpty()) rb.header("Cookie", "dsh-bridge=" + key);

    try (Response res = rpc.newCall(rb.build()).execute()) {
      String text = res.body() == null ? "" : res.body().string();
      if (res.code() == 401 || res.code() == 403) {
        throw new IOException("密钥不对或没连上闸门（HTTP " + res.code() + "）");
      }
      JSONObject env;
      try {
        env = new JSONObject(text);
      } catch (JSONException e) {
        throw new IOException("HTTP " + res.code() + " 返回的不是 JSON：" + head(text));
      }
      JSONObject result = env.optJSONObject("result");
      if (result == null) throw new IOException("响应里没有 result：" + head(text));
      if (!result.optBoolean("ok")) {
        JSONObject err = result.optJSONObject("error");
        throw new RpcException(
            err == null ? "error" : err.optString("code", "error"),
            err == null ? head(text) : err.optString("message", head(err.toString())));
      }
      JSONObject value = result.optJSONObject("value");
      if (value == null) {
        JSONObject wrapped = new JSONObject();
        try {
          wrapped.put("value", result.opt("value"));
        } catch (JSONException ignored) {
        }
        return wrapped;
      }
      return value;
    }
  }

  // ------------------------------------------------------------------ 流

  /** 开一路流；返回本地 streamId（断线重连时由本类自行重开，调用方不必关心）。 */
  public String open(String endpoint, JSONObject args, Stream stream) {
    String streamId = UUID.randomUUID().toString();
    String frame = frame(streamId, endpoint, args);
    synchronized (lock) {
      subs.put(streamId, new Sub(endpoint, args == null ? "{}" : args.toString(), stream));
      if (socket != null) {
        socket.send(frame);
      } else {
        outbox.add(new String[] {streamId, frame});
        ensureSocketLocked();
      }
    }
    return streamId;
  }

  public void cancelStream(String streamId) {
    synchronized (lock) {
      if (subs.remove(streamId) == null) return;
      if (socket != null) {
        socket.send("{\"type\":\"cancel\",\"streamId\":\"" + streamId + "\"}");
      }
    }
  }

  /** 关掉连接与所有流（Activity 销毁时调用）。 */
  public void close() {
    WebSocket s;
    synchronized (lock) {
      subs.clear();
      outbox.clear();
      s = socket;
      socket = null;
      connecting = false;
    }
    if (s != null) s.close(1000, "bye");
    rpc.dispatcher().executorService().shutdown();
    webs.dispatcher().executorService().shutdown();
  }

  private static String frame(String streamId, String endpoint, JSONObject args) {
    try {
      JSONObject f = new JSONObject();
      f.put("type", "open");
      f.put("streamId", streamId);
      f.put("endpoint", endpoint);
      JSONObject payload = new JSONObject();
      payload.put("args", args == null ? new JSONObject() : args);
      f.put("payload", payload);
      return f.toString();
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private void ensureSocketLocked() {
    if (socket != null || connecting) return;
    connecting = true;
    String wsUrl = (secure ? "wss://" : "ws://") + authority() + MUX_PATH;
    Request.Builder rb = new Request.Builder().url(wsUrl);
    if (!key.isEmpty()) rb.header("Cookie", "dsh-bridge=" + key);
    webs.newWebSocket(rb.build(), new WebSocketListener() {
      @Override
      public void onOpen(WebSocket ws, Response response) {
        synchronized (lock) {
          socket = ws;
          connecting = false;
          retryMs = 1000;
          while (!outbox.isEmpty()) {
            String[] f = outbox.poll();
            ws.send(f[1]);
          }
        }
        notifyState(true, null);
      }

      @Override
      public void onMessage(WebSocket ws, String text) {
        dispatch(text);
      }

      @Override
      public void onFailure(WebSocket ws, Throwable t, Response response) {
        dropped(t == null ? "连接失败" : String.valueOf(t.getMessage()));
      }

      @Override
      public void onClosed(WebSocket ws, int code, String reason) {
        dropped("连接关闭（" + code + "）");
      }
    });
  }

  private void dropped(String reason) {
    List<Sub> reopen;
    synchronized (lock) {
      socket = null;
      connecting = false;
      outbox.clear();
      reopen = new ArrayList<Sub>(subs.values());
      subs.clear();
    }
    notifyState(false, reason);
    if (reopen.isEmpty()) return;
    final int delay = retryMs;
    retryMs = Math.min(retryMs * 2, 15000);
    final List<Sub> again = reopen;
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          Thread.sleep(delay);
        } catch (InterruptedException ignored) {
          return;
        }
        for (Sub s : again) {
          try {
            open(s.endpoint, new JSONObject(s.argsJson), s.stream);
          } catch (JSONException ignored) {
            // args 是我们自己序列化的，正常不会坏
          }
        }
      }
    }, "dsh-reconnect");
    t.setDaemon(true);
    t.start();
  }

  private void dispatch(String text) {
    JSONObject msg;
    try {
      msg = new JSONObject(text);
    } catch (JSONException e) {
      return;
    }
    String type = msg.optString("type", "");
    String streamId = msg.optString("streamId", "");
    if ("item".equals(type)) {
      Sub sub;
      synchronized (lock) {
        sub = subs.get(streamId);
      }
      JSONObject value = msg.optJSONObject("value");
      if (sub != null && value != null) sub.stream.onItem(value);
    } else if ("end".equals(type) || "cancel".equals(type)) {
      Sub sub;
      synchronized (lock) {
        sub = subs.remove(streamId);
      }
      if (sub != null) sub.stream.onEnd();
    }
  }

  private void notifyState(boolean online, String reason) {
    State s = state;
    if (s != null) s.onState(online, reason);
  }

  private static String head(String text) {
    if (text == null) return "";
    String t = text.replace('\n', ' ');
    return t.length() > 300 ? t.substring(0, 300) + "…" : t;
  }
}