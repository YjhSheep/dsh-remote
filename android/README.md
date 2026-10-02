# DSH 遥控（Android）

一个极薄的 WebView 壳：手机上打开电脑里的 DSH，并把访问密钥存在 App 里、地址栏省掉。
包里**只有这一个界面**（v0.7 起）：打开 App 直接显示 DSH 的完整网页界面；原先 App 自绘的
会话列表/聊天页（原生界面）已**在 v0.7 整块删除**，不再有任何原生 UI。

## 为什么中间要有一条本机隧道

App 不在 WebView 里直接开 `http://192.168.31.216:3080`，而是先在手机内部起一条裸 TCP 隧道
`localhost:17800 → 电脑:3080`，WebView 只访问 `http://localhost:17800/`。这样做的实际收益：

- DSH 自己用 `location.hostname` 判断「是不是本机页面」（回环 = 桌面端的处境）。隧道让这个判断
  天然为真，所以**设置页 / 模型页不需要 `tools/bridge/owns-host.js` 那种信任注入**也能工作。
- **回环地址同时是浏览器眼里的「安全上下文」**：DSH 前端要用 `crypto.randomUUID`，而它只在
  `http://127.0.0.1:*` / `http://localhost:*` 这类 potentially-trustworthy 的 origin 上存在。
  所以 WebView 不能反过来直接开公网隧道地址 —— 那样连客户端连接对象都构造不出来。
- 转发的是字节，不解析 HTTP：文档、`/api`、SSE（`/plugins/events`）、WebSocket 升级都走同一条路。
- 密钥留在 App 的偏好设置里，不躺在浏览器地址栏和网页 URL 里。

**为什么是 `localhost` 而不是 `127.0.0.1`**（v0.5 起）：隧道是裸 TCP 转发，会把 WebView 的请求头
原样发给上游；如果 WebView 开在 `127.0.0.1`，公网的明文链路上就会出现 `Host: 127.0.0.1:<端口>`，
而某些中间盒见到这个字面量会直接回 RST —— 继电器一个字节都收不到，WebView 报
`net::ERR_EMPTY_RESPONSE`（同一个隧道地址用浏览器开却是正常的，因为它发的是服务器地址）。
`localhost` 同样是回环、同样过 DSH 的本机判断，但网线上不再出现那个字面量。

首次导航带 `?k=密钥`：桥接会回 303 并把 `dsh-bridge=密钥` 的 cookie 种在 `localhost` 上，
之后所有请求（文档、`/api`、SSE、WS）都在闸门内。

## 一次性准备

```powershell
powershell -ExecutionPolicy Bypass -File .\bootstrap-sdk.ps1   # 约 121 MB：build-tools 34.0.0 + platform 34
```

（本机默认禁止运行 .ps1，所以要用 `-ExecutionPolicy Bypass -File` 调；直接 `.\bootstrap-sdk.ps1`
会报「在此系统上禁止运行脚本」。）

装到 `.\android-sdk`（可用 `$env:DSH_ANDROID_SDK` 改），**这里只负责下载 SDK 包**（build-tools 34.0.0 +
platform 34）；真正构建用的是 Gradle 8.9 + `android\tools\jdk17`，由 `gradle-build.ps1` 驱动 —— 不用
Android Studio、不用 sdkmanager。早先那套「JDK 11 + SDK 自带 aapt2/d8/zipalign/apksigner」的手写
工具链已随 v0.7 删除原生界面一起删掉。

## 构建

```powershell
# Gradle 8.9 + android\tools\jdk17，唯一构建脚本；默认任务 assembleDebug
powershell -ExecutionPolicy Bypass -File .\gradle-build.ps1
# 产物 app\build\outputs\apk\debug\app-debug.apk → 拷成 dist\dsh-remote.apk（桥接分发的是它）
Copy-Item .\app\build\outputs\apk\debug\app-debug.apk .\dist\dsh-remote.apk -Force
```

