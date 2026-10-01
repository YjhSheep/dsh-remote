package app.dsh.remote;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 电脑上的会话列表。标题来自 session/list 的 projections.values.title。 */
public final class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.Holder> {

  public interface OnPick {
    void onPick(JSONObject session);
  }

  private static final class Row {
    final JSONObject raw;
    final String title;
    final String sub;
    final boolean running;

    Row(JSONObject raw, String title, String sub, boolean running) {
      this.raw = raw;
      this.title = title;
      this.sub = sub;
      this.running = running;
    }
  }

  private final List<Row> rows = new ArrayList<Row>();
  private final OnPick onPick;
  private final SimpleDateFormat stamp = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

  public SessionAdapter(OnPick onPick) {
    this.onPick = onPick;
  }

  public void setSessions(JSONArray items) {
    rows.clear();
    for (int i = 0; items != null && i < items.length(); i++) {
      JSONObject o = items.optJSONObject(i);
      if (o == null) continue;
      JSONObject values = null;
      JSONObject projections = o.optJSONObject("projections");
      if (projections != null) values = projections.optJSONObject("values");
      // 真机上实测：子代理会话的 title 是 JSON null，org.json 的 optString 会把它变成字符串
      // "null"（fallback 只管字段缺失），不判掉列表里就会显示字面量 null。
      String title = values == null ? "" : values.optString("title", "").trim();
      if (title.isEmpty() || "null".equals(title)) {
        title = "未命名会话 · " + tail(o.optString("sessionId", ""));
      }
      StringBuilder sub = new StringBuilder();
      boolean running = o.optBoolean("running", false);
      // 子代理会话也在这个列表里（实测 origin=subagent），标出来免得和主会话混在一起。
      if ("subagent".equals(o.optString("origin", ""))) sub.append("子代理");
      if (running) {
        if (sub.length() > 0) sub.append(" · ");
        sub.append("● 运行中");
      }
      String cwd = o.optString("cwd", "");
      if (!cwd.isEmpty()) {
        if (sub.length() > 0) sub.append(" · ");
        sub.append(basename(cwd));
      }
      Object updated = o.opt("updatedAt");
      if (updated instanceof Number) {
        if (sub.length() > 0) sub.append(" · ");
        sub.append(stamp.format(new Date(((Number) updated).longValue())));
      }
      if (o.optBoolean("blank", false)) {
        if (sub.length() > 0) sub.append(" · ");
        sub.append("空会话");
      }
      rows.add(new Row(o, title, sub.toString(), running));
    }
    notifyDataSetChanged();
  }

  public int count() {
    return rows.size();
  }

  /** 真机验收时把渲染的内容回传到 logcat（uiautomator 在 MIUI 上取不到界面文本）。 */
  public String titleAt(int i) {
    return i >= 0 && i < rows.size() ? rows.get(i).title : "";
  }

  private static String basename(String path) {
    String p = path.replace('\\', '/');
    int i = p.lastIndexOf('/');
    return i >= 0 && i < p.length() - 1 ? p.substring(i + 1) : p;
  }

  private static String tail(String sessionId) {
    int i = sessionId.lastIndexOf('-');
    return i >= 0 ? sessionId.substring(i + 1) : sessionId;
  }

  @NonNull
  @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v =
        LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false);
    return new Holder(v);
  }

  @Override
  public void onBindViewHolder(@NonNull Holder h, int position) {
    final Row row = rows.get(position);
    h.title.setText(row.title);
    h.sub.setText(row.sub);
    h.sub.setVisibility(row.sub.isEmpty() ? View.GONE : View.VISIBLE);
    h.title.setTextColor(row.running ? h.okColor : h.defaultColor);
    h.itemView.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            onPick.onPick(row.raw);
          }
        });
  }

  @Override
  public int getItemCount() {
    return rows.size();
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final TextView title;
    final TextView sub;
    final int defaultColor;
    final int okColor;

    Holder(View v) {
      super(v);
      title = v.findViewById(R.id.title);
      sub = v.findViewById(R.id.sub);
      defaultColor = title.getCurrentTextColor();
      okColor = v.getContext().getColor(R.color.ok);
    }
  }
}