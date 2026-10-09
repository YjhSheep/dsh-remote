/* notify.js — wire 层事件订阅 → Android 通知（不依赖 DSH ctx，直接连 remote.mux）
 * 事件来源：$events 流（forwarded events 白名单）。
 * 对 waterfall 事件（approval/request、user-questions/request）静默旁观：绝不回
 * $events/result，让真正的 UI 模块处理（回 result/rejected 会吞掉 UI 的处理机会）。
 * 通知文案带会话标题：标题来自 POST /api/session/list 的 items[].projections.values.title；
 * waterfall 帧的 agentId 在 wire 上就是 SessionId（dsh-session/types#SessionId）。
 * 同时用列表的 running=true 预置 engaged，修复「页面刷新后错过 true 边沿导致完成不通知」。
 */
(function () {
	"use strict";
	if (window.__DSH_NOTIFY__) return;
	var STATE = { started: false, backoff: 0, ws: null };
	var TITLES = { map: {}, inflight: null, fetchedAt: 0 };

	function wsUrl() {
		var proto = location.protocol === "https:" ? "wss://" : "ws://";
		return proto + location.host + "/api/remote.mux";
	}

	function uuid() {
		return (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()) + "-" + Math.random();
	}

	function androidNotify(title, body) {
		try {
			if (window.AndroidBridge && typeof window.AndroidBridge.notify === "function") {
				window.AndroidBridge.notify(String(title), String(body || ""));
			}
		} catch (e) { /* 桥不可用时静默 */ }
	}

	function trunc(s, n) {
		s = String(s == null ? "" : s);
		return s.length > n ? s.slice(0, n - 1) + "…" : s;
	}

	function fallbackTitle(sessionId) {
		return sessionId ? String(sessionId).replace(/^session-/, "").slice(0, 8) + "…" : "";
	}

	function withTimeout(p, ms) {
		return Promise.race([p, new Promise(function (res) { setTimeout(res, ms); })]);
	}

	// 同一张表既喂标题也喂 running 预置；3s 节流 + 单飞，失败也计节流防打爆。
	function fetchTitles() {
		if (TITLES.inflight) return TITLES.inflight;
		if (Date.now() - TITLES.fetchedAt < 3000) return Promise.resolve();
		TITLES.inflight = fetch("/api/session/list", {
			method: "POST",
			headers: { "content-type": "application/json" },
			body: JSON.stringify({
				type: "client-request",
				rpcId: uuid(),
				method: "session/list",
				payload: { args: { _request: {} } }
			})
		}).then(function (r) {
			return r.ok ? r.json() : null;
		}).then(function (env) {
			TITLES.fetchedAt = Date.now();
			var ok = env && env.type === "server-response" && env.result && env.result.ok;
			var items = ok ? env.result.value && env.result.value.items : null;
			if (!items) return;
			var engaged = handleEmit.engaged || (handleEmit.engaged = {});
			for (var i = 0; i < items.length; i++) {
				var it = items[i];
				if (!it || !it.sessionId) continue;
				var t = it.projections && it.projections.values && it.projections.values.title;
				if (typeof t === "string" && t) TITLES.map[it.sessionId] = t;
				if (it.running) engaged[it.sessionId] = true;
			}
		}).catch(function () {
			TITLES.fetchedAt = Date.now();
		}).then(function () {
			TITLES.inflight = null;
		});
		return TITLES.inflight;
	}

	function resolveTitle(sessionId, cb) {
		var t = TITLES.map[sessionId];
		if (t) { cb(t); return; }
		withTimeout(fetchTitles(), 2000).then(function () { cb(TITLES.map[sessionId] || ""); });
	}

	// 统一出口：通知标题=类别，正文=「会话标题：细节」。
	function notifyWithSession(sessionId, kind, detail) {
		resolveTitle(sessionId, function (t) {
			var body = (t ? t + (detail ? "：" + detail : "") : detail) || fallbackTitle(sessionId);
			androidNotify(kind, trunc(body, 120));
		});
	}

	function handleEmit(event, args) {
		if (event !== "api-session/status") return;
		// args = [sessionId, running]；只关心 true→false 边沿（完成）。
		var sessionId = args && args[0];
		var running = args && args[1];
		if (typeof sessionId !== "string" || typeof running !== "boolean") return;
		var engaged = handleEmit.engaged || (handleEmit.engaged = {});
		if (running) {
			engaged[sessionId] = true;
			resolveTitle(sessionId, function () {}); // 预热标题缓存，完成时即时可用
			return;
		}
		if (!engaged[sessionId]) return; // 列表里也不在跑的会话不通知（fetchTitles 会补种 running）
		delete engaged[sessionId];
		notifyWithSession(sessionId, "任务完成", "");
	}

	function handleWaterfall(event, frame) {
		var sid = frame && frame.agentId; // wire 上 agentId 即 SessionId
		if (event === "approval/request") {
			var req = frame && frame.request;
			if (!req) return;
			var body = req.displayReason || req.reason || req.toolName || "";
			var detail = req.toolName && body && body !== req.toolName ? req.toolName + "：" + body : body;
			notifyWithSession(sid, "待确认", detail);
		} else if (event === "user-questions/request") {
			var rq = frame && frame.request;
			var qs = rq && rq.questions;
			if (!qs || !qs.length) return;
			var first = qs[0];
			var text = (first.header ? first.header + "：" : "") + (first.question || first.id || "");
			if (qs.length > 1) text += "（等 " + qs.length + " 个问题）";
			notifyWithSession(sid, "提问", text);
		}
		// 静默旁观：不回 $events/result，waterfall 链继续交给 UI 模块。
	}

	function onItem(value) {
		if (!value || typeof value !== "object") return;
		if (value.type === "emit") {
			handleEmit(value.event, value.args);
		} else if (value.type === "waterfall") {
			handleWaterfall(value.event, value);
		}
		// ready / cancel：无需处理
	}

	function connect() {
		if (STATE.ws) { try { STATE.ws.close(); } catch (e) {} STATE.ws = null; }
		var ws;
		try { ws = new WebSocket(wsUrl()); } catch (e) { return retry(); }
		STATE.ws = ws;
		var streamId = uuid();
		ws.onopen = function () {
			STATE.backoff = 0;
			ws.send(JSON.stringify({
				type: "open",
				streamId: streamId,
				endpoint: "$events",
				payload: { args: {} }
			}));
		};
		ws.onmessage = function (msg) {
			var frame;
			try { frame = JSON.parse(msg.data); } catch (e) { return; }
			if (frame && frame.type === "item" && frame.streamId === streamId) onItem(frame.value);
		};
		ws.onclose = function () { if (STATE.ws === ws) { STATE.ws = null; retry(); } };
		ws.onerror = function () { /* onclose 会跟上来 */ };
	}

	function retry() {
		var delay = [1000, 5000, 15000][Math.min(STATE.backoff, 2)];
		STATE.backoff++;
		setTimeout(connect, delay);
	}

	function start() {
		if (STATE.started) return;
		STATE.started = true;
		connect();
		fetchTitles(); // 启动即拉一次：喂标题 + 预置 running（刷新后也能收到完成通知）
		// ponytail: B2 兜底轮询未做——WS 断开超 60s 的场景目前只有通知丢失风险，验收后再补
		// ponytail: 标题缓存无 TTL——DSH 里改标题后通知仍用旧值，页面刷新自愈
	}

	start();
	window.__DSH_NOTIFY__ = { reconnect: connect, state: STATE, titles: TITLES };
})();
