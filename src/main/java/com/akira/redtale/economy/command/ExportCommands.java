package com.akira.redtale.economy.command;

import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.InternalProvider;
import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.shop.ShopCatalog;
import com.akira.redtale.economy.storage.CsvExport;
import com.akira.redtale.economy.util.DataRoot;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * T14：{@code /rt admin export <items|assets|ledger> …} —— 导出 CSV 供 Excel / 对账脚本使用。
 *
 * <pre>
 * /rt admin export items  [文件]
 * /rt admin export assets [文件]
 * /rt admin export ledger [起始日] [结束日] [文件]
 * </pre>
 *
 * <p>不写文件时落到 {@code <数据根>/export/<kind>-yyyyMMdd-HHmmss.csv}，命令反馈绝对路径 ——
 * 服主常在异地开服，只回相对路径等于没说。
 *
 * <p>路径参数一律 {@code greedyString}：{@code StringArgumentType.word()} 遇到 {@code shop/x.csv}
 * 会直接报 “Expected whitespace to end one argument”（2026-10-21 实测，见 _LESSONS）。
 */
public final class ExportCommands {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private ExportCommands() {
	}

	public static LiteralArgumentBuilder<ServerCommandSource> build() {
		return CommandManager.literal("export")
				.requires(src -> Permissions.has(src, Permissions.NODE_OWNER))
				.executes(ExportCommands::usage)
				// ---- 商品表
				.then(CommandManager.literal("items")
						.executes(ctx -> items(ctx, null))
						.then(CommandManager.argument("file", StringArgumentType.greedyString())
								.executes(ctx -> items(ctx, StringArgumentType.getString(ctx, "file")))))
				// ---- 玩家资产
				.then(CommandManager.literal("assets")
						.executes(ctx -> assets(ctx, null))
						.then(CommandManager.argument("file", StringArgumentType.greedyString())
								.executes(ctx -> assets(ctx, StringArgumentType.getString(ctx, "file")))))
				// ---- 流水（默认近 7 天）
				.then(CommandManager.literal("ledger")
						.executes(ctx -> ledger(ctx, null, null, null))
						.then(CommandManager.argument("from", StringArgumentType.word())
								.then(CommandManager.argument("to", StringArgumentType.word())
										.executes(ctx -> ledger(ctx,
												StringArgumentType.getString(ctx, "from"),
												StringArgumentType.getString(ctx, "to"), null))
										.then(CommandManager.argument("file", StringArgumentType.greedyString())
												.executes(ctx -> ledger(ctx,
														StringArgumentType.getString(ctx, "from"),
														StringArgumentType.getString(ctx, "to"),
														StringArgumentType.getString(ctx, "file")))))));
	}

	private static int usage(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		src.sendFeedback(() -> Text.literal("§6[export] §f/rt admin export items [文件]"), false);
		src.sendFeedback(() -> Text.literal("§6[export] §f/rt admin export assets [文件]"), false);
		src.sendFeedback(() -> Text.literal("§6[export] §f/rt admin export ledger [起始日] [结束日] [文件]"), false);
		src.sendFeedback(() -> Text.literal("§7日期格式 §fyyyy-MM-dd§7，缺省近 7 天；文件缺省落 §f<数据根>/export/"),
				false);
		return 1;
	}

	// ---------------------------------------------------------------- 商品

