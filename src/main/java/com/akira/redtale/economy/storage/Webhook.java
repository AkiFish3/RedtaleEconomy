package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.CoreConfig;
import com.akira.redtale.economy.shop.ShopEvents;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T15 外部告警 Webhook（默认关闭）。
 *
 * <p>把「大额成交 / 防小号拦截」这类需要人立刻知道的事件推到外部（QQ 机器人 / 任意 HTTP 端点）。
 * 三条设计约束：
 * <ul>
 *   <li><b>默认关闭且失败静默</b>：网络问题绝不影响交易主流程，异步单线程发出、异常只记日志；</li>
 *   <li><b>限流</b>：滑动 60 秒窗口，超出直接丢（宁可少一条告警，也不要因为刷接口被平台封禁）；</li>
 *   <li><b>格式可配</b>：onebot / qq / json / plain 四种，避免为不同机器人各写一套。</li>
 * </ul>
 *
 * <p>纯逻辑（payload 拼装、URL 校验、限流）与网络分离开，单测用 {@link Poster} 注入假发送器。
 */
public final class Webhook {

	/** 支持的上报格式。 */
	public enum Format {
		/** OneBot v11：{@code {"message":"…"}} */
		ONEBOT,
		/** QQ 官方机器人：{@code {"content":"…","msg_type":0}} */
		QQ,
		/** 通用：{@code {"event":"…","text":"…"}} */
		JSON,
		/** 纯文本 body。 */
		PLAIN;

