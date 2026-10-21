package com.akira.redtale.economy.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * T16 流水查询：把 append-only 的 jsonl 读成「按时间倒序的一页条目」，供 GUI 展示。
 *
 * <p>与 {@link EconomyMetrics} 的分工：那个只做聚合（只要 sum），这个要**逐笔明细**，
 * 所以保留 ts / uuid / type / delta / after / ref 六个字段。
 *
 * <p>三个筛选口径（写死在这里，别在界面和网络层各写一套）：
 * <ul>
 *   <li><b>时间</b>：{@code days} 天窗口（含今天），实际文件由 {@link EconomyMetrics#filesFor} 决定；</li>
 *   <li><b>方向</b>：0 全部 / 1 收入（delta&gt;0）/ 2 支出（delta&lt;0）；</li>
 *   <li><b>类型</b>：见 {@link #TYPE_FILTERS}，逐项是<b>前缀</b>匹配，最后一项是「其它」（不命中前几项）。</li>
 * </ul>
 *
 * <p>返回「匹配总数 + 前 {@code limit} 条」：界面要显示「共 N 笔」，而带宽只发一页。
 */
public final class LedgerQuery {

	/** 界面可选的时间窗天数档位（界面按这个顺序画 chip）。 */
	public static final int[] DAYS = {1, 7, 30};

	/**
	 * 类型分桶：{@code ""} = 全部，其余为前缀；{@code "!"} = 「其它」（不在前几桶里的都算）。
	 *
	 * <p>顺序固定，协议里传的是下标，改顺序等于改协议。
	 */
	public static final String[] TYPE_FILTERS = {"", "shop", "market", "task", "!"};

	/** 一页最多带回多少条（协议侧还有一层上限，这里先截）。 */
	public static final int MAX_ROWS = 64;

	/** 单笔流水。{@code uuid} 留空表示该行没有 uuid 字段（老数据）。 */
	public record Row(String ts, String uuid, String type, long delta, long after, String ref) {
	}

	/** 查询结果：匹配总数（截断前）+ 已截断的一页。 */
	public record Result(int total, List<Row> rows) {
		public static Result empty() {
			return new Result(0, List.of());
		}
	}

	private LedgerQuery() {
	}

	/** 天数档位下标 → 实际天数（越界按下标 0）。 */
	public static int daysOf(int idx) {
		if (idx < 0 || idx >= DAYS.length) return DAYS[0];
		return DAYS[idx];
	}

	/**
	 * 查一页流水。
	 *
	 * @param root        ledger 目录（{@code StorageConfig.ledgerDir()} 解析后的绝对路径）
	 * @param granularity daily / monthly
	 * @param today       业务日（不只是 LocalDate.now，要考虑重置小时）
	 * @param days        时间窗天数（含今天）
	 * @param dir         0 全部 / 1 收入 / 2 支出
	 * @param typeIdx     {@link #TYPE_FILTERS} 下标
	 * @param who         只查这个玩家；{@code null} = 全部玩家
	 * @param limit       最多返回多少条
	 */
	public static Result query(Path root, String granularity, LocalDate today, int days,
			int dir, int typeIdx, UUID who, int limit) {
		if (root == null || today == null) return Result.empty();
		int n = Math.max(1, days);
		List<Path> files = EconomyMetrics.filesFor(root, granularity, today.minusDays(n - 1L), today);
		String me = who == null ? "" : who.toString();
		String wanted = TYPE_FILTERS[Math.max(0, Math.min(TYPE_FILTERS.length - 1, typeIdx))];
		int cap = Math.max(1, Math.min(MAX_ROWS, limit));
		List<Row> all = new ArrayList<>();
		int total = 0;
		for (Path f : files) {
			List<String> lines;
			try {
				lines = Files.readAllLines(f, StandardCharsets.UTF_8);
			} catch (Exception e) {
				continue; // 文件读不了（正在轮转 / 权限）就跳过，界面显示空而不是崩
			}
			for (String line : lines) {
				if (line == null || line.isBlank()) continue;
				Row r;
				try {
					JsonObject o = JsonParser.parseString(line).getAsJsonObject();
					String uuid = o.has("uuid") && o.get("uuid").isJsonPrimitive()
							? o.get("uuid").getAsString() : "";
					if (!me.isEmpty() && !me.equals(uuid)) continue;
					long delta = o.has("delta") ? o.get("delta").getAsLong() : 0L;
					if (dir == 1 && delta <= 0) continue;
					if (dir == 2 && delta >= 0) continue;
					String type = o.has("type") && o.get("type").isJsonPrimitive()
							? o.get("type").getAsString() : "unknown";
					if (!matchType(type, wanted)) continue;
					r = new Row(o.has("ts") && o.get("ts").isJsonPrimitive()
							? o.get("ts").getAsString() : "",
							uuid, type, delta,
							o.has("after") ? o.get("after").getAsLong() : 0L,
							o.has("ref") && o.get("ref").isJsonPrimitive() ? o.get("ref").getAsString() : "");
				} catch (Exception bad) {
					continue; // 半行（进程被杀）正常现象，不抛
				}
				total++;
				all.add(r);
			}
		}
		// ts 是 ISO 定长字符串，字典序 == 时间序；同秒按写入顺序（列表本身有序）稳定
		all.sort(Comparator.comparing(Row::ts).reversed());
		if (all.size() > cap) all = new ArrayList<>(all.subList(0, cap));
		return new Result(total, all);
	}

	/** 类型分桶匹配；{@code "!"} 表示「不在前面几桶里的」。 */
	public static boolean matchType(String type, String filter) {
		String t = type == null ? "" : type;
		if (filter == null || filter.isEmpty()) return true;
		if ("!".equals(filter)) {
			for (String f : TYPE_FILTERS) {
				if (f.isEmpty() || "!".equals(f)) continue;
				if (t.equals(f) || t.startsWith(f + ".")) return false;
			}
			return true;
		}
		return t.equals(filter) || t.startsWith(filter + ".");
	}
}
