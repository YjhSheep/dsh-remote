# 手机适配层（桥接注入）

`tools/dsh-lan-bridge.cjs` 把 DSH 页面交给手机时，往 `<head>` 里插这三个文件和一个探针。
改这四个文件**不用重启 DSH、也不用重装 App**：手机刷新一次页面就生效；改 `dsh-lan-bridge.cjs` 本身才需要重启桥接。

| 文件 | 作用 |
| --- | --- |
| `mobile.css` | 手机宽度（≤900px）下的排版修正：鲸鱼挂件让位与文字不溢出、设置弹窗改成纵向、插件市场标题与页签条不再出框 |
| `keyboard.js` | 上报真实软键盘状态（当前视口高度 vs 见过的最大高度），给 `<html>` 打 `data-dsh-kb`；`mobile.css` 据此在打字时藏起挂件。**不能用 `:focus` 代替**：Android 收起键盘后输入框仍然聚焦，那样挂件会永远回不来 |
| `owns-host.js` | 让非回环页面（`192.168.x.x`）上 DSH 把 Host 认成自己。否则设置/模型页只显示 `加载提供商目录失败: settings are unavailable in this browser`。代价是远程页面获得与回环页面同等的 Host 设置读写权——同一个 `?k=` 链接本来就等于远程执行；删掉本文件即回退（桥接会跳过缺失的标签） |
| `probe.js` | 真机布局几何上报到 `tools/layout.json`，只用于取证，不参与样式 |

`.dshwv-*` 是 `dsh-whale-widget`（`%USERPROFILE%\.dsh\profiles\desktop\node_modules\dsh-whale-widget\assets\whale-widget.js`）的类名；
`mobile.css` 里每条规则上面都写了它挡住的实测数字，改之前先读那段注释。

## 运行方式

`tools\bridge-on.cmd` 注册计划任务 **`DSH LAN Bridge`**（登录触发 + 每 5 分钟自检），由 `tools\bridge-start.vbs`
**隐藏窗口**启动，所以「手机能连」是常态、没有窗口可以误关；`tools\bridge-off.cmd` 停用并杀掉进程。
给使用者的完整说明见仓库根目录 `操作手册.md`，给开发者的见 `交接文件.md`。

## 验证

```powershell
node tools\verify-bridge.cjs            # 注入层剥离后应与回环页面逐字节一致
node tools\phone-shot.cjs tmp\shot.png --url "http://192.168.31.216:3080/?k=<key>" --dump whale     # whale | ellipse | overflow | settings
```
