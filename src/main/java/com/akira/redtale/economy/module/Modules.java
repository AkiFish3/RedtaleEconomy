package com.akira.redtale.economy.module;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.ModulesProtocol;

import java.util.ArrayList;
import java.util.List;

/**
 * T51 模块总开关的门面（命令、Hub 设置页、网络包都走这里）。
 *
 * <p>为什么不是直接在 {@link ModuleManager} 上加：Manager 是注册/加载期的内部件，
 * 这里处理的是「管理员要求改一个开关」这件事 —— 要判可开关性、落盘、应用、
 * 生成用户可读的结果文案。两件事分开，Manager 才能保持不依赖网络/权限。
 */
public final class Modules {

	/** 一次切换的结果：{@code ok=false} 时 {@code note} 是给人看的原因。 */
	public record Result(boolean ok, String note) {
	}

	private Modules() {
	}

	/** 模块是否开启；未注册的 id 一律视为「不存在 ⇒ 关闭」，避免调用方各自兜底。 */
	public static boolean enabled(String id) {
		ModuleManager mm = ModuleManager.get();
		return mm.known(id) && mm.isEnabled(id);
	}

	public static boolean toggleable(String id) {
		return ModuleManager.get().toggleable(id);
	}

	public static boolean locked(String id) {
		return ModuleManager.locked(id);
	}

	/** 全量视图（服务端下发给客户端的唯一口径，顺序 = 注册顺序）。 */
	public static List<ModulesProtocol.ModuleView> views() {
		ModuleManager mm = ModuleManager.get();
		List<ModulesProtocol.ModuleView> out = new ArrayList<>();
		for (RedtaleModule m : mm.all()) {
			boolean on = mm.isEnabled(m.id());
			boolean locked = locked(m.id());
			String note = on ? ""
					: locked ? "不可关闭"
					: mm.reason(m.id());
			out.add(new ModulesProtocol.ModuleView(m.id(), on, locked, note));
		}
		return out;
	}

	/**
	 * 切换一个模块并<b>立即</b>应用（写盘 → 运行时 → 命令树 → 在线玩家）。
	 *
	 * <p>拒绝的情况全部返回明确文案，不做静默：未知 id / economy / 写盘失败。
	 */
	public static Result set(String id, boolean on) {
		ModuleManager mm = ModuleManager.get();
		if (id == null || id.isBlank()) return new Result(false, "缺少模块 id");
		if (!mm.known(id)) return new Result(false, "没有模块 " + id);
		if (locked(id)) return new Result(false, id + " 不可关闭（" + (ModuleManager.ALWAYS_ON.equals(id) ? "经济基础" : "余额/流水本体") + "）");
		if (!mm.toggleable(id)) return new Result(false, id + " 不支持开关");
		boolean cur = mm.isEnabled(id);
		if (cur == on) return new Result(true, id + " 已经是" + (on ? "开启" : "关闭") + "状态");
		if (!mm.writeSwitch(id, on)) {
			return new Result(false, "写 modules.json 失败（见日志）");
		}
		List<String> notes = mm.applySwitches();
		RedtaleEconomy.LOGGER.info("[module] {} → {}（{}）", id, on ? "on" : "off",
				notes.isEmpty() ? "无附加动作" : String.join("、", notes));
		return new Result(true, id + " 已" + (on ? "开启" : "关闭")
				+ (notes.isEmpty() ? "" : "（" + String.join("、", notes) + "）"));
	}

	/** 简易状态行（命令列表 / 控制台指标共用）。 */
	public static String statusLine(String id) {
		ModuleManager mm = ModuleManager.get();
		if (!mm.known(id)) return "?";
		if (mm.isEnabled(id)) return "开";
		return "关" + (locked(id) ? "" : "（" + mm.reason(id) + "）");
	}
}
