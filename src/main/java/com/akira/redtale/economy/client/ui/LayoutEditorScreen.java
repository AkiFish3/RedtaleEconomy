package com.akira.redtale.economy.client.ui;

import com.akira.redtale.economy.ui.LayoutWriter;
import com.akira.redtale.economy.ui.UiLayout;
import com.akira.redtale.economy.ui.UiLayouts;
import com.akira.redtale.economy.ui.UiNode;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/**
 * 布局拖拽编辑器 V3（R17 / spec §9.3）。
 *
 * <p>直接在游戏里拖控件：拖动主体改 x/y，拖右下角改 w/h，方向键微调（Shift ×5）。
 * 保存写回 {@code config/redtale/ui/<id>.json5}（先备份再原子写），布局立刻热重载。
 *
 * <p>只编辑<b>顶层控件</b>：嵌套控件跟着父节点走，逐个拖会让人算不清相对坐标。
 * 未识别的 JSON 字段原样保留（{@link UiLayout#toJson()}），所以手写的注释以外的内容不会丢。
 */
@Environment(EnvType.CLIENT)
public final class LayoutEditorScreen extends LayoutScreen {

	private static final int HANDLE = 8;

	private int selected = -1;
	private boolean dragging;
	private boolean resizing;
	private int grabDx;
	private int grabDy;
	private String flash = "";
	private long flashAt;

	public LayoutEditorScreen(UiLayout layout) {
		super(layout);
	}

	public static void open(String id) {
		MinecraftClient mc = MinecraftClient.getInstance();
		UiLayout l = UiLayouts.get(id);
		if (l == null) {
			if (mc.player != null) {
				mc.player.sendMessage(Text.translatable("gui.redtale_economy.layout.missing", id), false);
			}
			return;
		}
		mc.setScreen(new LayoutEditorScreen(l));
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		if (selected >= 0 && selected < layout.children.size()) {
			UiNode n = layout.children.get(selected);
			int x = ox() + n.x;
			int y = oy() + n.y;
			ctx.drawBorder(x, y, Math.max(1, n.w), Math.max(1, n.h), 0xFFB4462F);
			ctx.fill(x + Math.max(0, n.w) - HANDLE, y + Math.max(0, n.h) - HANDLE,
					x + Math.max(0, n.w), y + Math.max(0, n.h), 0xFFB4462F);
			ctx.drawText(textRenderer, Text.literal(n.type + " " + n.x + "," + n.y + " " + n.w + "x" + n.h),
					x, y - 10, 0xFF2B2118, false);
		}
		ctx.drawText(textRenderer, Text.translatable("gui.redtale_economy.layout.editHint"),
				6, height - 26, 0xFFF3EAD8, false);
		if (!flash.isEmpty() && System.currentTimeMillis() - flashAt < 4000) {
			ctx.drawText(textRenderer, Text.literal(flash), 6, height - 14, 0xFFC9A227, false);
		}
	}

	/** 找最上层的顶层控件（倒序：后声明的在上）。 */
	private int pick(double mx, double my) {
		for (int i = layout.children.size() - 1; i >= 0; i--) {
			UiNode n = layout.children.get(i);
			int x = ox() + n.x;
			int y = oy() + n.y;
			if (mx >= x && mx < x + n.w && my >= y && my < y + n.h) return i;
		}
		return -1;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			int idx = pick(mx, my);
			if (idx >= 0) {
				selected = idx;
				UiNode n = layout.children.get(idx);
				int x = ox() + n.x;
				int y = oy() + n.y;
				resizing = mx >= x + n.w - HANDLE && my >= y + n.h - HANDLE;
				dragging = !resizing;
				grabDx = (int) mx - x;
				grabDy = (int) my - y;
				return true;
			}
			selected = -1;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragging && selected >= 0) {
			UiNode n = layout.children.get(selected);
			int nx = (int) mx - ox() - grabDx;
			int ny = (int) my - oy() - grabDy;
			n.raw.addProperty("x", nx);
			n.raw.addProperty("y", ny);
			reloadFromDiskInMemory();
			return true;
		}
		if (resizing && selected >= 0) {
			UiNode n = layout.children.get(selected);
			int w = Math.max(8, (int) mx - ox() - n.x);
			int h = Math.max(8, (int) my - oy() - n.y);
			n.raw.addProperty("w", w);
			n.raw.addProperty("h", h);
			reloadFromDiskInMemory();
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	/**
	 * 拖拽不改布局对象本身（UiNode 字段是 final），只改 raw；
	 * 为了让画面立刻反映新坐标，这里用 raw 重新解析一份覆盖显示。
	 */
	private void reloadFromDiskInMemory() {
		try {
			var fresh = com.akira.redtale.economy.ui.UiLayout.parse(layout.id, layout.toJson());
			layout.children = new java.util.ArrayList<>(fresh.children);
		} catch (Exception e) {
			// 解析失败就保持原样，下一次拖动再试
		}
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		dragging = false;
		resizing = false;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		UiNode n = selected >= 0 && selected < layout.children.size() ? layout.children.get(selected) : null;
		if (n != null) {
			int step = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? 5 : 1;
			boolean handled = true;
			switch (keyCode) {
				case GLFW.GLFW_KEY_LEFT -> n.raw.addProperty("x", n.x - step);
				case GLFW.GLFW_KEY_RIGHT -> n.raw.addProperty("x", n.x + step);
				case GLFW.GLFW_KEY_UP -> n.raw.addProperty("y", n.y - step);
				case GLFW.GLFW_KEY_DOWN -> n.raw.addProperty("y", n.y + step);
				case GLFW.GLFW_KEY_LEFT_BRACKET -> n.raw.addProperty("w", Math.max(8, n.w - step));
				case GLFW.GLFW_KEY_RIGHT_BRACKET -> n.raw.addProperty("w", n.w + step);
				case GLFW.GLFW_KEY_MINUS -> n.raw.addProperty("h", Math.max(8, n.h - step));
				case GLFW.GLFW_KEY_EQUAL -> n.raw.addProperty("h", n.h + step);
				case GLFW.GLFW_KEY_TAB -> {
					selected = (selected + 1) % Math.max(1, layout.children.size());
					return true;
				}
				case GLFW.GLFW_KEY_S -> {
					save();
					return true;
				}
				default -> handled = false;
			}
			if (handled) {
				reloadFromDiskInMemory();
				return true;
			}
		}
		if (keyCode == GLFW.GLFW_KEY_S) {
			save();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private void save() {
		Path p = LayoutWriter.save(layout);
		flash = (p == null ? Text.translatable("gui.redtale_economy.layout.saveFailed")
				: Text.translatable("gui.redtale_economy.layout.saved", p.getFileName().toString())).getString();
		flashAt = System.currentTimeMillis();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
