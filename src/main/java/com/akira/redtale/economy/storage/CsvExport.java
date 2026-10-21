package com.akira.redtale.economy.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * T14：把 mod 自己的数据导成 CSV（流水 / 商品 / 玩家资产）。
 *
 * <p>三条设计约束，都是踩过坑之后的结论：
 * <ul>
 *   <li><b>UTF-8 + BOM</b>：中文 Windows 的 Excel 默认按本地代码页开 CSV，无 BOM 会把中文读成乱码。
 *       带 BOM 的副作用是「用脚本按第一列名取字段」时要先剥 BOM，所以 {@link #header} 单独给出。</li>
 *   <li><b>RFC 4180 转义</b>：商品名/备注里出现逗号、双引号、换行都必须包引号并双写引号，
 *       否则一行会被拆成多列（手工拼 {@code String.join(",")} 必踩）。</li>
 *   <li><b>原子写</b>：先写 {@code .tmp} 再 rename。导出目标常在存档目录里，
 *       半截 CSV 比没有更糟（下次导入时会把半行当数据）。</li>
 * </ul>
 *
 * <p>本类刻意不引用任何 Minecraft 类型，也不读静态配置 —— 路径与区间都由调用方传入，
 * 这样纯函数部分可以脱离游戏直接跑（禁构建轮的唯一验证手段）。
 */
public final class CsvExport {

	public static final String[] LEDGER_COLUMNS = {
			"ts", "uuid", "delta", "after", "type", "ref", "server"};
	public static final String[] ITEM_COLUMNS = {
			"shop", "shop_name", "item_id", "category", "buy", "sell",
			"stock_init", "daily_limit", "enabled", "name", "file"};
	public static final String[] ASSET_COLUMNS = {"uuid", "balance", "updatedAt", "file"};

	/** 一次导出的统计；{@code skipped} 是解析失败被跳过的条目（不是静默丢弃，调用方要显示出来）。 */
	public record Stats(int rows, int skipped) {
	}

	private CsvExport() {
	}

	// ---------------------------------------------------------------- 纯函数

	/**
	 * 单元格转义。空值写空串（不是 {@code null}，Excel 会把字面 "null" 当内容）。
	 */
	public static String escape(String v) {
		if (v == null) return "";
		boolean needQuote = false;
		for (int i = 0; i < v.length(); i++) {
			char c = v.charAt(i);
			if (c == ',' || c == '"' || c == '\n' || c == '\r') {
				needQuote = true;
				break;
			}
		}
		if (!needQuote) return v;
		return '"' + v.replace("\"", "\"\"") + '"';
	}

	public static String row(String... cells) {
		StringBuilder sb = new StringBuilder(cells.length * 8);
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) sb.append(',');
			sb.append(escape(cells[i]));
		}
		return sb.toString();
	}

	public static String row(List<String> cells) {
		return row(cells.toArray(new String[0]));
	}

	public static String header(String[] columns) {
		return row(columns);
	}

	/** 流水一行；字段按 {@link #LEDGER_COLUMNS} 顺序，缺字段留空而不是写 "0"。 */
	public static String ledgerRow(JsonObject o) {
		return row(
				str(o, "ts"),
				str(o, "uuid"),
				str(o, "delta"),
				str(o, "after"),
				str(o, "type"),
				str(o, "ref"),
				str(o, "server"));
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) return "";
		// 数字/布尔都按原文输出，别用 getAsString 对 JsonNull 抛异常
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	/** 解析 {@code yyyy-MM-dd}；非法返回 {@code fallback}（命令层再报错，不在这里抛）。 */
	public static LocalDate parseDate(String s, LocalDate fallback) {
		if (s == null || s.isBlank()) return fallback;
		try {
			return LocalDate.parse(s.trim());
		} catch (DateTimeParseException e) {
			return fallback;
		}
	}

	/**
	 * 区间内的流水文件（按时间升序，monthly 粒度自动去重）。
	 *
	 * <p>用 {@link Ledger#fileFor(LocalDate, String, Path)} 生成路径 —— 与写入侧共用同一套命名规则，
	 * 避免「导出扫不到自己写的文件」（粒度/目录任一处不一致就会静默 0 行）。
	 * 上限 3660 天，防止有人敲错年份把整盘扫一遍。
	 */
	public static List<Path> ledgerFiles(Path dir, String granularity, LocalDate from, LocalDate to) {
		List<Path> out = new ArrayList<>();
		if (dir == null || from == null || to == null) return out;
		LocalDate start = from.isAfter(to) ? to : from;
		LocalDate end = from.isAfter(to) ? from : to;
		if (start.plusDays(3660).isBefore(end)) end = start.plusDays(3660);
		Set<String> seen = new LinkedHashSet<>();
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			Path f = Ledger.fileFor(d, granularity, dir);
			if (f == null) continue;
			if (seen.add(f.toAbsolutePath().toString()) && Files.isRegularFile(f)) {
				out.add(f);
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- 导出

	/** 流水 → CSV。返回写出的行数与被跳过的坏行数。 */
	public static Stats exportLedger(Path dir, String granularity, LocalDate from, LocalDate to, Path out)
			throws IOException {
		List<String> rows = new ArrayList<>();
		int skipped = 0;
		for (Path f : ledgerFiles(dir, granularity, from, to)) {
			List<String> lines;
			try {
				lines = Files.readAllLines(f, StandardCharsets.UTF_8);
			} catch (IOException e) {
				skipped++;
				continue;
			}
			for (String line : lines) {
				if (line == null || line.isBlank()) continue;
				try {
					JsonElement e = JsonParser.parseString(line);
					if (!e.isJsonObject()) {
						skipped++;
						continue;
					}
					rows.add(ledgerRow(e.getAsJsonObject()));
				} catch (RuntimeException bad) {
					skipped++;
				}
			}
		}
		write(out, LEDGER_COLUMNS, rows);
		return new Stats(rows.size(), skipped);
	}

	/**
	 * 玩家资产 → CSV：扫账号目录下的 {@code <uuid>.json}。
	 *
	 * <p>只读盘、不读 {@code InternalProvider} 的内存缓存 —— 导出是「对账」用途，
	 * 要的是已落盘的事实；内存里未 flush 的余额由调用方先 {@code flushAll()}。
	 */
	public static Stats exportAssets(Path accountDir, Path out) throws IOException {
		List<String> rows = new ArrayList<>();
		int skipped = 0;
		if (accountDir != null && Files.isDirectory(accountDir)) {
			List<Path> files = new ArrayList<>();
			try (Stream<Path> s = Files.list(accountDir)) {
				s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
			}
			files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
			for (Path f : files) {
				try {
					String text = Files.readString(f, StandardCharsets.UTF_8);
					JsonElement e = JsonParser.parseString(text);
					if (!e.isJsonObject()) {
						skipped++;
						continue;
					}
					JsonObject o = e.getAsJsonObject();
					rows.add(row(
							str(o, "uuid"),
							str(o, "balance"),
							str(o, "updatedAt"),
							f.getFileName().toString()));
				} catch (IOException | RuntimeException bad) {
					skipped++;
				}
			}
		}
		write(out, ASSET_COLUMNS, rows);
		return new Stats(rows.size(), skipped);
	}

	/** 已经拼好的行（商品表由调用方从 ShopCatalog 取，避免本类依赖商店层）。 */
	public static Stats writeTable(Path out, String[] columns, List<String> rows) throws IOException {
		write(out, columns, rows);
		return new Stats(rows.size(), 0);
	}

	/** UTF-8 + BOM 原子写。父目录不存在会自动建。 */
	public static void write(Path out, String[] columns, List<String> rows) throws IOException {
		if (out == null) throw new IOException("输出路径为空");
		Path parent = out.toAbsolutePath().getParent();
		if (parent != null) Files.createDirectories(parent);
		StringBuilder sb = new StringBuilder(1024 + rows.size() * 64);
		sb.append('\uFEFF'); // BOM：让 Excel 按 UTF-8 打开
		sb.append(header(columns)).append('\n');
		for (String r : rows) sb.append(r).append('\n');
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
		try {
			Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
