package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 单值编解码器（T33）。
 *
 * <p>存在的唯一理由：<b>把「写」和「读」钉在同一个对象上</b>。以前每个协议各写一对
 * 静态 {@code write}/{@code read}，字段顺序靠人记，改一处忘另一处就联机断连
 * （已踩过 3 次）。现在列表元素、字符串这类会重复出现的字段统一走 {@link Codecs}，
 * 元素级 codec 自带 id，出错时日志能指出是哪一类。
 *
 * @param <T> 值类型
 */
public interface Codec<T> {

	/** 写入一个值。 */
	void write(PacketByteBuf buf, T value);

	/** 读出一个值。必须与 {@link #write} 的字段顺序严格一致。 */
	T read(PacketByteBuf buf);

	/** 稳定标识，仅用于日志/测试报错。 */
	String id();

	static <T> Codec<T> of(String id, BiConsumer<PacketByteBuf, T> writer,
			Function<PacketByteBuf, T> reader) {
		return new Codec<>() {
			@Override
			public void write(PacketByteBuf buf, T value) {
				writer.accept(buf, value);
			}

			@Override
			public T read(PacketByteBuf buf) {
				return reader.apply(buf);
			}

			@Override
			public String id() {
				return id;
			}

			@Override
			public String toString() {
				return "Codec[" + id + "]";
			}
		};
	}
}
