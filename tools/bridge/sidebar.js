/* sidebar.js — 手机上从侧栏里选中东西后自动收起侧栏，让开主区域
 *
 * 为什么需要：dsh-client 的 AppFrame 在 viewport < 1024（`SIDEBAR_AUTO_COLLAPSE`，
 * @deepseek-ai/dsh-client-ui-layout）时把侧栏收成 56px 图标栏，点 ☰ 只是把
 * narrowExpanded 打开；面板再靠 mobile.css 浮在主区域上面（实测 400x700：面板 281px，
 * 盖住整块对话）。而 asar 里 `toggleSidebar` 的调用者只有 ☰ 按钮和 KeyB 快捷键——
 * **选中会话、点插件/上下文洞察/技能中心都不会复位 narrowExpanded**，于是刚打开的
 * 内容一直被面板盖着，得再点一次「收起侧边栏」。桌面宽度侧栏是真列、本来就该留着，
 * 所以这里只在窄屏动手。
 *
 * 点两类东西才收：
 *   ① 会话行（`data-row-key` 以 `session:` 开头）。**项目行不收**——实测点
 *      `.hIlkoa_projectRow` 是展开/切换该项目的会话列表（不导航），收了就没法接着选。
 *   ② 「插件 / 上下文洞察 / 技能中心」那一组面板入口（`nav[aria-label="全局面板"]` 里
 *      的三个 `_2H3hWW_panelRow` 按钮），它们会在主区域打开对应面板。
 *   设置不在里面：它是 `aria-haspopup="dialog"` 的全屏弹窗，弹窗盖住一切时收不收都看不见。
 *
 * 收起 = 点应用自己的 ☰（`._2H3hWW_toggle`，回退按 `aria-label` 中英双写）。不碰 store
 * 内部：toggleSidebar 是它唯一入口，界面和状态不会脱节；选择器失配时整个脚本静默
 * 失效（fail-open，最坏就是回到现在的行为）。
 *
 * 类名判据一律用「构建 hash 之后的原名子串」（`[class*="panelList"]`、`[class*="sidebarCol"]`）：
 * 实测 Vite 生成的是 `<hash>_<localName>`，hash 每次重新构建都会变，原名不会。
 */
(function () {
	"use strict";
	if (window.__DSH_SIDEBAR__) return;
	window.__DSH_SIDEBAR__ = true;
	/* 与 dsh-client-ui-layout 的 SIDEBAR_AUTO_COLLAPSE 对齐：≥1024 时侧栏是常驻列。 */
	var NARROW = 1024;

	/* 「插件 / 上下文洞察 / 技能中心」那一组所在的导航；两个分支都要求是 nav，
	   免得 [class*="panelList"] 撞上什么大容器、把整条侧栏都算成面板入口。 */
	function panelNav() {
		return document.querySelector('nav[aria-label="全局面板"],nav[class*="panelList"]');
	}

	function toggleBtn() {
		return (
			document.querySelector("._2H3hWW_toggle") ||
			document.querySelector(
				'[aria-label="收起侧边栏"],[aria-label="打开侧边栏"],[aria-label="Toggle left sidebar"]',
			)
		);
	}

	/* 面板已经收起时绝不能动手：那时再点一次 ☰ 是把侧栏**打开**。
	   收起态侧栏列宽 56–57px、展开态 281px，取 100 当分界；元素找不到就当没展开
	   （宁可不干活，也不要平白把侧栏弹开）。 */
	function expanded() {
		var col = document.querySelector('[class*="sidebarCol"]');
		return !!col && col.getBoundingClientRect().width > 100;
	}

	document.addEventListener(
		"click",
		function (ev) {
			if (window.innerWidth >= NARROW) return;
			if (!expanded()) return;
			var t = ev.target;
			if (!t || !t.closest) return;
			var row = t.closest("[data-row-key]");
			var sessionRow = !!row && String(row.getAttribute("data-row-key")).indexOf("session:") === 0;
			var nav = panelNav();
			if (!sessionRow && !(nav && nav.contains(t))) return;
			var btn = toggleBtn();
			/* React 的 click 监听挂在 root 上、document 的冒泡在它之后，setTimeout 再让出
			   一拍：应用先把会话切过去，行才可能被卸载，收起动作不会插在派发途中。 */
			if (btn)
				setTimeout(function () {
					btn.click();
				}, 0);
		},
		false,
	);
})();
