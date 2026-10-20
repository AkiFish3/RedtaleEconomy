package com.akira.redtale.economy.client.ui;

import com.akira.redtale.economy.ui.UiDataRegistry;
import com.akira.redtale.economy.ui.UiLayout;
import com.akira.redtale.economy.ui.UiLayouts;
import com.akira.redtale.economy.ui.UiNode;
import com.akira.redtale.economy.ui.UiTheme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 布局渲染器（R15 / spec §9.2）。
 *
 * <p>控件的几何、文案、颜色、动作全部来自布局 JSON，代码里<b>没有一处硬编码界面</b>；
 * 换皮 / 加按钮 / 改位置都只改 {@code config/redtale/ui/**.json5}。
 *
 * <p>交互只产生「意图」：{@code cmd:} 走玩家命令（服务端再校验权限），{@code ui:} 切布局，
 * 客户端不做任何经济学判断。
 */
@Environment(EnvType.CLIENT)
public class LayoutScreen extends Screen {

	protected final UiLayout layout;
	private final Map<String, Integer> scroll = new HashMap<>();

	public LayoutScreen(UiLayout layout) {
		super(Text.translatable("gui.redtale_economy.layout." + layout.id.replace('/', '.')));
		this.layout = layout;
	}

	public static void open(String id) {
		UiLayout l = UiLayouts.get(id);
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (l == null) {
			if (mc.player != null) mc.player.sendMessage(
					Text.translatable("gui.redtale_economy.layout.missing", id), false);
			return;
		}
		mc.setScreen(new LayoutScreen(l));
	}

	protected int ox() {
		return (width - layout.width) / 2;
	}

	protected int oy() {
		return (height - layout.height) / 2;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		renderBackground(ctx);
		int x = ox();
		int y = oy();
		int w = layout.width;
		int h = layout.height;

		Identifier bg = layout.background.isBlank() ? null : Identifier.tryParse(layout.background);
		if (bg != null) {
			ctx.drawTexture(bg, x, y, 0, 0, w, h, 256, 256);
		} else {
			ctx.fill(x, y, x + w, y + h, UiTheme.background());
			ctx.fill(x + 2, y + 2, x + w - 2, y + h - 2, UiTheme.content());
		}
		ctx.drawBorder(x, y, w, h, UiTheme.border());
		if (!layout.title.isBlank()) {
			ctx.drawText(textRenderer, Text.translatable(layout.title), x + 8, y + 6, UiTheme.border(), false);
		}

		for (UiNode n : layout.children) {
			draw(ctx, n, x, y, mouseX, mouseY);
		}
		super.render(ctx, mouseX, mouseY, delta);
	}

	protected void draw(DrawContext ctx, UiNode n, int px, int py, int mx, int my) {
		int ax = px + n.x;
		int ay = py + n.y;
		int color = UiTheme.parseColor(n.color, UiTheme.textDark());

		switch (n.type) {
			case LABEL -> {
				int tx = switch (n.align) {
					case "center" -> ax + (n.w - textRenderer.getWidth(Text.translatable(n.text))) / 2;
					case "right" -> ax + n.w - textRenderer.getWidth(Text.translatable(n.text));
					default -> ax;
				};
				ctx.drawText(textRenderer, Text.translatable(n.text), tx, ay, color, false);
			}
			case BUTTON, TAB -> {
				boolean hover = mx >= ax && mx < ax + n.w && my >= ay && my < ay + n.h;
				int bgc = hover ? UiTheme.accent() : UiTheme.border();
				ctx.fill(ax, ay, ax + n.w, ay + n.h, bgc);
				ctx.fill(ax + 1, ay + 1, ax + n.w - 1, ay + n.h - 1,
						hover ? UiTheme.content() : UiTheme.background());
				int tx = ax + (n.w - textRenderer.getWidth(Text.translatable(n.text))) / 2;
				ctx.drawText(textRenderer, Text.translatable(n.text), tx, ay + (n.h - 8) / 2,
						hover ? UiTheme.textDark() : UiTheme.textLight(), false);
			}
			case LIST -> {
				ctx.fill(ax - 1, ay - 1, ax + n.w + 1, ay + n.h + 1, UiTheme.border());
				ctx.fill(ax, ay, ax + n.w, ay + n.h, 0x14000000);
				List<String> rows = UiDataRegistry.rows(n.source);
				ctx.enableScissor(ax, ay, ax + n.w, ay + n.h);
				if (rows == null) {
					ctx.drawText(textRenderer, Text.literal("（无数据源: " + n.source + "）"),
							ax + 4, ay + 4, UiTheme.danger(), false);
				} else {
					int off = scroll.getOrDefault(n.source, 0);
					int total = rows.size();
					int visible = Math.max(1, n.h / n.rowHeight);
					off = Math.max(0, Math.min(off, Math.max(0, total - visible)));
					for (int i = 0; i < visible && off + i < total; i++) {
						String row = rows.get(off + i);
						int bar = row.indexOf('|');
						String label = bar < 0 ? row : row.substring(0, bar);
						boolean hoverRow = mx >= ax && mx < ax + n.w
								&& my >= ay + i * n.rowHeight && my < ay + (i + 1) * n.rowHeight;
						ctx.drawText(textRenderer, Text.literal(label),
								ax + 4, ay + 2 + i * n.rowHeight,
								hoverRow && !n.action.isBlank() ? UiTheme.accent() : UiTheme.textDark(), false);
					}
				}
				ctx.disableScissor();
			}
			case GRID -> {
				List<String> rows = UiDataRegistry.rows(n.source);
				int cw = Math.max(1, n.w / n.columns);
				ctx.enableScissor(ax, ay, ax + n.w, ay + n.h);
				if (rows == null) {
					ctx.drawText(textRenderer, Text.literal("（无数据源: " + n.source + "）"),
							ax + 4, ay + 4, UiTheme.danger(), false);
				} else {
					for (int i = 0; i < rows.size(); i++) {
						int cx = ax + (i % n.columns) * cw;
						int cy = ay + (i / n.columns) * n.rowHeight;
						if (cy > ay + n.h) break;
						String row = rows.get(i);
						int bar = row.indexOf('|');
						String label = bar < 0 ? row : row.substring(0, bar);
						boolean hoverRow = mx >= cx && mx < cx + cw && my >= cy && my < cy + n.rowHeight;
						ctx.drawText(textRenderer, Text.literal(label), cx + 2, cy + 2,
								hoverRow && !n.action.isBlank() ? UiTheme.accent() : UiTheme.textDark(), false);
					}
				}
				ctx.disableScissor();
			}
			case SLOT_GRID -> {
				int cell = 18;
				int cols = Math.max(1, n.w / cell);
				for (int cy = 0; cy + cell <= n.h; cy += cell) {
					for (int cx = 0; cx + cell <= n.w; cx += cell) {
						if (cols <= 0) break;
						ctx.fill(ax + cx, ay + cy, ax + cx + cell - 1, ay + cy + cell - 1, 0xFF8B8B8B);
						ctx.fill(ax + cx + 1, ay + cy + 1, ax + cx + cell - 2, ay + cy + cell - 2,
								UiTheme.content());
					}
				}
			}
			case PROGRESS -> {
				ctx.fill(ax, ay, ax + n.w, ay + n.h, 0xFF6B5A45);
				double ratio = n.max <= 0 ? 0 : Math.max(0, Math.min(1, n.value / n.max));
				ctx.fill(ax, ay, ax + (int) (n.w * ratio), ay + n.h, UiTheme.accent());
				if (!n.text.isBlank()) {
					ctx.drawText(textRenderer, Text.translatable(n.text), ax + 2, ay - 10, UiTheme.textDark(), false);
				}
			}
			case IMAGE -> {
				Identifier id = Identifier.tryParse(n.image);
				if (id != null) ctx.drawTexture(id, ax, ay, 0, 0, n.w, n.h, 256, 256);
			}
			default -> {
				// UNKNOWN：不渲染，避免一个拼错的 type 让整屏空白
			}
		}

		for (UiNode c : n.children) {
			draw(ctx, c, ax, ay, mx, my);
		}
	}

	/** 列表/网格的行点击：行文本 `标签|参数`，节点的 action 里 `%s` 用参数替换。 */
	private boolean clickRows(double mx, double my) {
		for (UiNode n : layout.children) {
			if (n.type != UiNode.Type.LIST && n.type != UiNode.Type.GRID) continue;
			int ax = ox() + n.x;
			int ay = oy() + n.y;
			if (mx < ax || mx >= ax + n.w || my < ay || my >= ay + n.h) continue;
			List<String> rows = UiDataRegistry.rows(n.source);
			if (rows == null) return true;
			int idx;
			if (n.type == UiNode.Type.GRID) {
				int cw = Math.max(1, n.w / n.columns);
				idx = (int) ((my - ay) / n.rowHeight) * n.columns + (int) ((mx - ax) / cw);
			} else {
				int visible = Math.max(1, n.h / n.rowHeight);
				int rowInView = (int) ((my - ay) / n.rowHeight);
				if (rowInView >= visible) return true;
				idx = scroll.getOrDefault(n.source, 0) + rowInView;
			}
			if (idx < 0 || idx >= rows.size()) return true;
			String row = rows.get(idx);
			int bar = row.indexOf('|');
			if (bar < 0 || n.action.isBlank()) return true;
			String arg = row.substring(bar + 1);
			UiActions.run(n.action.contains("%s") ? n.action.replace("%s", arg) : n.action);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			if (clickRows(mx, my)) return true;
			UiNode hit = null;
			for (int i = layout.children.size() - 1; i >= 0 && hit == null; i--) {
				hit = layout.children.get(i).hit((int) mx, (int) my, ox(), oy());
			}
			if (hit != null) {
				UiActions.run(hit.action);
				return true;
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		for (UiNode n : layout.children) {
			if (n.type == UiNode.Type.LIST && mx >= ox() + n.x && mx < ox() + n.x + n.w
					&& my >= oy() + n.y && my < oy() + n.y + n.h) {
				int cur = scroll.getOrDefault(n.source, 0);
				scroll.put(n.source, Math.max(0, cur - (int) Math.signum(amount)));
				return true;
			}
		}
		return super.mouseScrolled(mx, my, amount);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
