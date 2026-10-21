package com.akira.redtale.economy.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * T07 经济指标：把流水 jsonl 汇总成「产出 / 消耗 / 净增 / 通胀」。
 *
 * <p>口径（写死并有单测，避免每次换算法）：
 * <ul>
 *   <li><b>产出 minted</b> = 所有 {@code delta>0} 之和；</li>
 *   <li><b>消耗 burned</b> = 所有 {@code delta<0} 的绝对值之和；</li>
 *   <li><b>净增 net</b> = minted - burned —— 等于流通总量的变化（玩家间转账天然抵消）；</li>
 *   <li><b>通胀率</b> = net / 当前流通总量；总量未知（Numismatic 外部账本）时返回 NaN，界面显示「—」。</li>
 * </ul>
 *
 * <p>读的是**已落盘**的流水文件，异步写队列里还没刷盘的几笔不在统计内 —— 这是有意的：
 * 指标是趋势判断，不需要精确到最后一毫秒，但必须能离线复算（所以全部参数化，可单测）。
 */
public final class EconomyMetrics {

	/** 一段时间的汇总。{@code byType} 已按金额降序。 */
	public record Totals(long minted, long burned, int tx, int skipped,
			List<Map.Entry<String, Long>> mintedByType,
			List<Map.Entry<String, Long>> burnedByType) {

		public long net() {
			return minted - burned;
		}

		public static Totals empty() {
			return new Totals(0, 0, 0, 0, List.of(), List.of());
		}
	}

	private EconomyMetrics() {
	}

	/** 该窗口覆盖的流水文件（按时间升序、去重）。 */
	public static List<Path> filesFor(Path ledgerRoot, String granularity, LocalDate from, LocalDate to) {
		List<Path> out = new ArrayList<>();
		if (ledgerRoot == null || from == null || to == null) return out;
		LocalDate end = to.isBefore(from) ? from : to;
		TreeMap<String, Path> uniq = new TreeMap<>();
		for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
			Path f = Ledger.fileFor(d, granularity == null ? "daily" : granularity, ledgerRoot);
			if (f != null && Files.isRegularFile(f)) uniq.put(f.toString(), f);
		}
		out.addAll(uniq.values());
		return out;
	}

	/** 汇总若干流水文件；坏行只计数不抛（半行 = 进程被杀，属正常现象）。 */
	public static Totals scan(List<Path> files) {
		long minted = 0;
		long burned = 0;
		int tx = 0;
		int skipped = 0;
		Map<String, Long> in = new LinkedHashMap<>();
		Map<String, Long> outMap = new LinkedHashMap<>();
		if (files != null) {
			for (Path f : files) {
				List<String> lines;
				try {
					lines = Files.readAllLines(f, StandardCharsets.UTF_8);
				} catch (Exception e) {
					skipped++;
					continue;
				}
				for (String line : lines) {
					if (line == null || line.isBlank()) continue;
					try {
						JsonObject o = JsonParser.parseString(line).getAsJsonObject();
						long delta = o.has("delta") ? o.get("delta").getAsLong() : 0;
						String type = o.has("type") && o.get("type").isJsonPrimitive()
								? o.get("type").getAsString() : "unknown";
						tx++;
						if (delta > 0) {
							minted += delta;
							in.merge(type, delta, Long::sum);
						} else if (delta < 0) {
							burned += -delta;
							outMap.merge(type, -delta, Long::sum);
						}
					} catch (Exception bad) {
						skipped++;
					}
				}
			}
		}
		return new Totals(minted, burned, tx, skipped, sorted(in), sorted(outMap));
	}

	/** 最近 {@code days} 天（含今天）的汇总。 */
	public static Totals window(Path ledgerRoot, String granularity, LocalDate today, int days) {
		int n = Math.max(1, days);
		return scan(filesFor(ledgerRoot, granularity, today.minusDays(n - 1L), today));
	}

	/** 流通总量：账户目录里所有 {@code balance} 之和。 */
	public static long totalBalance(Path accountDir) {
		if (accountDir == null || !Files.isDirectory(accountDir)) return 0L;
		long sum = 0;
		try (var s = Files.list(accountDir)) {
			for (Path f : s.toList()) {
				String name = f.getFileName().toString();
				if (!name.endsWith(".json") || name.startsWith("_")) continue;
				try {
					JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
							.getAsJsonObject();
					if (o.has("balance")) sum += Math.max(0, o.get("balance").getAsLong());
				} catch (Exception ignored) {
					// 单个坏档不影响总量估算
				}
			}
		} catch (Exception e) {
			return sum;
		}
		return sum;
	}

	/** 通胀率（net / 总量）。总量为 0 或未知时返回 {@link Double#NaN}。 */
	public static double inflation(Totals t, long total) {
		if (t == null || total <= 0) return Double.NaN;
		return t.net() / (double) total;
	}

	/** 百分比显示（NaN → "—"）。 */
	public static String pct(double v) {
		if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
		return String.format(java.util.Locale.ROOT, "%+.2f%%", v * 100.0);
	}

	/** Top N 类型（金额降序，已排序列表直接截断）。 */
	public static List<Map.Entry<String, Long>> top(List<Map.Entry<String, Long>> list, int n) {
		if (list == null || list.isEmpty()) return List.of();
		return list.subList(0, Math.min(Math.max(1, n), list.size()));
	}

	/** 一行式摘要（命令与日志共用）。 */
	public static String line(String label, Totals t, long total) {
		StringBuilder sb = new StringBuilder();
		sb.append(label).append("：产出 +").append(t.minted())
				.append(" / 消耗 -").append(t.burned())
				.append(" / 净 ").append(t.net() >= 0 ? "+" : "").append(t.net())
				.append("（").append(pct(inflation(t, total))).append("）");
		return sb.toString();
	}

	private static List<Map.Entry<String, Long>> sorted(Map<String, Long> m) {
		List<Map.Entry<String, Long>> list = new ArrayList<>(m.entrySet());
		list.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
				.thenComparing(Map.Entry::getKey));
		return list;
	}
}