换图标：把美术图放进 `icon/`（现在的 `icon/1.1.ico` 就是当前用的那张），跑
`python make-icon.py` 重生成 `app/src/main/res/mipmap-*/ic_launcher.png` 与自适应图标的前景 `ic_fg.png`，
再重新构建。自适应图标的底色是 `app/src/main/res/values/colors.xml` 里的 `ic_bg`（取自原图的背景色，
所以原图的圆角不会在启动器里露出方边）。

## 自测隧道（可选）

`Tunnel` 只用了 JDK 自带的类，所以不用手机、不用模拟器，在电脑上就能连**真桥接**验证：

```powershell
javac -encoding UTF-8 -d .\build\tunneltest app\src\main\java\app\dsh\remote\Tunnel.java test\TunnelTest.java
java -cp .\build\tunneltest app.dsh.remote.TunnelTest 3080 <密钥>
```

六项断言：绑到 17800 / 端口被占时顺延到 17801 / `?k=` 换到 303 与 cookie / 带 cookie 取到页面 /
正文完整 / 上游不通时回 502 页面。

## 装到手机

桥接会把构建好的 APK 直接发给手机（`tools/dsh-lan-bridge.cjs` 里的 `/__bridge/app.apk` 路由）：

1. 手机上用**浏览器**打开 `http://<电脑IP>:3080/__bridge/app.apk?k=<密钥>` → 下载 → 安装
   （需要允许「安装未知应用」）。用浏览器而不是 App，是因为浏览器那一步会带上 `?k=` 换来的 cookie。
2. 或者走 adb（这台手机走 WiFi 调试，USB 口是空的）：`adb connect 192.168.31.168:5555` 之后
   `adb -s 192.168.31.168:5555 install -r .\dist\dsh-remote.apk`；**先把 `$env:TEMP` 指到工作区内**，
   否则 adb 报 `cannot open C:\Temp\adb.log: Permission denied`。

## 使用

打开 App → 首次会让你填地址：

- 地址：`192.168.31.216:3080`（桥接启动时打印的那个 `http://192.168.x.x:3080/?k=...` 整条粘进来也行，
  密钥会自动从 `k=` 里取出来）
- 密钥：链接里已含 `k=` 时可留空

右上角菜单：**设置**（改地址/密钥，随时换电脑）、**重新加载**。

界面：顶部蓝色标题栏右边那行小字就是连接状态（`未连接` / `连接中…` / `已连接 · 192.168.31.216:3080` /
`连不上`），标题栏下面那条细线是加载进度。连不上时页面会换成一块说明板（目标地址 + 失败原因 +
系统 WebView 版本），带 **重试** 与 **设置** 两个按钮 —— 把里面的灰字截图发出来就能定位问题。

设置窗口里有 **测试连接**：不退出就能知道这个地址通不通，结果就地显示在下面（绿色=能连上，红色=连不上）。
配色跟随系统深浅色：系统开深色时，App 外壳与 DSH 页面都会变深。

## 目录

```
app/AndroidManifest.xml          权限、Activity、软键盘 adjustResize、启动器图标
app/res/                         strings / styles / layout / menu / mipmap（图标）
app/java/app/dsh/remote/Tunnel.java        127.0.0.1 裸 TCP 转发
app/java/app/dsh/remote/WebActivity.java   网页壳：顶栏/状态 + WebView + 设置 + 文件选择
icon/1.1.ico                     图标原图（make-icon.py 的输入）
make-icon.py                     原图 → mipmap 各密度 + 自适应图标前景
bootstrap-sdk.ps1 / gradle-build.ps1  下载 SDK / Gradle 构建
dist/dsh-remote.apk              产物（桥接的 /__bridge/app.apk 就是发它）
```

## 调试

`Tunnel` 与页面出问题时，App 里的 WebView 已开启远程调试：电脑上打开
`chrome://inspect/#devices`（先用 `adb devices` 确认手机连着），可以直接对手机里的页面开 DevTools、
量真实布局 —— 比反复截图猜要快。

`adb logcat -s dsh-remote` 能看 App 自己的日志（`WebActivity` 打印加载 URL 与转发目标）。
