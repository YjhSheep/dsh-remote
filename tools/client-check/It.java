import app.dsh.remote.DshClient;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在电脑上直接跑 App 的 DshClient（它没有任何 android.* 依赖），对着真实的桥接验证协议：
 *  - phase A：用裸 WebSocket 打印 /api/remote.mux 的每一帧，确认真实帧形状
 *  - phase B：用 DshClient 走 session/list、$events、session/follow
 *  - phase C：用不存在的 sessionId 探 prompt/control 的参数形状（无副作用）
 *
 * 用法（在电脑上编译并运行本文件，对着真实桥接验一遍协议）：
 *   powershell -NoProfile -ExecutionPolicy Bypass -File tools\client-check.ps1 [host] [port] [key]
 * 手动跑：java -cp ... It &lt;host&gt; &lt;port&gt; &lt;gateKey&gt;
 */
public class It {

  static String HOST = "192.168.31.216";
  static int PORT = 3080;
  static String KEY = "";

  public static void main(String[] args) throws Exception {
    // 控制台默认 GBK，中文会乱码——这里强制 UTF-8，确认服务端的中文标题是完整的
    System.setOut(
        new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
    if (args.length > 0) HOST = args[0];
    if (args.length > 1) PORT = Integer.parseInt(args[1]);
    if (args.length > 2) KEY = args[2];

    System.out.println("== target " + HOST + ":" + PORT + " keylen=" + KEY.length());

    // ---------------------------------------------------------------- phase B1
    DshClient client = new DshClient(HOST, PORT, KEY, "http");
    client.setStateListener(
        new DshClient.State() {
          @Override
          public void onState(boolean online, String reason) {
            System.out.println("B state online=" + online + " reason=" + reason);
          }
        });

    JSONObject listArgs = new JSONObject();
    listArgs.put("_request", new JSONObject());
    JSONObject res = client.call("session/list", listArgs);
    JSONArray items = res.optJSONArray("items");
    int n = items == null ? 0 : items.length();
    System.out.println("B session/list -> items=" + n);
    String sessionId = null;
    String subId = null;
    int subagents = 0;
    JSONObject first = null;
    for (int i = 0; items != null && i < items.length(); i++) {
      JSONObject it = items.optJSONObject(i);
      if (it == null) continue;
      boolean sub = "subagent".equals(it.optString("origin", ""));
      if (sub) {
        subagents++;
        if (subId == null) subId = it.optString("sessionId");
        continue;
      }
      if (sessionId == null) {
        sessionId = it.optString("sessionId");
        first = it;
      }
    }
    System.out.println("B subagent sessions=" + subagents + "/" + n + " pick=" + sessionId);
    if (first != null) {
      System.out.println("B pick keys=" + keys(first));
      System.out.println("B pick running=" + first.opt("running") + " updatedAt=" + first.opt("updatedAt") + " cwd=" + first.opt("cwd"));
      JSONObject p = first.optJSONObject("projections");
      JSONObject v = p == null ? null : p.optJSONObject("values");
      System.out.println("B pick title=" + (v == null ? "<no projections>" : v.optString("title")));
    }
    if (subId != null) {
      System.out.println("B subagent sample=" + subId);
      client.open(
          "session/follow",
          new JSONObject(
              "{\"request\":{\"address\":{\"kind\":\"session\",\"sessionId\":\"" + subId + "\"}}}"),
          new DshClient.Stream() {
            @Override
            public void onItem(JSONObject value) {
              System.out.println("B subagent onItem " + value.optString("type"));
            }

            @Override
            public void onEnd() {}

            @Override
            public void onError(String code, String message) {
              System.out.println("B subagent onError code=" + code + " msg=" + message);
            }
          });
      Thread.sleep(2500);
    }

    // ---------------------------------------------------------------- phase B2: $events
    final AtomicInteger events = new AtomicInteger();
    final StringBuilder evTypes = new StringBuilder();
    client.open(
        "$events",
        new JSONObject(),
        new DshClient.Stream() {
          @Override
          public void onItem(JSONObject value) {
            events.incrementAndGet();
            evTypes.append(value.optString("type", "?")).append(' ');
          }

          @Override
          public void onEnd() {
            System.out.println("B $events ended");
          }
        });
    Thread.sleep(3000);
    System.out.println("B $events via DshClient -> items=" + events.get() + " types=[" + evTypes.toString().trim() + "]");

    // ---------------------------------------------------------------- phase A: raw frames
    raw("$events", "{}", 5);
    if (sessionId != null) {
      raw(
          "session/follow",
          "{\"request\":{\"address\":{\"kind\":\"session\",\"sessionId\":\""
              + sessionId
              + "\"},\"assistantStream\":true,\"maxMessages\":20}}",
          6);
    }

    // ---------------------------------------------------------------- phase B3: session/follow via DshClient
    if (sessionId != null) {
      final AtomicInteger got = new AtomicInteger();
      final StringBuilder types = new StringBuilder();
      client.open(
          "session/follow",
          new JSONObject(
              "{\"request\":{\"address\":{\"kind\":\"session\",\"sessionId\":\""
                  + sessionId
                  + "\"},\"assistantStream\":true,\"maxMessages\":20}}"),
          new DshClient.Stream() {
            @Override
            public void onItem(JSONObject value) {
              got.incrementAndGet();
              types.append(value.optString("type", "?")).append(' ');
            }

            @Override
            public void onEnd() {
              System.out.println("B follow ended");
            }
          });
      Thread.sleep(5000);
      System.out.println("B session/follow via DshClient -> items=" + got.get() + " types=[" + types.toString().trim() + "]");
    }

    // ---------------------------------------------------------------- phase C: 参数形状（无副作用）
    probe(client, "session/control", "{\"request\":{\"sessionId\":\"" + BOGUS + "\"}}");
    probe(
        client,
        "session/prompt",
        "{\"request\":{\"requestId\":\""
            + BOGUS
            + "\",\"sessionId\":\""
            + BOGUS
            + "\",\"mode\":\"queue\",\"content\":[{\"type\":\"text\",\"text\":\"shape-probe\"}]}}");
    probe(
        client,
        "session/follow",
        "{\"request\":{\"address\":{\"kind\":\"session\",\"sessionId\":\"" + BOGUS + "\"}}}");
    probe(client, "session/cancel", "{\"request\":{\"sessionId\":\"" + BOGUS + "\"}}");
    probe(client, "session/create", "{\"request\":{\"workspaceId\":\"__no_such_workspace__\"}}");
    probe(client, "job/list", "{\"request\":{\"sessionId\":\"" + BOGUS + "\"}}");

    client.close();
    System.out.println("== done");
  }

  static final String BOGUS = "00000000-0000-0000-0000-000000000000";

  static void probe(DshClient c, String method, String argsJson) {
    try {
      JSONObject r = c.call(method, new JSONObject(argsJson));
      System.out.println("C " + method + " UNEXPECTED ok -> " + cut(r.toString()));
    } catch (DshClient.RpcException e) {
      System.out.println("C " + method + " rpc-error code=" + e.code + " msg=" + cut(e.getMessage()));
    } catch (Exception e) {
      System.out.println("C " + method + " " + e.getClass().getSimpleName() + ": " + cut(e.getMessage()));
    }
  }

  static void raw(final String endpoint, final String argsJson, int seconds) throws Exception {
    final OkHttpClient c =
        new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
    final CountDownLatch opened = new CountDownLatch(1);
    final String sid = UUID.randomUUID().toString();
    Request r =
        new Request.Builder()
            .url("ws://" + HOST + ":" + PORT + "/api/remote.mux")
            .header("Cookie", "dsh-bridge=" + KEY)
            .build();
    WebSocket ws =
        c.newWebSocket(
            r,
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket s, Response response) {
                System.out.println("A ws open http=" + response.code());
                s.send(
                    "{\"type\":\"open\",\"streamId\":\""
                        + sid
                        + "\",\"endpoint\":\""
                        + endpoint
                        + "\",\"payload\":{\"args\":"
                        + argsJson
                        + "}}");
                opened.countDown();
              }

              @Override
              public void onMessage(WebSocket s, String text) {
                System.out.println("A << " + cut(text));
              }

              @Override
              public void onFailure(WebSocket s, Throwable t, Response response) {
                System.out.println("A !! " + t);
                opened.countDown();
              }

              @Override
              public void onClosed(WebSocket s, int code, String reason) {
                System.out.println("A ws closed " + code + " " + reason);
              }
            });
    opened.await(10, TimeUnit.SECONDS);
    Thread.sleep(seconds * 1000L);
    ws.close(1000, "done");
    c.dispatcher().executorService().shutdown();
  }

  static String keys(JSONObject o) {
    if (o == null) return "<null>";
    StringBuilder b = new StringBuilder();
    java.util.Iterator<String> it = o.keys();
    while (it.hasNext()) b.append(it.next()).append(' ');
    return b.toString().trim();
  }

  static String cut(String s) {
    if (s == null) return "";
    String t = s.replace('\n', ' ');
    return t.length() > 420 ? t.substring(0, 420) + "…" : t;
  }
}