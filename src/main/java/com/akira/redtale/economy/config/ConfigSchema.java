package com.akira.redtale.economy.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 声明式配置 schema。
 *
 * <p>作用有三：
 * <ol>
 *   <li>首次启动生成默认文件（含 {@code _readme} 说明 + 每层 OBJECT 的 {@code _comment}）</li>
 *   <li>加载时补齐缺失字段、纠正错误类型、把越界数值钳进 [min,max] 并记日志</li>
 *   <li>保存前做同一套校验，坏值写不进磁盘</li>
 * </ol>
 *
 * <p>设计总则要求「任何数值都必须能进 config 且带说明与范围校验」，
 * 所以所有配置都从这里声明，Java 侧不允许出现没登记的常量。
 */
public final class ConfigSchema {

	public enum Kind { BOOL, INT, DOUBLE, STRING, STRING_LIST, OBJECT, LIST, RAW_LIST }

	private final Kind kind;
	private String comment = "";
	private Object def;
	private long min = Long.MIN_VALUE;
	private long max = Long.MAX_VALUE;
	private double dmin = -Double.MAX_VALUE;
	private double dmax = Double.MAX_VALUE;
	private List<String> allowed;
	private final LinkedHashMap<String, ConfigSchema> children = new LinkedHashMap<>();
	private ConfigSchema element;
	private JsonArray listDefault;

	private ConfigSchema(Kind kind) {
		this.kind = kind;
	}

	// ---------------------------------------------------------------- 工厂

	public static ConfigSchema bool(boolean def, String comment) {
		ConfigSchema s = new ConfigSchema(Kind.BOOL);
		s.def = def;
		s.comment = comment;
		return s;
	}

	public static ConfigSchema integer(long def, long min, long max, String comment) {
		ConfigSchema s = new ConfigSchema(Kind.INT);
		s.def = def;
		s.min = min;
		s.max = max;
		s.comment = comment;
		return s;
	}

	public static ConfigSchema decimal(double def, double min, double max, String comment) {
		ConfigSchema s = new ConfigSchema(Kind.DOUBLE);
		s.def = def;
		s.dmin = min;
		s.dmax = max;
		s.comment = comment;
		return s;
	}

	public static ConfigSchema string(String def, String comment) {
		ConfigSchema s = new ConfigSchema(Kind.STRING);
		s.def = def;
		s.comment = comment;
		return s;
	}

	/** 枚举值；值不在 allowed 内会回落默认值。 */
	public static ConfigSchema choice(String def, List<String> allowed, String comment) {
		ConfigSchema s = new ConfigSchema(Kind.STRING);
		s.def = def;
		s.allowed = new ArrayList<>(allowed);
		s.comment = comment + "（可选：" + String.join(" | ", allowed) + "）";
		return s;
	}

	public static ConfigSchema stringList(List<String> def, String comment) {
		ConfigSchema s = new ConfigSchema(Kind.STRING_LIST);
		s.def = new ArrayList<>(def);
		s.comment = comment;
		return s;
	}

	/**
	 * 对象数组，例如 checkin 的 tiers、tasks 的 pools 条目。
	 *
	 * @param def     默认数组（可为空数组，表示「运营自己填」）
	 * @param element 每个元素的字段 schema
	 */
	public static ConfigSchema list(JsonArray def, ConfigSchema element, String comment) {
		ConfigSchema s = new ConfigSchema(Kind.LIST);
		s.listDefault = def == null ? new JsonArray() : def;
		s.element = element;
		s.comment = comment;
		return s;
	}

	/**
	 * 原样保留的对象数组：只校验「是数组」，元素结构由模块自己解析。
	 * 用于结构会演进、不值得为它写 schema 的表（如签到档位 days/reward）。
	 */
	public static ConfigSchema rawList(JsonArray def, String comment) {
		ConfigSchema s = new ConfigSchema(Kind.RAW_LIST);
		s.listDefault = def;
		s.comment = comment;
		return s;
	}

