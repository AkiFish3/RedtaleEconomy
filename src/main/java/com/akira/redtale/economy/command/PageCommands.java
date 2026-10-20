package com.akira.redtale.economy.command;

import com.akira.redtale.economy.net.HubNetworking;
import com.akira.redtale.economy.net.PacketIds;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /rt <page>} 直达页面（U8b）。
 *
 * <p>收敛后的命令模型：<b>一个入口 + 一个页面名</b>。
 * <pre>
 * /rt              打开签到（默认页）
 * /rt shop         打开商店
 * /rt storage|mail|rank|settings ...
 * </pre>
 *
 * <p>旧命令（{@code /checkin /tasks /bounty /guild /pass /board}）<b>仍然可用</b>，
 * 只是首次使用时用 actionbar 提示一次「以后用 /rt xxx」。提示按玩家记一次，不刷屏、不进聊天历史。
 */
public final class PageCommands {

	/** 页面 id → 中文名（用于提示与补全）。顺序即补全顺序。 */
	private static final Map<String, String> PAGES = new LinkedHashMap<>();

	static {
		PAGES.put("checkin", "签到");
		PAGES.put("shop", "商店");
		PAGES.put("tasks", "任务");
		PAGES.put("storage", "仓储");
		PAGES.put("bounty", "悬赏");
		PAGES.put("pass", "战令");
		PAGES.put("guild", "协会");
		PAGES.put("mail", "邮件");
		PAGES.put("rank", "排行");
		PAGES.put("settings", "设置");
	}

	/** player → 已提示过的旧命令（只在内存里，重启后最多再提示一次）。 */
	private static final Map<UUID, Set<String>> HINTED = new ConcurrentHashMap<>();

	private PageCommands() {
	}

	public static Set<String> pageIds() {
		return PAGES.keySet();
	}

	/** 把 {@code <page>} 挂到 /redtale（/rt 是它的 redirect）根节点上。 */
	public static void attach(LiteralArgumentBuilder<ServerCommandSource> root) {
		root.then(CommandManager.argument("page", StringArgumentType.word())
				.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(PAGES.keySet(), b))
				.executes(ctx -> {
					ServerPlayerEntity p = ctx.getSource().getPlayer();
					if (p == null) {
						ctx.getSource().sendError(Text.literal("该命令只能由玩家执行"));
						return 0;
					}
					String page = StringArgumentType.getString(ctx, "page").toLowerCase();
					if (!PAGES.containsKey(page)) {
						// 错误走聊天（§38）：玩家要能翻回去看
						ctx.getSource().sendError(Text.literal("§c没有这个页面：§f" + page
								+ " §7可用：§f" + String.join(" ", PAGES.keySet())));
						return 0;
					}
					return open(p, page);
				}));
	}

	/** 打开指定页；数据由各页自己的快照包补齐，HUB_OPEN 只负责「切到哪一页」。 */
	public static int open(ServerPlayerEntity p, String page) {
		// 任务面板是独立 Screen（U2），不是 Hub 内页；直达后就不再发 HUB_OPEN
		if ("tasks".equals(page)) {
			com.akira.redtale.economy.net.TaskNetworking.sendSnapshot(p);
			return 1;
		}
		switch (page) {
			case "checkin" -> com.akira.redtale.economy.net.CheckinNetworking.sendSnapshot(p);
			case "shop" -> {
				var ids = com.akira.redtale.economy.shop.ShopCatalog.shopIds();
				if (ids.isEmpty()) {
					p.sendMessage(Text.literal("§e暂无可用的商店"), true);
					return 0;
				}
				com.akira.redtale.economy.net.ShopNetworking.sendOpen(p, ids.get(0));
			}
			case "storage" -> com.akira.redtale.economy.net.WarehouseNetworking.send(p);
			case "bounty" -> com.akira.redtale.economy.net.BountyNetworking.send(p);
			case "pass" -> com.akira.redtale.economy.net.PassNetworking.send(p);
			case "guild" -> com.akira.redtale.economy.net.GuildNetworking.send(p);
			case "rank" -> com.akira.redtale.economy.net.RankNetworking.send(p);
			default -> {
			}
		}
		// 最后一个包：明确告诉客户端切到哪一页（storage / mail / settings 只有这一个包）
		HubNetworking.open(p, page);
		return 1;
	}

	/**
	 * 旧命令首次使用时提示一次。{@code page} 为空表示没有对应新页面（不提示）。
	 *
	 * <p>走 actionbar：不刷聊天、不占历史；只有玩家主动敲旧命令才会出现。
	 */
	public static void hintLegacy(ServerPlayerEntity p, String legacy, String page) {
		if (p == null || page == null || page.isEmpty()) return;
		Set<String> seen = HINTED.computeIfAbsent(p.getUuid(), k -> ConcurrentHashMap.newKeySet());
		if (!seen.add(legacy)) return;
		p.sendMessage(Text.literal("§7/" + legacy + " §8→ §f/rt " + page + " §7（旧命令仍可使用）"), true);
	}

	/** 供 {@code /rt help}、日志与自检使用。 */
	public static String pageOfPacket() {
		return PacketIds.S2C_HUB_OPEN.toString();
	}
}
