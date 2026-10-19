package com.akira.redtale.economy.config;

import com.akira.redtale.economy.RedtaleEconomy;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置版本迁移骨架（Q6）。
 *
 * <p>每个迁移是「从 vN 到 vN+1」的一步，按版本顺序链式执行；缺中间步骤就停在那一步并 WARN，
 * 不猜测、不跳级。所有迁移必须是<b>纯函数</b>（只改传入的 JsonObject），便于单测。
 *
 * <p>当前 {@code CURRENT_VERSION = 1}，还没有历史版本，所以内置迁移表为空 ——
 * 这层先建好，等 v2 加字段时只需 {@link #register} 一行，不用改 ConfigStore。
 */
public final class ConfigMigrations {

	/** 单个迁移步骤。 */
	public interface Step {
		/** 目标版本（这一步执行完版本号变成它）。 */
		int to();

		void apply(JsonObject root);
	}

	private static final List<Step> STEPS = new ArrayList<>();

	private ConfigMigrations() {
	}

	/** 注册一步迁移；重复注册同一目标版本会被忽略（先到先得）。 */
	public static synchronized void register(Step step) {
		for (Step s : STEPS) {
			if (s.to() == step.to()) {
				RedtaleEconomy.LOGGER.warn("[config] 迁移到 v{} 已存在，忽略重复注册", step.to());
				return;
			}
		}
		STEPS.add(step);
		STEPS.sort((a, b) -> Integer.compare(a.to(), b.to()));
	}

	/**
	 * 把 {@code root} 从 {@code from} 迁到 {@code target}。
	 *
	 * @return 实际到达的版本；缺失中间步骤时返回停在的版本（调用方据此 WARN）
	 */
	public static int migrate(JsonObject root, int from, int target) {
		int v = from;
		while (v < target) {
			Step step = find(v + 1);
			if (step == null) {
				RedtaleEconomy.LOGGER.warn("[config] 缺少 v{} → v{} 的迁移，停在 v{}", v, v + 1, v);
				return v;
			}
			step.apply(root);
			v = step.to();
		}
		return v;
	}

	private static Step find(int to) {
		for (Step s : STEPS) {
			if (s.to() == to) return s;
		}
		return null;
	}

	/** 已注册的迁移链（日志/诊断用）。 */
	public static synchronized String describe() {
		if (STEPS.isEmpty()) return "（无迁移步骤）";
		StringBuilder sb = new StringBuilder();
		for (Step s : STEPS) sb.append("→v").append(s.to()).append(' ');
		return sb.toString().trim();
	}
}
