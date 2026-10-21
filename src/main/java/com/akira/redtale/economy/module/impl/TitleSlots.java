package com.akira.redtale.economy.module.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * 称号多槽位（T60）+ 强制称号拼装（T66）的纯函数集合。
 *
 * <p>零 MC 依赖 ⇒ 可单测。服务端渲染与客户端预览共用同一套槽位规则，
 * 避免「界面显示 3 个、聊天只出 1 个」这类分叉。
 *
 * <p>槽位模型：{@code equipped} 是**有序**列表，下标 0 最靠前；长度上限由玩家解锁数
 * （{@link #MAX_SLOTS} 封顶）决定。空串/重复项一律视为无效，读取时归一化。
 */
public final class TitleSlots {

	/** 槽位硬上限（配置的上限不能超过它）。 */
	public static final int MAX_SLOTS = 3;

	/** 默认拼接符：{@code [A·B]}。 */
	public static final String DEFAULT_SEPARATOR = "·";

	private TitleSlots() {
	}

	/** 归一化：丢空串/重复/首尾空白，截断到 {@code slots} 个。 */
	public static List<String> normalize(List<String> in, int slots) {
		List<String> out = new ArrayList<>();
		if (in != null) {
			for (String raw : in) {
				if (raw == null) continue;
				String t = raw.trim();
				if (t.isEmpty() || out.contains(t)) continue;
				if (out.size() >= clampSlots(slots)) break;
				out.add(t);
			}
		}
		return out;
	}

	/** 槽位数夹到 [0, MAX_SLOTS]。 */
	public static int clampSlots(int slots) {
		if (slots < 0) return 0;
		return Math.min(slots, MAX_SLOTS);
	}

	/**
	 * 装备到指定槽位。
	 *
	 * @param slot 目标槽位；{@code <0} = 追加到末尾（槽位满则替换最后一个）
	 * @return 新列表（不改入参，无空洞）
	 */
	public static List<String> equip(List<String> equipped, String title, int slot) {
		List<String> out = new ArrayList<>(normalize(equipped, MAX_SLOTS));
		if (title == null || title.trim().isEmpty()) return out;
		String t = title.trim();
		// 同一个称号不允许同时占两个槽：先摘掉旧的（下标因此可能左移，故先算位置再插）
		out.remove(t);
		int target = slot >= 0 ? Math.min(slot, out.size()) : Math.min(out.size(), MAX_SLOTS - 1);
		if (out.size() >= MAX_SLOTS) {
			target = Math.min(target, MAX_SLOTS - 1);
			out.set(target, t);   // 槽满 = 就地替换
		} else {
			out.add(target, t);
		}
		return out;
	}

	/** 取消第 {@code index} 个槽；越界返回原值归一化结果。 */
	public static List<String> unequip(List<String> equipped, int index) {
		List<String> out = new ArrayList<>(normalize(equipped, MAX_SLOTS));
		if (index >= 0 && index < out.size()) out.remove(index);
		return out;
	}

	/** 整段卸下某个称号（不论在哪个槽）。 */
	public static List<String> unequip(List<String> equipped, String title) {
		List<String> out = new ArrayList<>(normalize(equipped, MAX_SLOTS));
		if (title != null) out.remove(title.trim());
		return out;
	}

	/** 去掉空槽，保持相对顺序。 */
	public static List<String> compact(List<String> in) {
		List<String> out = new ArrayList<>();
		if (in != null) {
			for (String s : in) {
				if (s != null && !s.trim().isEmpty()) out.add(s.trim());
			}
		}
		return out;
	}

	/** 拼接（{@code [A·B]} 里的内层）；空列表返回空串。 */
	public static String join(List<String> parts, String separator) {
		if (parts == null || parts.isEmpty()) return "";
		String sep = separator == null || separator.isEmpty() ? DEFAULT_SEPARATOR : separator;
		StringBuilder sb = new StringBuilder();
		for (String p : parts) {
			if (p == null || p.isEmpty()) continue;
			if (sb.length() > 0) sb.append(sep);
			sb.append(p);
		}
		return sb.toString();
	}
}
