# 手机适配层（桥接注入）

`tools/dsh-lan-bridge.cjs` 把 DSH 页面交给手机时，往 `<head>` 里插这五个文件和一个探针。
改这些文件（除了新增文件本身要重启一次桥接）**不用重启 DSH、也不用重装 App**：手机刷新一次页面就生效；改 `dsh-lan-bridge.cjs` 本身才需要重启桥接。

| 文件 | 作用 |
| --- | --- |
| `mobile.css` | 手机宽度（≤900px）下的排版修正：鲸鱼挂件让位与文字不溢出、设置弹窗改成纵向、插件市场标题与页签条不再出框、输入框底部状态胶囊不再被 ellipsis 吃掉（`min-width:max-content` + 换行）、组合器尾部控制允许收缩（`flex-shrink:1`，否则模型芯片把发送按钮挤出屏幕、整个对话能在 `.Dc7zOa_scrollBody` 里左右拖）、侧栏展开时改成浮层遮盖对话（767px 以下）、锁死双指缩放（`html,body{touch-action:pan-x pan-y}`，去掉 `pinch-zoom`；实测手机上仍然拦不住 WebView 原生层的捏合，所以 v0.13 起 App 侧也加了 `setSupportZoom(false)`，这条留作非 WebView 浏览器的兜底）、组合器弹出层（等级/模型面板与模型列表）右移进聊天滚动口并把列表高度放开 |
| `keyboard.js` | 两件事。①上报真实软键盘状态（当前视口高度 vs 见过的最大高度），给 `<html>` 打 `data-dsh-kb`；`mobile.css` 据此在打字时藏起挂件。**不能用 `:focus` 代替**：Android 收起键盘后输入框仍然聚焦，那样挂件会永远回不来。②掐掉客户端自己的输入框自动聚焦：dsh-client 的 `focusDraftEditor()` 会在首屏和每次会话切换时聚焦 `[data-composer-input]`，Android 上就是弹 IME——点一下侧栏里的历史会话键盘就盖住会话。规则是「`focusin` 前 800 ms 内没有同一个输入框上的 `pointerdown` 就 `blur()`」，所以自己点输入框照常弹键盘 |
| `owns-host.js` | 让非回环页面（`192.168.x.x`）上 DSH 把 Host 认成自己。否则设置/模型页只显示 `加载提供商目录失败: settings are unavailable in this browser`。代价是远程页面获得与回环页面同等的 Host 设置读写权——同一个 `?k=` 链接本来就等于远程执行；删掉本文件即回退（桥接会跳过缺失的标签） |
| `widget-idle.js` | 触屏上没有 hover，挂件自己会把 ☰（`dshwv-menu-btn`，三横）钉成常显，一直压在会话上。这个脚本在「几秒没碰挂件」时给 `<html>` 打 `data-dsh-widget-idle`，`mobile.css` 据此把它淡出并停掉点击；碰一下鲸鱼立刻回来。只有落在 `.dshwv-root` 里的触摸才算「碰」——翻页滚动不算，否则按钮等于没藏 |
| `probe.js` | 真机布局几何上报到 `tools/layout.json`，只用于取证，不参与样式。其中 `zoom`（`visualViewport.scale`、meta viewport、页面能否横向滚）与 `hscroll`（能左右拖动的容器 + 未被裁剪祖先遮住的越界子元素）专治「手机说能左右拖/能缩放」这类误报 |
| `notify.js` | 消息通知（v0.10）：页面里自订 DSH `$events` 流，任务完成 / 待确认 / 提问 → `AndroidBridge.notify(...)` 系统通知（正文带会话标题）；对 waterfall 事件静默旁观，不回 `$events/result` |

`.dshwv-*` 是 `dsh-whale-widget`（`%USERPROFILE%\.dsh\profiles\desktop\node_modules\dsh-whale-widget\assets\whale-widget.js`）的类名；
`mobile.css` 里每条规则上面都写了它挡住的实测数字，改之前先读那段注释。

## 运行方式

`tools\bridge-on.cmd` 注册计划任务 **`DSH LAN Bridge`**（登录触发 + 每 5 分钟自检），由 `tools\bridge-start.vbs`
**隐藏窗口**启动，所以「手机能连」是常态、没有窗口可以误关；`tools\bridge-off.cmd` 停用并杀掉进程。
给使用者的完整说明见仓库根目录 `操作手册.md`，给开发者的见 `交接文件.md`。

## 验证

```powershell
node tools\verify-bridge.cjs            # 注入层剥离后应与回环页面逐字节一致
node tools\phone-shot.cjs tmp\shot.png --url "http://10.190.24.150:3080/?k=<key>" --dump whale     # whale | ellipse | overflow | settings
```
