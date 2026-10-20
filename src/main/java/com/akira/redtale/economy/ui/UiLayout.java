package com.akira.redtale.economy.ui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/** 一份布局文件（R15）。对应 spec §9.2 的顶层结构。 */
public final class UiLayout {

	private static final Gson GSON = new Gson();

	public final String id;
	public final int width;
	public final int height;
	public final String background;
	public final String title;
	/** 音效档（R39）：vanilla=原版按钮音，silent=静音。可被布局覆盖。 */
	public final String sound;
	public List<UiNode> children;
	/** 原始 JSON（R17 写回时保留未知字段与顺序）。 */
	public final JsonObject raw;

	private UiLayout(String id, JsonObject o) {
		this.id = id;
		this.raw = o;
		this.width = Math.max(64, intOf(o, "width", 320));
		this.height = Math.max(48, intOf(o, "height", 220));
		this.background = strOf(o, "background", "");
		this.title = strOf(o, "title", "");
		this.sound = strOf(o, "sound", "vanilla");
		this.children = o.has("children") && o.get("children").isJsonArray()
				? new java.util.ArrayList<>(UiNode.parseList(o.getAsJsonArray("children")))
				: new java.util.ArrayList<>();
	}

	public static UiLayout parse(String id, String json) {
		JsonElement el = GSON.fromJson(json, JsonElement.class);
		if (el == null || !el.isJsonObject()) {
			throw new IllegalArgumentException("布局顶层必须是 JSON 对象：" + id);
		}
		return new UiLayout(id, el.getAsJsonObject());
	}

	/** 布局里声明的数据源（渲染器据此决定要不要请求数据）。 */
	public List<String> sources() {
		List<String> out = new java.util.ArrayList<>();
		collect(children, out);
		return out;
	}

	private static void collect(List<UiNode> nodes, List<String> out) {
		for (UiNode n : nodes) {
			if (!n.source.isBlank() && !out.contains(n.source)) out.add(n.source);
			collect(n.children, out);
		}
	}

	/** 把当前（可能被拖拽改过的）节点树写回 JSON 文本。 */
	public String toJson() {
		JsonObject out = raw.deepCopy();
		out.addProperty("width", width);
		out.addProperty("height", height);
		if (!title.isBlank()) out.addProperty("title", title);
		if (!background.isBlank()) out.addProperty("background", background);
		if (!"vanilla".equals(sound)) out.addProperty("sound", sound);
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		children.forEach(n -> arr.add(n.raw));
		out.add("children", arr);
		return GSON_PRETTY.toJson(out);
	}

	private static final com.google.gson.Gson GSON_PRETTY =
			new com.google.gson.GsonBuilder().setPrettyPrinting().create();

	private static String strOf(JsonObject o, String k, String def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
	}

	private static int intOf(JsonObject o, String k, int def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : def;
	}
}
