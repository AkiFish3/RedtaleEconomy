package com.akira.redtale.economy.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 数据驱动 UI 的节点（R15 / spec v0.9 §9.2）。
 *
 * <p>解析对坏数据一律<b>宽容降级</b>：未知 type ⇒ {@link Type#UNKNOWN}（不渲染、不报错），
 * 越界数字钳到合理范围。理由：布局文件是服主手改的，写错一个字段不该让整个界面消失。
 */
public final class UiNode {

	public enum Type {
		LABEL, BUTTON, LIST, GRID, SLOT_GRID, PROGRESS, IMAGE, TAB, UNKNOWN;

		static Type of(String raw) {
			if (raw == null) return UNKNOWN;
			return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
				case "label" -> LABEL;
				case "button" -> BUTTON;
				case "list" -> LIST;
				case "grid" -> GRID;
				case "slot-grid", "slotgrid", "slots" -> SLOT_GRID;
				case "progress" -> PROGRESS;
				case "image" -> IMAGE;
				case "tab" -> TAB;
				default -> UNKNOWN;
			};
		}
	}

	public final Type type;
	public final int x;
	public final int y;
	public final int w;
	public final int h;
	public final String text;
	public final String source;
	public final String action;
	public final String color;
	public final String image;
	public final String tooltip;
	public final double value;
	public final double max;
	public final int columns;
	public final int rowHeight;
	public final String align;
	public final List<UiNode> children;
	/** 原始 JSON（R17 拖拽编辑器直接改这份再写回，避免丢字段）。 */
	public final JsonObject raw;

	private UiNode(JsonObject o) {
		this.raw = o == null ? new JsonObject() : o;
		this.type = Type.of(str(o, "type", null));
		this.x = clamp(i(o, "x", 0), -4096, 4096);
		this.y = clamp(i(o, "y", 0), -4096, 4096);
		this.w = Math.max(0, i(o, "w", 0));
		this.h = Math.max(0, i(o, "h", 0));
		this.text = str(o, "text", "");
		this.source = str(o, "source", "");
		this.action = str(o, "action", "");
		this.color = str(o, "color", "");
		this.image = str(o, "image", "");
		this.tooltip = str(o, "tooltip", "");
		this.value = d(o, "value", 0);
		this.max = d(o, "max", 1);
		this.columns = Math.max(1, i(o, "columns", 1));
		this.rowHeight = Math.max(8, i(o, "rowHeight", 12));
		this.align = str(o, "align", "left");
		this.children = parseList(o.has("children") && o.get("children").isJsonArray()
				? o.getAsJsonArray("children") : null);
	}

	public static UiNode parse(JsonObject o) {
		return new UiNode(o == null ? new JsonObject() : o);
	}

	public static List<UiNode> parseList(JsonArray arr) {
		if (arr == null) return Collections.emptyList();
		List<UiNode> out = new ArrayList<>(arr.size());
		for (JsonElement e : arr) {
			if (e != null && e.isJsonObject()) out.add(new UiNode(e.getAsJsonObject()));
		}
		return Collections.unmodifiableList(out);
	}

	/** 命中测试（含子节点，后声明的盖在上面 ⇒ 倒序找）。 */
	public UiNode hit(int mx, int my, int ox, int oy) {
		int ax = ox + x;
		int ay = oy + y;
		for (int i = children.size() - 1; i >= 0; i--) {
			UiNode hitChild = children.get(i).hit(mx, my, ax, ay);
			if (hitChild != null) return hitChild;
		}
		if (type == Type.BUTTON || type == Type.TAB) {
			if (mx >= ax && mx < ax + w && my >= ay && my < ay + h) return this;
		}
		return null;
	}

	private static String str(JsonObject o, String k, String def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
	}

	private static int i(JsonObject o, String k, int def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : def;
	}

	private static double d(JsonObject o, String k, double def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : def;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