	public static ConfigSchema obj(String comment) {
		ConfigSchema s = new ConfigSchema(Kind.OBJECT);
		s.comment = comment;
		return s;
	}

	public ConfigSchema field(String name, ConfigSchema child) {
		children.put(name, child);
		return this;
	}

	// ---------------------------------------------------------------- 应用

	/**
	 * 把 schema 应用到 holder。
	 *
	 * @param holder   目标对象（会被就地修改）
	 * @param key      字段名
	 * @param path     日志用的完整路径
	 * @param notes    变更记录（缺失补齐 / 越界钳制 / 类型纠正）
	 */
	public void applyTo(JsonObject holder, String key, String path, List<String> notes) {
		String full = path.isEmpty() ? key : path + "." + key;
		JsonElement cur = holder.get(key);

		if (kind == Kind.LIST) {
			JsonArray arr;
			if (cur != null && cur.isJsonArray()) {
				arr = cur.getAsJsonArray();
			} else {
				if (cur != null) {
					notes.add(full + "：期望数组，实际 " + cur + "，已恢复默认");
				} else {
					notes.add(full + "：缺失，已补默认数组（" + listDefault.size() + " 条）");
				}
				arr = listDefault.deepCopy();
			}
			JsonArray out = new JsonArray();
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) {
					notes.add(full + "：数组内出现非对象元素 " + el + "，已丢弃");
					continue;
				}
				JsonObject item = el.getAsJsonObject();
				JsonObject wrap = new JsonObject();
				wrap.add("__item__", item);
				element.applyTo(wrap, "__item__", full + "[]", notes);
				out.add(wrap.getAsJsonObject("__item__"));
			}
			holder.add(key, out);
			return;
		}

		if (kind == Kind.RAW_LIST) {
			if (cur != null && cur.isJsonArray()) return;
			if (cur != null) {
				notes.add(full + "：期望数组，实际 " + cur + "，已恢复默认（" + listDefault.size() + " 条）");
			} else {
				notes.add(full + "：缺失，已补默认数组（" + listDefault.size() + " 条）");
			}
			holder.add(key, listDefault.deepCopy());
			return;
		}

		if (kind == Kind.OBJECT) {
			JsonObject target;
			if (cur != null && cur.isJsonObject()) {
				target = cur.getAsJsonObject();
			} else {
				target = new JsonObject();
				if (cur != null) {
					notes.add(full + "：期望对象，实际 " + cur.getClass().getSimpleName() + "，已重置（原值 " + cur + "）");
				} else {
					notes.add(full + "：缺失，已补默认对象");
				}
			}
			for (Map.Entry<String, ConfigSchema> e : children.entrySet()) {
				e.getValue().applyTo(target, e.getKey(), full, notes);
			}
			// 说明统一放 config/redtale/README.md，配置文件里不写 _comment
			holder.add(key, target);
			return;
		}

		switch (kind) {
			case BOOL -> {
				if (cur != null && cur.isJsonPrimitive() && cur.getAsJsonPrimitive().isBoolean()) {
					return;
				}
				if (cur != null) {
					notes.add(full + "：期望布尔，实际 " + cur + "，已回默认 " + def);
				} else {
					notes.add(full + "：缺失，已补默认 " + def);
				}
				holder.addProperty(key, (Boolean) def);
			}
			case INT -> {
				if (cur != null && cur.isJsonPrimitive() && cur.getAsJsonPrimitive().isNumber()) {
					long v = cur.getAsLong();
					long c = Math.max(min, Math.min(max, v));
					if (c != v) {
						notes.add(full + "：" + v + " 超出 [" + min + "," + max + "]，已钳为 " + c);
						holder.addProperty(key, c);
					}
					return;
				}
				if (cur != null) {
					notes.add(full + "：期望整数，实际 " + cur + "，已回默认 " + def);
				} else {
					notes.add(full + "：缺失，已补默认 " + def);
				}
				holder.addProperty(key, (Long) def);
			}
			case DOUBLE -> {
				if (cur != null && cur.isJsonPrimitive() && cur.getAsJsonPrimitive().isNumber()) {
					double v = cur.getAsDouble();
					double c = Math.max(dmin, Math.min(dmax, v));
					if (Double.compare(c, v) != 0) {
						notes.add(full + "：" + v + " 超出 [" + dmin + "," + dmax + "]，已钳为 " + c);
						holder.addProperty(key, c);
					}
					return;
				}
				if (cur != null) {
					notes.add(full + "：期望小数，实际 " + cur + "，已回默认 " + def);
				} else {
					notes.add(full + "：缺失，已补默认 " + def);
				}
				holder.addProperty(key, (Double) def);
			}
			case STRING -> {
				if (cur != null && cur.isJsonPrimitive() && cur.getAsJsonPrimitive().isString()) {
					String v = cur.getAsString();
					if (allowed != null && !allowed.contains(v)) {
						notes.add(full + "：\"" + v + "\" 不在允许集合内，已回默认 \"" + def + "\"");
						holder.addProperty(key, (String) def);
					}
					return;
				}
				if (cur != null) {
					notes.add(full + "：期望字符串，实际 " + cur + "，已回默认 \"" + def + "\"");
				} else {
					notes.add(full + "：缺失，已补默认 \"" + def + "\"");
				}
				holder.addProperty(key, (String) def);
			}
			case STRING_LIST -> {
				if (cur != null && cur.isJsonArray()) {
					JsonArray out = new JsonArray();
					for (JsonElement el : cur.getAsJsonArray()) {
						if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
							out.add(el.getAsString());
						} else {
							notes.add(full + "：列表内出现非字符串元素 " + el + "，已丢弃");
						}
					}
					holder.add(key, out);
					return;
				}
				if (cur != null) {
					notes.add(full + "：期望字符串数组，实际 " + cur + "，已恢复默认");
				} else {
					notes.add(full + "：缺失，已补默认数组");
				}
				JsonArray arr = new JsonArray();
				for (Object o : (List<?>) def) {
					arr.add(new JsonPrimitive((String) o));
				}
				holder.add(key, arr);
			}
			default -> throw new IllegalStateException("unhandled kind " + kind);
		}
	}

	/** 生成一份完全默认的对象（用于首次启动建文件）。 */
	public JsonObject defaults(List<String> readme, String path) {
		JsonObject holder = new JsonObject();
		applyTo(holder, "__root__", "", new ArrayList<>());
		// applyTo 会把内容挂在 "__root__" 下，这里摊平
		JsonObject root = holder.getAsJsonObject("__root__");
		collectReadme(root, "", readme);
		return root;
	}

	private void collectReadme(JsonObject obj, String prefix, List<String> readme) {

		if (kind == Kind.OBJECT) {
			for (Map.Entry<String, ConfigSchema> e : children.entrySet()) {
				ConfigSchema child = e.getValue();
				String full = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
				if (child.kind == Kind.OBJECT) {
					child.collectReadme(obj.getAsJsonObject(e.getKey()), full, readme);
				} else if (child.kind == Kind.LIST || child.kind == Kind.RAW_LIST) {
					readme.add(full + " = [对象数组]  // " + child.comment);
				} else {
					String range = child.rangeText();
					readme.add(full + " = " + child.defText() + range + "  // " + child.comment);
				}
			}
		}
	}

	private String rangeText() {
		return switch (kind) {
			case INT -> (min != Long.MIN_VALUE || max != Long.MAX_VALUE) ? "  [" + min + "~" + max + "]" : "";
			case DOUBLE -> (dmin != -Double.MAX_VALUE || dmax != Double.MAX_VALUE) ? "  [" + dmin + "~" + dmax + "]" : "";
			case STRING_LIST -> "  [字符串数组]";
			default -> "";
		};
	}

	private String defText() {
		return def == null ? "null" : String.valueOf(def);
	}
}
