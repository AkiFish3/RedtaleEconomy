package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 可视化配置编辑器的线格式（R14 V1）。
 *
 * <p>只传「标量字段」：path / 类型 / 当前值。数组与对象保持只读（V1 不做增删元素）。
 * 客户端拿到的只是文本，不参与任何校验；保存时服务端逐条重解析 + schema 校验。
 */
public final class ConfigProtocol {

	public record Entry(String path, String type, String value) {
	}

	private ConfigProtocol() {
	}

	public static void writeEntries(PacketByteBuf buf, String file, List<Entry> entries) {
		buf.writeString(file == null ? "" : file, 128);
		buf.writeVarInt(entries.size());
		for (Entry e : entries) {
			buf.writeString(e.path(), 256);
			buf.writeString(e.type(), 16);
			buf.writeString(e.value() == null ? "" : e.value(), 512);
		}
	}

	public static void readEntries(PacketByteBuf buf, String[] fileOut, List<Entry> out) {
		fileOut[0] = buf.readString(128);
		int n = buf.readVarInt();
		int max = Math.min(n, 2000);
		for (int i = 0; i < n; i++) {
			String path = buf.readString(256);
			String type = buf.readString(16);
			String value = buf.readString(512);
			if (i < max) out.add(new Entry(path, type, value));
		}
	}

	public static void writeSave(PacketByteBuf buf, String file, List<Entry> entries) {
		writeEntries(buf, file, entries);
	}

	public static List<Entry> readSave(PacketByteBuf buf, String[] fileOut) {
		List<Entry> list = new ArrayList<>();
		readEntries(buf, fileOut, list);
		return list;
	}
}
