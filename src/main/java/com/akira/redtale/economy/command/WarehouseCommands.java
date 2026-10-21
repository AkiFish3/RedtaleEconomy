package com.akira.redtale.economy.command;

import com.akira.redtale.economy.config.WarehouseConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.WarehouseModule;
import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.warehouse.WarehouseData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.List;

/**
 * {@code /storage}（别名 {@code /warehouse}）—— 玩家仓储（R27）与扩容（R28）。
 *
 * <p>{@code /storage} 打开第 1 页，{@code /storage 2} 打开第 2 页；
 * 打开后聊天栏会给翻页/扩容的可点击链接（原版容器 GUI 没有按钮位，用聊天栏补）。
 */
public final class WarehouseCommands {

	private WarehouseCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d, List<String> names) {
		for (String n : names) CommandTable.declare(d, n, "warehouse");
		for (String name : names) {
			if (name == null || name.isBlank()) continue;
			LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal(name)
					.requires(src -> Permissions.has(src, Permissions.NODE_USE))
					.executes(ctx -> open(ctx, 1))
					.then(CommandManager.argument("page", IntegerArgumentType.integer(1, 99))
							.executes(ctx -> open(ctx, IntegerArgumentType.getInteger(ctx, "page"))))
					.then(CommandManager.literal("upgrade").executes(WarehouseCommands::upgrade))
					.then(CommandManager.literal("info").executes(WarehouseCommands::info));
			d.register(root);
		}
	}

	private static int open(CommandContext<ServerCommandSource> ctx, int page) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!WarehouseModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("warehouse.disabled")));
			return 0;
		}
		WarehouseData data = WarehouseData.of(p.getUuid());
		int pageSize = WarehouseConfig.pageSize();
		int pages = Math.max(1, (int) Math.ceil(data.slots() / (double) pageSize));
		int want = Math.max(1, Math.min(page, pages));
		WarehouseModule.open(p, want - 1);

		String cmd = WarehouseConfig.openCommands().get(0);
		Text hint = Text.literal(Lang.msg("warehouse.pageHint", want, pages, data.slots()))
				.setStyle(Style.EMPTY.withColor(0x9A9A9A));
		if (pages > 1) {
			Text prev = Text.literal(Lang.t("warehouse.prev"))
					.setStyle(Style.EMPTY.withColor(0xC9A227).withClickEvent(
							new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + cmd + " " + Math.max(1, want - 1))));
			Text next = Text.literal(Lang.t("warehouse.next"))
					.setStyle(Style.EMPTY.withColor(0xC9A227).withClickEvent(
							new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + cmd + " " + Math.min(pages, want + 1))));
			src.sendFeedback(() -> Text.empty().append(hint).append("  ").append(prev).append("  ").append(next), false);
		} else {
			src.sendFeedback(() -> hint, false);
		}
		src.sendFeedback(() -> Text.literal(Lang.t("warehouse.upgradeHint"))
				.setStyle(Style.EMPTY.withColor(0xC9A227).withClickEvent(
						new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + cmd + " upgrade"))), false);
		return 1;
	}

	private static int upgrade(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		WarehouseModule.UpgradeOutcome out = WarehouseModule.upgrade(p);
		switch (out.result()) {
			case OK -> {
				WarehouseConfig.Upgrade step = out.step();
				String cost = step == null ? "" : costText(step);
				src.sendFeedback(() -> Text.literal(Lang.msg("warehouse.upgrade.ok", out.slots(), cost)), false);
				// 重开界面让新格位立刻可见
				p.closeHandledScreen();
				WarehouseModule.open(p, 0);
				return 1;
			}
			case MAX -> {
				src.sendError(Text.literal(Lang.msg("warehouse.upgrade.max", out.slots())));
				return 0;
			}
			case DISABLED -> {
				src.sendError(Text.literal(Lang.msg("warehouse.upgrade.disabled")));
				return 0;
			}
			case NO_MONEY -> {
				long need = out.step() == null ? 0 : out.step().price();
				src.sendError(Text.literal(Lang.msg("warehouse.upgrade.noMoney",
						BalanceService.format(need), BalanceService.format(BalanceService.get(p.getUuid())))));
				return 0;
			}
			case NO_PREMIUM -> {
				long need = out.step() == null ? 0 : out.step().premium();
				src.sendError(Text.literal(Lang.msg("warehouse.upgrade.noPremium", need)));
				return 0;
			}
			default -> {
				src.sendError(Text.literal(Lang.msg("warehouse.upgrade.failed")));
				return 0;
			}
		}
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		WarehouseData data = WarehouseData.of(p.getUuid());
		src.sendFeedback(() -> Text.literal(Lang.msg("warehouse.info",
				data.slots(), WarehouseConfig.maxSlots(), data.slots() / 9)), false);
		WarehouseConfig.Upgrade next = WarehouseConfig.nextUpgrade(data.slots());
		if (next == null) {
			src.sendFeedback(() -> Text.literal(Lang.msg("warehouse.upgrade.max", data.slots())), false);
		} else {
			src.sendFeedback(() -> Text.literal(Lang.msg("warehouse.info.next", next.slots(), costText(next))), false);
		}
		return 1;
	}

	private static String costText(WarehouseConfig.Upgrade step) {
		StringBuilder sb = new StringBuilder();
		if (step.price() > 0) sb.append(BalanceService.format(step.price()));
		if (step.premium() > 0) {
			if (sb.length() > 0) sb.append(" + ");
			sb.append(step.premium()).append(' ').append(Lang.t("currency.premium"));
		}
		return sb.length() == 0 ? Lang.t("currency.free") : sb.toString();
	}

	private static int onlyPlayer(ServerCommandSource src) {
		src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
		return 0;
	}
}
