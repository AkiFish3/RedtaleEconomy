package com.akira.redtale.economy.config;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.ConfigProtocol;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表单式配置编辑器（R14 V1）的服务端部分。
 *
 * <p>把已登记的配置文件摊平成「标量字段列表」给客户端，保存时逐条回写：
 * <b>解析 → schema 校验（坏值拉回合法域）→ 备份 → 原子写 → 热重载</b>。
 * 数组/对象不在 V1 的编辑范围内，只作为只读结构保留。
 */
public final class ConfigEditor {

	/** 单次最多编辑多少字段（防御性上限，正常配置远小于此）。 */
	private static final int MAX_FIELDS = 2000;

	public record Result(boolean ok, int applied, int rejected, String message) {
	}

	private ConfigEditor() {
	}

	/** 摊平一个配置文件的标量字段；文件未登记时返回空列表。 */
	public static List<ConfigProtocol.Entry> snapshot(String file) {
		List<ConfigProtocol.Entry> out = new ArrayList<>();
		if (!ConfigManager.get().isDefined(file)) return out;
		JsonElement root = ConfigManager.get().store(file).root();
		if (root == null || !root.isJsonObject()) return out;
		flatten("", root, out);
		return out;
	}

	private static void flatten(String prefix, JsonElement el, List<ConfigProtocol.Entry> out) {
		if (out.size() >= MAX_FIELDS || el == null || !el.isJsonObject()) return;
		for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
			String key = e.getKey();
			if (key.startsWith("_")) continue; // _version 等元数据不参与编辑
			String path = prefix.isEmpty() ? key : prefix + "." + key;
			JsonElement v = e.getValue();
			if (v == null) continue;
			if (v.isJsonObject()) {
				flatten(path, v, out);
			} else if (v.isJsonPrimitive()) {
				JsonPrimitive p = v.getAsJsonPrimitive();
				String type = p.isBoolean() ? "bool" : (p.isNumber() ? (isInt(p) ? "int" : "double") : "string");
				out.add(new ConfigProtocol.Entry(path, type, p.getAsString()));
			}
			// 数组：V1 不支持编辑，跳过（文件里原样保留）
		}
	}

	private static boolean isInt(JsonPrimitive p) {
		try {
			p.getAsLong();
			return p.getAsString().indexOf('.') < 0;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * 应用客户端提交的修改。
	 *
	 * <p>只允许改「本来就存在的字段路径」，避免客户端凭空塞新 key 把配置撑大。
	 */
	public static Result apply(String file, List<ConfigProtocol.Entry> entries) {
		if (!ConfigManager.get().isDefined(file)) {
			return new Result(false, 0, 0, "未登记的配置文件：" + file);
		}
		ConfigStore store = ConfigManager.get().store(file);
		int applied = 0;
		int rejected = 0;
		for (ConfigProtocol.Entry e : entries) {
			JsonElement cur = store.getPath(e.path());
			if (cur == null || !cur.isJsonPrimitive()) {
				rejected++;
				continue;
			}
			try {
				switch (e.type()) {
					case "bool" -> {
						String v = e.value().trim();
						if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
							rejected++;
							continue;
						}
						store.setPath(e.path(), Boolean.parseBoolean(v));
					}
					case "int" -> store.setPath(e.path(), Long.parseLong(e.value().trim()));
					case "double" -> store.setPath(e.path(), new JsonPrimitive(Double.parseDouble(e.value().trim())));
					default -> store.setPath(e.path(), e.value());
				}
				applied++;
			} catch (Exception ex) {
				rejected++;
			}
		}

		List<String> notes = store.validate();
		store.save(true);
		ConfigManager.get().reloadAll();
		RedtaleEconomy.LOGGER.info("[edit] {} 应用 {} 条，拒绝 {} 条，校验修正 {} 处",
				file, applied, rejected, notes.size());
		return new Result(true, applied, rejected, notes.isEmpty() ? "" : String.join("; ", notes));
	}
}