	private static int items(CommandContext<ServerCommandSource> ctx, String file) {
		ServerCommandSource src = ctx.getSource();
		try {
			List<String> rows = new ArrayList<>();
			for (String shopId : ShopCatalog.shopIds()) {
				ShopCatalog.ShopDef shop = ShopCatalog.shop(shopId);
				if (shop == null) continue;
				String shopName = ShopCatalog.shopName(shopId);
				String fileName = shop.file() == null ? "" : shop.file().getFileName().toString();
				for (ShopCatalog.ItemDef it : shop.items()) {
					if (it == null) continue;
					rows.add(CsvExport.row(
							shopId,
							shopName,
							it.id(),
							it.category(),
							Long.toString(it.buy()),
							Long.toString(ShopCatalog.sellPrice(it)),
							Long.toString(it.stockInit()),
							Integer.toString(ShopCatalog.dailyLimitOf(shopId, it.id())),
							Boolean.toString(it.enabled()),
							it.nameOrEmpty(),
							fileName));
				}
			}
			Path out = target(file, "items");
			CsvExport.Stats st = CsvExport.writeTable(out, CsvExport.ITEM_COLUMNS, rows);
			src.sendFeedback(() -> Text.literal("§a[export] 商品 §f" + st.rows() + " §a行 → §f" + out), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c导出失败：" + e.getMessage()));
			return 0;
		}
	}

	// ---------------------------------------------------------------- 资产

	private static int assets(CommandContext<ServerCommandSource> ctx, String file) {
		ServerCommandSource src = ctx.getSource();
		try {
			// 先 flush：导出要的是「落盘事实」，内存里未写的余额会让总数偏小
			InternalProvider provider = com.akira.redtale.economy.economy.BalanceService.internal();
			if (provider != null) provider.flushAll();
			Path dir = DataRoot.path().resolve(StorageConfig.accountsDir());
			Path out = target(file, "assets");
			CsvExport.Stats st = CsvExport.exportAssets(dir, out);
			src.sendFeedback(() -> Text.literal("§a[export] 资产 §f" + st.rows() + " §a行"
					+ (st.skipped() > 0 ? " §e（跳过坏文件 " + st.skipped() + "）" : "")
					+ " §a→ §f" + out), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c导出失败：" + e.getMessage()));
			return 0;
		}
	}

	// ---------------------------------------------------------------- 流水

	private static int ledger(CommandContext<ServerCommandSource> ctx, String fromArg, String toArg, String file) {
		ServerCommandSource src = ctx.getSource();
		try {
			LocalDate today = LocalDate.now();
			LocalDate from = CsvExport.parseDate(fromArg, today.minusDays(6));
			LocalDate to = CsvExport.parseDate(toArg, today);
			if (fromArg != null && !fromArg.isBlank() && CsvExport.parseDate(fromArg, null) == null) {
				src.sendError(Text.literal("§c起始日格式错误（要 yyyy-MM-dd）：" + fromArg));
				return 0;
			}
			if (toArg != null && !toArg.isBlank() && CsvExport.parseDate(toArg, null) == null) {
				src.sendError(Text.literal("§c结束日格式错误（要 yyyy-MM-dd）：" + toArg));
				return 0;
			}
			Path dir = DataRoot.path().resolve(StorageConfig.ledgerDir());
			if (!Files.isDirectory(dir) && !com.akira.redtale.economy.storage.MysqlBackend.available()) {
				src.sendError(Text.literal("§c流水目录不存在：" + dir));
				return 0;
			}
			if (com.akira.redtale.economy.storage.MysqlBackend.available()) {
				src.sendError(Text.literal("§ebackend=mysql 时流水在数据库里，CSV 导出只支持 JSONL（见 T21）"));
				return 0;
			}
			Path out = target(file, "ledger");
			CsvExport.Stats st = CsvExport.exportLedger(dir, StorageConfig.granularity(), from, to, out);
			final String range = from + " ~ " + to;
			src.sendFeedback(() -> Text.literal("§a[export] 流水 §f" + st.rows() + " §a行（§7" + range + "§a）"
					+ (st.skipped() > 0 ? " §e（跳过坏行 " + st.skipped() + "）" : "")
					+ " §a→ §f" + out), false);
			if (st.rows() == 0) {
				src.sendFeedback(() -> Text.literal("§7区间内没有流水（检查 storage.json 的 ledgerDir / granularity）"),
						false);
			}
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c导出失败：" + e.getMessage()));
			return 0;
		}
	}

	// ---------------------------------------------------------------- 路径

	/** 空 → {@code <数据根>/export/<kind>-<stamp>.csv}；相对路径 → 同样落在 export/ 下；绝对路径原样用。 */
	static Path target(String file, String kind) {
		Path base = DataRoot.path().resolve("export");
		if (file == null || file.isBlank()) {
			return base.resolve(kind + "-" + java.time.LocalDateTime.now().format(STAMP) + ".csv");
		}
		String raw = file.trim().replace('\\', '/');
		Path p = Path.of(raw);
		if (!p.isAbsolute()) p = base.resolve(raw);
		String name = p.getFileName() == null ? "" : p.getFileName().toString();
		if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
			p = p.resolveSibling(name + ".csv");
		}
		return p;
	}
}