		public static Format parse(String raw) {
			if (raw == null) return ONEBOT;
			switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "qq":
				case "qqbot":
					return QQ;
				case "json":
					return JSON;
				case "plain":
				case "text":
					return PLAIN;
				default:
					return ONEBOT;
			}
		}
	}

	/** 运行时设置（由 {@link #load()} 从 core.json 读出）。 */
	public record Settings(boolean enabled, String url, Format format, int timeoutMs,
			int perMinute, long largeShopAmount, boolean onLargeShop, boolean onAltGuard,
			boolean onConfigError) {

		public static Settings disabled() {
			return new Settings(false, "", Format.ONEBOT, 3000, 20, 0L, true, true, false);
		}

		public boolean usable() {
			return enabled && validUrl(url);
		}
	}

	/** 发送器接缝（单测注入假实现，生产走 {@link #httpPost}）。 */
	public interface Poster {
		boolean post(String url, String body, int timeoutMs);
	}

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Deque<Long> WINDOW = new ArrayDeque<>();
	private static final AtomicBoolean SHOP_HOOKED = new AtomicBoolean();

	private static volatile Settings settings = Settings.disabled();
	private static volatile Poster poster = Webhook::httpPost;
	private static volatile ExecutorService executor;

	private Webhook() {
	}

	// ---------------------------------------------------------------- 设置

	/** 从 {@code core.json → alerts} 读取；任何异常都退回「关闭」。 */
	public static Settings load() {
		try {
			Settings s = new Settings(
					CoreConfig.store().getPathBool("alerts.enabled", false),
					CoreConfig.store().getPathString("alerts.url", ""),
					Format.parse(CoreConfig.store().getPathString("alerts.format", "onebot")),
					(int) CoreConfig.store().getPathLong("alerts.timeoutMs", 3000),
					(int) CoreConfig.store().getPathLong("alerts.perMinute", 20),
					CoreConfig.store().getPathLong("alerts.largeShopAmount", 10000),
					CoreConfig.store().getPathBool("alerts.events.largeShop", true),
					CoreConfig.store().getPathBool("alerts.events.altGuard", true),
					CoreConfig.store().getPathBool("alerts.events.configError", false));
			settings = s;
			return s;
		} catch (Exception e) {
			settings = Settings.disabled();
			return settings;
		}
	}

	public static Settings settings() {
		return settings;
	}

	/** 测试/热重载用：直接套用一组设置并清空限流窗口。 */
	public static void apply(Settings s) {
		settings = s == null ? Settings.disabled() : s;
		synchronized (WINDOW) {
			WINDOW.clear();
		}
	}

	public static boolean enabled() {
		return settings.usable();
	}

	/** 只接受 http/https；其它协议一律不发（防 file:/jar: 之类被写进配置）。 */
	public static boolean validUrl(String url) {
		if (url == null || url.isBlank()) return false;
		try {
			URI u = URI.create(url.trim());
			String scheme = u.getScheme();
			return scheme != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
					&& u.getHost() != null && !u.getHost().isBlank();
		} catch (Exception e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- payload

	/** 拼 payload（纯函数，便于逐格式断言）。 */
	public static String payload(Format format, String event, String text) {
		Format f = format == null ? Format.ONEBOT : format;
		String body = (event == null || event.isBlank() ? "" : "[" + event + "] ") + (text == null ? "" : text);
		if (f == Format.PLAIN) return body;
		JsonObject o = new JsonObject();
		switch (f) {
			case QQ -> {
				o.addProperty("content", body);
				o.addProperty("msg_type", 0);
			}
			case JSON -> {
				o.addProperty("event", event == null ? "" : event);
				o.addProperty("text", text == null ? "" : text);
			}
			default -> o.addProperty("message", body);
		}
		return GSON.toJson(o);
	}

	// ---------------------------------------------------------------- 限流

	/** 滑动 60 秒限流：允许则记一次并返回 true。 */
	public static boolean allow(long nowMs) {
		int limit = Math.max(1, settings.perMinute());
		synchronized (WINDOW) {
			while (!WINDOW.isEmpty() && nowMs - WINDOW.peekFirst() >= 60_000L) {
				WINDOW.pollFirst();
			}
			if (WINDOW.size() >= limit) return false;
			WINDOW.addLast(nowMs);
			return true;
		}
	}

	// ---------------------------------------------------------------- 发送

	/**
	 * 异步通知：关闭/非法 URL/被限流都直接返回 false，不发也不报错。
	 *
	 * @return 是否已排队
	 */
	public static boolean notify(String event, String text) {
		Settings s = settings;
		if (!s.usable()) return false;
		if (eventAllowed(s, event)) return false;
		if (!allow(System.currentTimeMillis())) return false;
		String body = payload(s.format(), event, text);
		String url = s.url().trim();
		int timeout = s.timeoutMs();
		executor().submit(() -> {
			try {
				poster.post(url, body, timeout);
			} catch (Throwable t) {
				try {
					RedtaleEconomy.LOGGER.warn("[alert] 发送失败（已忽略）：{}", t.toString());
				} catch (Throwable ignored) {
					// 日志不可用也不能影响主流程
				}
			}
		});
		return true;
	}

	/** 同步发送（单测入口 / 关服前冲刷用）。 */
	public static boolean sendNow(String event, String text) {
		Settings s = settings;
		if (!s.usable()) return false;
		try {
			return poster.post(s.url().trim(), payload(s.format(), event, text), s.timeoutMs());
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[alert] 发送失败：{}", e.toString());
			return false;
		}
	}

	private static boolean eventAllowed(Settings s, String event) {
		if (event == null) return false;
		return switch (event) {
			case "shop.large" -> !s.onLargeShop();
			case "alt.block" -> !s.onAltGuard();
			case "config.error" -> !s.onConfigError();
			default -> false;
		};
	}

	/** 大额成交订阅（幂等；由 {@code ShopModule.onLoad} 调用）。 */
	public static void installShopAlerts() {
		if (!SHOP_HOOKED.compareAndSet(false, true)) return;
		ShopEvents.listen(tx -> {
			Settings s = settings;
			if (!s.usable() || !s.onLargeShop()) return;
			if (s.largeShopAmount() <= 0 || tx.total() < s.largeShopAmount()) return;
			notify("shop.large", (tx.buy() ? "买入 " : "卖出 ") + tx.itemId() + " x" + tx.count()
					+ " @ " + tx.unitPrice() + "（合计 " + tx.total() + "）店 " + tx.shopId()
					+ " 玩家 " + tx.player());
		});
	}

	/** 测试隔离：拆掉订阅并恢复默认（仅单测用）。 */
	static void resetForTest() {
		SHOP_HOOKED.set(false);
		settings = Settings.disabled();
		synchronized (WINDOW) {
			WINDOW.clear();
		}
	}

	static void setPosterForTest(Poster p) {
		poster = p == null ? Webhook::httpPost : p;
	}

	private static ExecutorService executor() {
		ExecutorService e = executor;
		if (e == null) {
			synchronized (Webhook.class) {
				if (executor == null) {
					executor = Executors.newSingleThreadExecutor(r -> {
						Thread t = new Thread(r, "redtale-webhook");
						t.setDaemon(true);
						return t;
					});
				}
				e = executor;
			}
		}
		return e;
	}

	private static boolean httpPost(String url, String body, int timeoutMs) {
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(Math.max(500, timeoutMs)))
					.build();
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofMillis(Math.max(500, timeoutMs)))
					.header("Content-Type", "application/json; charset=utf-8")
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() / 100 != 2) {
				RedtaleEconomy.LOGGER.warn("[alert] 端点返回 HTTP {}", res.statusCode());
				return false;
			}
			return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[alert] HTTP 发送异常：{}", e.toString());
			return false;
		}
	}

	/** 供命令展示：当前设置的一句话摘要（不含 token）。 */
	public static String describe() {
		Settings s = settings;
		if (!s.enabled()) return "关闭";
		if (!validUrl(s.url())) return "已启用但 URL 非法";
		return s.format() + " → " + mask(s.url()) + "（≤" + s.perMinute() + "/分，≥" + s.largeShopAmount() + " 告警）";
	}

	/** 展示用脱敏：**整段 query 丢弃**（access_token 常在 query 尾部，按字符截断必漏）。 */
	static String mask(String url) {
		if (url == null) return "";
		try {
			URI u = URI.create(url.trim());
			if (u.getScheme() == null || u.getHost() == null) return shorten(url);
			String path = u.getPath() == null ? "" : u.getPath();
			if (path.length() > 18) path = path.substring(0, 18) + "…";
			return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "")
					+ path + (u.getQuery() != null ? "?…" : "");
		} catch (Exception e) {
			return shorten(url);
		}
	}

	private static String shorten(String url) {
		return url.length() <= 24 ? url : url.substring(0, 12) + "…";
	}

	/** 事件名清单（命令补全 / 文档用）。 */
	public static Set<String> events() {
		return Set.of("shop.large", "alt.block", "config.error");
	}
}
