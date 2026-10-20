package com.akira.redtale.economy.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 布局里 {@code "source": "tasks.list"} 对应的数据供给（R15）。
 *
 * <p>与服务端解耦：服务端只发原始数据，客户端注册 provider 把它们变成「一行文本」。
 * 没有 provider 时渲染器显示 {@code （无数据源: xxx）}，<b>绝不编造数据</b>，
 * 免得界面看起来正常、实际是假数字。
 */
public final class UiDataRegistry {

	/** 数据供给方。返回 null 表示「暂时没有数据」，与「空列表」区分。 */
	public interface Provider {
		List<String> rows(String source);
	}

	private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();

	private UiDataRegistry() {
	}

	public static synchronized void register(String source, Provider provider) {
		PROVIDERS.put(source, provider);
	}

	public static synchronized void clear() {
		PROVIDERS.clear();
	}

	/** 未注册返回 null（渲染器据此提示，而不是显示空列表）。 */
	public static List<String> rows(String source) {
		Provider p;
		synchronized (UiDataRegistry.class) {
			p = PROVIDERS.get(source);
		}
		if (p == null) return null;
		try {
			List<String> r = p.rows(source);
			return r == null ? Collections.emptyList() : r;
		} catch (Exception e) {
			return List.of("（数据源 " + source + " 读取失败）");
		}
	}

	public static synchronized boolean has(String source) {
		return PROVIDERS.containsKey(source);
	}
}
