package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * T28 管理员控制台协议。
 *
 * <p>刻意做成「指标列表」而不是一堆固定字段：控制台要展示的东西会随版本增长
 * （服务器 → 商店 → 市场 → 流水 → 配置 → 回收站 → 备份），每加一项都改协议字段
 * 会让「客户端/服务端必须同版本」这条约束变成日常事故。
 *
 * <p>代价是客户端不能对字段做语义判断 —— 所以 value 一律是<b>已格式化的字符串</b>，
 * 客户端只负责排版。任何需要「点击数值去做点什么」的交互都必须另开包，不要解析这里的字符串。
 *
 * <p>权限：请求包不带任何参数，服务端按 {@code Permissions.NODE_ADMIN} 判定；
 * 非管理员拿到的是一份空快照 + {@code admin=false}（界面显示「需要管理员权限」），
 * 不报错也不踢人 —— 伪造请求包最优结果也只是空页面。
 */
public final class AdminProtocol {

	/** 一行指标。{@code hint} 可以为空串（界面对空 hint 不留空隙）。 */
	public record Metric(String label, String value, String hint) {
	}

	/**
	 * 快照。
	 *
	 * @param generatedAt 服务端生成时间（毫秒），客户端用「x 秒前」显示，判断数据是否新鲜
	 * @param admin       服务端判定的管理员标志（客户端不自己算权限）
	 * @param metrics     指标列表，顺序即显示顺序（服务端决定分组顺序）
	 */
	public record Snapshot(long generatedAt, boolean admin, List<Metric> metrics) {

		public static Snapshot empty() {
			return new Snapshot(0, false, List.of());
		}
	}

	/** 单条指标上限：控制台永远不该有几十行，超限只截内存、不影响后续字段。 */
	private static final int MAX_METRICS = 64;

	/**
	 * 字段上限（<b>字符数</b>，不是字节）。
	 *
	 * <p>★写端必须先截断★：{@code PacketByteBuf.writeString(s, max)} 超限直接抛
	 * {@code EncoderException: String too big}，而且是在<b>服务端下发时</b>炸 ——
	 * 界面表现是「一直空着」，日志只有一行下发失败（2026-10-22 首次实机截图抓到的：
	 * 数据根路径 63 字符 &gt; 原上限 48）。
	 */
	private static final int MAX_LABEL = 48;
	private static final int MAX_VALUE = 128;
	private static final int MAX_HINT = 160;

	private static final Codec<Metric> METRIC = Codec.of("admin/metric",
			(buf, m) -> {
				buf.writeString(trunc(m.label(), MAX_LABEL), MAX_LABEL);
				buf.writeString(trunc(m.value(), MAX_VALUE), MAX_VALUE);
				buf.writeString(trunc(m.hint(), MAX_HINT), MAX_HINT);
			},
			buf -> new Metric(buf.readString(MAX_LABEL), buf.readString(MAX_VALUE),
					buf.readString(MAX_HINT)));

	private static String trunc(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max);
	}

	private AdminProtocol() {
	}

	// ------------------------------------------------------------ 请求

	/**
	 * 请求：只剩一个版本哨兵，没有任何可篡改的参数。
	 *
	 * <p>写成 record 而不是 void，是为了能进 {@code CodecRoundTripTest} ——
	 * 请求包一样会漂移（读端少读/多读一个字节就整包错位），不能因为「它只有一个字节」就不测。
	 */
	public record Query(int version) {
		public static Query current() {
			return new Query(1);
		}
	}

	public static void writeQuery(PacketByteBuf buf, Query q) {
		buf.writeByte(q.version());
	}

	public static Query readQuery(PacketByteBuf buf) {
		return new Query(buf.readByte());
	}

	// ------------------------------------------------------------ 快照

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeLong(s.generatedAt());
		buf.writeBoolean(s.admin());
		Codecs.writeList(buf, s.metrics(), METRIC);
	}

	public static Snapshot read(PacketByteBuf buf) {
		long at = buf.readLong();
		boolean admin = buf.readBoolean();
		return new Snapshot(at, admin, Codecs.readList(buf, MAX_METRICS, METRIC));
	}
}
