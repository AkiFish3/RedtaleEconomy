package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 协议编解码的公共构件（T33）。
 *
 * <h2>为什么要有 {@link #readList}</h2>
 * 老写法是 {@code int n = Math.min(buf.readVarInt(), 64); for (i < n) ...}，看起来是防御，
 * 实际是个<b>流错位</b>：写端写了 {@code n} 条，读端只读 64 条就跑去读后面的字段，
 * 于是「第 65 条」被当成下一页的 page 号，之后整包全是垃圾值——不抛异常，只是界面数据全错。
 *
 * <p>正确做法只有一种：<b>全部读完，只保留前 max 条</b>。截断是内存保护，不是读取边界。
 * 本类只提供这一种写法，协议里不再手写循环条件。
 */
public final class Codecs {

	private Codecs() {
	}

	/** 变长 int —— 列表里最常用的元素类型（槽位号、档位、里程碑）。 */
	public static final Codec<Integer> VAR_INT = Codec.of("varint",
			PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt);

	/** 定长字符串（null 归一为空串，写端读端同一个上限）。 */
	public static Codec<String> string(int max) {
		final int cap = Math.max(1, Math.min(max, 32767));
		return Codec.of("str" + cap,
				(buf, v) -> buf.writeString(v == null ? "" : v, cap),
				buf -> buf.readString(cap));
	}

	/** 写列表：先写长度，再逐元素。 */
	public static <T> void writeList(PacketByteBuf buf, List<T> list, Codec<T> codec) {
		if (list == null) {
			buf.writeVarInt(0);
			return;
		}
		buf.writeVarInt(list.size());
		for (T v : list) {
			codec.write(buf, v);
		}
	}

	/**
	 * 读列表：<b>读完所有元素</b>，只保留前 {@code max} 条。
	 *
	 * <p>{@code max} 违规不会让流错位——这是它和 {@code Math.min} 写法的唯一区别，
	 * 也是这条 API 存在的全部意义。
	 */
	public static <T> List<T> readList(PacketByteBuf buf, int max, Codec<T> codec) {
		int n = buf.readVarInt();
		if (n < 0) {
			// 负数长度是坏包：不读任何元素，也不让 n 变成 ArrayList 的负容量
			throw new IllegalArgumentException("negative list length " + n + " for " + codec.id());
		}
		int keep = Math.max(0, max);
		List<T> out = new ArrayList<>(Math.min(n, keep));
		for (int i = 0; i < n; i++) {
			T v = codec.read(buf);
			if (i < keep) {
				out.add(v);
			}
		}
		return out;
	}
}
