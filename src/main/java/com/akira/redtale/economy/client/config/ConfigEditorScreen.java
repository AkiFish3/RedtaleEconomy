package com.akira.redtale.economy.client.config;

import com.akira.redtale.economy.net.ConfigProtocol;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表单式配置编辑器（R14 V1）。
 *
 * <p>按快照里的标量字段自动生成：布尔 → 开关按钮，数字/文本 → 输入框。
 * 保存时全部字段回传，服务端再校验 + 备份 + 原子写 + 热重载。
 * 数组/对象不在这里编辑（V1 只做标量）。
 */
public final class ConfigEditorScreen extends Screen {

	private static final int ROWS = 8;
	private static final int ROW_H = 22;
	private static final int PANEL_W = 420;

	private final String file;
	private final List<ConfigProtocol.Entry> entries;
	private final Map<String, String> values = new LinkedHashMap<>();
	private final List<TextFieldWidget> fields = new ArrayList<>();
	private int page;

	private int left;
	private int top;

	public ConfigEditorScreen(String file, List<ConfigProtocol.Entry> entries) {
		super(Text.translatable("gui.redtale_economy.edit.title"));
		this.file = file == null ? "" : file;
		this.entries = entries == null ? List.of() : entries;
		for (ConfigProtocol.Entry e : this.entries) {
			values.put(e.path(), e.value());
		}
	}

	@Override
	protected void init() {
		int panelH = 26 + ROWS * ROW_H + 40;
		left = (width - PANEL_W) / 2;
		top = (height - panelH) / 2;
		fields.clear();

		int start = page * ROWS;
		for (int row = 0; row < ROWS; row++) {
			int idx = start + row;
			if (idx >= entries.size()) break;
			ConfigProtocol.Entry e = entries.get(idx);
			int y = top + 26 + row * ROW_H;
			if ("bool".equals(e.type())) {
				final String path = e.path();
				addDrawableChild(ButtonWidget.builder(boolLabel(path), b -> {
					values.put(path, "true".equals(values.get(path)) ? "false" : "true");
					b.setMessage(boolLabel(path));
				}).dimensions(left + 230, y, 60, 18).build());
			} else {
				TextFieldWidget tf = new TextFieldWidget(textRenderer, left + 230, y, 180, 18,
						Text.literal(e.path()));
				tf.setMaxLength(256);
				tf.setText(values.getOrDefault(e.path(), ""));
				tf.setChangedListener(v -> values.put(e.path(), v));
				addDrawableChild(tf);
				fields.add(tf);
			}
		}

		int navY = top + 26 + ROWS * ROW_H + 8;
		int pages = pageCount();
		if (pages > 1) {
			addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> turn(-1))
					.dimensions(left + 8, navY, 24, 18).build());
			addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> turn(1))
					.dimensions(left + 34, navY, 24, 18).build());
		}
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.redtale_economy.edit.save"), b -> {
			flush();
			ConfigClientNetworking.save(file, snapshot());
		}).dimensions(left + PANEL_W - 100, navY, 90, 18).build());
	}

	private Text boolLabel(String path) {
		return Text.literal("true".equals(values.get(path))
				? Text.translatable("gui.redtale_economy.edit.on").getString()
				: Text.translatable("gui.redtale_economy.edit.off").getString());
	}

	private int pageCount() {
		return Math.max(1, (entries.size() + ROWS - 1) / ROWS);
	}

	private void turn(int delta) {
		flush();
		int np = Math.max(0, Math.min(pageCount() - 1, page + delta));
		if (np == page) return;
		page = np;
		rebuild();
	}

	private void rebuild() {
		clearChildren();
		init();
	}

	private void flush() {
		// 输入框已经通过 setChangedListener 同步到 values，这里只做兜底
		int start = page * ROWS;
		int fi = 0;
		for (int row = 0; row < ROWS; row++) {
			int idx = start + row;
			if (idx >= entries.size()) break;
			ConfigProtocol.Entry e = entries.get(idx);
			if ("bool".equals(e.type())) continue;
			if (fi < fields.size()) values.put(e.path(), fields.get(fi).getText());
			fi++;
		}
	}

	private List<ConfigProtocol.Entry> snapshot() {
		List<ConfigProtocol.Entry> out = new ArrayList<>(entries.size());
		for (ConfigProtocol.Entry e : entries) {
			out.add(new ConfigProtocol.Entry(e.path(), e.type(), values.getOrDefault(e.path(), e.value())));
		}
		return out;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		renderBackground(ctx);
		int panelH = 26 + ROWS * ROW_H + 40;
		ctx.fill(left - 1, top - 1, left + PANEL_W + 1, top + panelH + 1, 0xFF1A1A1A);
		ctx.fill(left, top, left + PANEL_W, top + panelH, 0xFF2B2B2B);
		ctx.drawText(textRenderer, Text.translatable("gui.redtale_economy.edit.title").getString()
				+ " · " + file, left + 8, top + 8, 0xFFFFFF, false);

		int start = page * ROWS;
		for (int row = 0; row < ROWS; row++) {
			int idx = start + row;
			if (idx >= entries.size()) break;
			ConfigProtocol.Entry e = entries.get(idx);
			int y = top + 26 + row * ROW_H;
			String label = e.path();
			if (textRenderer.getWidth(label) > 215) label = textRenderer.trimToWidth(label, 212) + "…";
			ctx.drawText(textRenderer, label, left + 8, y + 5, 0xDDDDDD, false);
			ctx.drawText(textRenderer, e.type(), left + 196, y + 5, 0x777777, false);
		}
		if (pageCount() > 1) {
			ctx.drawText(textRenderer, (page + 1) + "/" + pageCount(), left + 64,
					top + 26 + ROWS * ROW_H + 13, 0xAAAAAA, false);
		}
		super.render(ctx, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
