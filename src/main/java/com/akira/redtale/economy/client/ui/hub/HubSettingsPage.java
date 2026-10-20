package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Hub 内的设置页（U10）：真实读写的客户端偏好。
 *
 * <p>只有「本地偏好」在这里；服务器配置（商店价格、任务奖励）仍由 {@code /rt admin} 改。
 */
public final class HubSettingsPage {

	/** 可点击的行。 */
	public enum Row {
		THEME, SOUND, LABELS, TIPS, DEBUG,
		/** U10：跳原版语言设置（界面上写的语言必须能改，不能只在标题里承诺）。 */
		LANGUAGE,
		/** U10：跳原版按键设置（不自己造键位界面）。 */
		KEYBINDS,
		/** U10：管理员折叠区（仅 OP4 可见，默认收起）。 */
		ADMIN_HEAD, ADMIN_SHOP, ADMIN_CONFIG, ADMIN_RELOAD
	}

	/** 管理员区是否展开（每帧由 HubScreen 传入，这里只存会话内状态）。 */
	private static boolean adminOpen;

	/** 内容滚动偏移（GUI Scale 2 下内容区装不下 5 行以上，必须能滚）。 */
	private static int scroll;

	public static void setAdminOpen(boolean v) {
		adminOpen = v;
	}

	public static boolean adminOpen() {
		return adminOpen;
	}

	/** 滚动上限；{@code h} 是内容区高度。 */
	public static int maxScroll(int h) {
		return Math.max(0, contentHeight() - h);
	}

	public static void scrollBy(int delta) {
		scroll = Math.max(0, scroll + delta);
	}

	/** 仅 dev 夹具：直接跳到指定偏移（{@link #maxScroll} 之外的值由 render 夹回）。 */
	public static void scrollTo(int v) {
		scroll = Math.max(0, v);
	}

	/** 内容总高（行 + 分组标题 + 底部提示），与 render 的累加口径一致。 */
	private static int contentHeight() {
		String lastGroup = "";
		int total = 0;
		for (Item it : items()) {
			if (!it.group.equals(lastGroup)) {
				lastGroup = it.group;
				total += 16;
			}
			total += 20;
		}
		return total + 16;
	}

	/** 客户端能判定的最高权限等级（OP 4）。服务器侧仍会再判一次。 */
	private static boolean isAdmin() {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		return mc.player != null && mc.player.hasPermissionLevel(4);
	}

	private HubSettingsPage() {
	}

	private static class Item {
		String group;
		String label;
		String value;
		Row row;
	}

	private static Item[] items() {
		java.util.List<Item> list = new java.util.ArrayList<>();
		list.add(item("外观", "界面主题", ClientUiConfig.themeLabel(), Row.THEME));
		list.add(item("外观", "按钮音效", ClientUiConfig.soundLabel(), Row.SOUND));
		list.add(item("界面", "侧边栏显示文字", ClientUiConfig.showLabels ? "开" : "关", Row.LABELS));
		list.add(item("界面", "底部提示轮播", ClientUiConfig.tipsEnabled ? "开" : "关", Row.TIPS));
		list.add(item("界面", "显示语言", "打开原版设置", Row.LANGUAGE));
		list.add(item("按键", "修改键位", "打开原版设置", Row.KEYBINDS));
		list.add(item("高级", "连接信息", "—", Row.DEBUG));
		// U10：管理员区默认折叠，避免普通玩家看到一堆「点了也没权限」的行
		if (isAdmin()) {
			// 折叠标记只用 ASCII：几何图形块（▸/▴/▾）在默认字体里常是空白字形，
			// 上一轮 U11 的树箭头就踩过（▸ 画出来什么都没有）。
			list.add(item("管理员", "管理工具", adminOpen ? "收起 -" : "展开 +", Row.ADMIN_HEAD));
			if (adminOpen) {
				list.add(item("管理员", "商店编辑", "/shop admin edit", Row.ADMIN_SHOP));
				list.add(item("管理员", "配置编辑", "/redtale edit", Row.ADMIN_CONFIG));
				list.add(item("管理员", "重载配置", "/redtale reload", Row.ADMIN_RELOAD));
			}
		}
		return list.toArray(new Item[0]);
	}

	private static Item item(String g, String l, String v, Row r) {
		Item i = new Item();
		i.group = g;
		i.label = l;
		i.value = v;
		i.row = r;
		return i;
	}

	/** 渲染；返回底部 Y，供点击测试复用同一套布局。 */
	public static int render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		int max = maxScroll(h);
		if (scroll > max) scroll = max;
		if (scroll < 0) scroll = 0;
		// 行超出内容区一律裁掉，否则会画到顶栏/底栏上（U10 实测：管理员区在 Scale 2 下整段不可见）
		dc.enableScissor(x, y, x + w, y + h);
		String lastGroup = "";
		int cy = y - scroll;
		for (Item it : items()) {
			if (!it.group.equals(lastGroup)) {
				lastGroup = it.group;
				// 分组标题走 MUTED：曾写死 0xFF6A6A6A，在原版灰底（0xFFC6C6C6）上对比度约 2.6:1
				dc.drawText(tr, Text.literal(it.group), x, cy + 2, HubTheme.MUTED, false);
				cy += 16;
			}
			boolean inArea = mouseY >= y && mouseY < y + h;
			boolean hover = inArea && mouseX >= x && mouseX < x + w
					&& mouseY >= cy && mouseY < cy + 18;
			// 悬停底走主题：写死 0xFF323232 在原版主题里会把深灰文字糊掉
			dc.fill(x, cy, x + w, cy + 18, hover ? HubTheme.ROW_HOVER : HubTheme.CARD);
			dc.drawText(tr, Text.literal(it.label), x + 6, cy + 5, HubTheme.TEXT, false);
			int vw = tr.getWidth(it.value);
			boolean clickable = it.row != Row.DEBUG;
			dc.drawText(tr, Text.literal(it.value), x + w - 6 - vw, cy + 5,
					clickable ? HubTheme.GOLD : HubTheme.MUTED, false);
			cy += 20;
		}
		dc.drawText(tr, Text.literal("点击右侧即可切换，立即生效"), x, cy + 4, HubTheme.MUTED, false);
		dc.disableScissor();
		// 可滚时画一条细滚动条，否则玩家不知道下面还有内容
		if (max > 0) {
			int trackH = h;
			int barH = Math.max(16, (int) ((long) trackH * trackH / (trackH + max)));
			int barY = y + (int) ((long) (trackH - barH) * scroll / max);
			dc.fill(x + w - 2, y, x + w, y + trackH, HubTheme.TRACK);
			dc.fill(x + w - 2, barY, x + w, barY + barH, HubTheme.GOLD);
		}
		return cy;
	}

	/** 命中测试：返回被点的行，没有则 null。 */
	public static Row click(double mx, double my, int x, int y, int w, int h) {
		if (my < y || my >= y + h) return null;
		String lastGroup = "";
		int cy = y - scroll;
		for (Item it : items()) {
			if (!it.group.equals(lastGroup)) {
				lastGroup = it.group;
				cy += 16;
			}
			if (mx >= x && mx < x + w && my >= cy && my < cy + 18) {
				return it.row;
			}
			cy += 20;
		}
		return null;
	}

	/**
	 * 仅 dev 截图夹具使用：{@code row} 那一行的中心点（与本类 render / click 同一套布局）。
	 *
	 * <p>越界（被裁剪在可视区外）或该行当前不存在（折叠中的管理员子行）返回 {@code null}，
	 * 夹具据此判定「点不到」而不是硬点一个空坐标。
	 */
	public static double[] rowCenter(Row row, int x, int y, int w, int h) {
		String lastGroup = "";
		int cy = y - scroll;
		for (Item it : items()) {
			if (!it.group.equals(lastGroup)) {
				lastGroup = it.group;
				cy += 16;
			}
			if (it.row == row) {
				double mx = x + w / 2.0;
				double my = cy + 9;
				if (my < y || my >= y + h) return null;
				return new double[]{mx, my};
			}
			cy += 20;
		}
		return null;
	}

	/** 仅 dev 夹具：当前是否展开管理员区（写日志用）。 */
	public static boolean devAdminOpen() {
		return adminOpen;
	}

	/** 仅 dev 夹具：当前权限下可见的行数（非 OP 恒比 OP 少 4 行：管理工具 + 3 个子行）。 */
	public static int devRowCount() {
		return items().length;
	}

	/** 仅 dev 夹具：客户端判定自己是不是管理员（读的就是 render 用的同一个判断）。 */
	public static boolean devAdminVisible() {
		return isAdmin();
	}

	/** 仅 dev 夹具：当前滚动偏移（写日志用）。 */
	public static int devScroll() {
		return scroll;
	}

	/** 执行切换并返回要显示的提示。 */
	public static String toggle(Row row) {
		switch (row) {
			case LANGUAGE -> {
				var mc = net.minecraft.client.MinecraftClient.getInstance();
				// 直接复用原版语言列表：改完按「完成」回 Hub
				mc.setScreen(new net.minecraft.client.gui.screen.option.LanguageOptionsScreen(
						mc.currentScreen, mc.options, mc.getLanguageManager()));
				return "已打开语言设置";
			}
			case KEYBINDS -> {
				var mc = net.minecraft.client.MinecraftClient.getInstance();
				// 直接复用原版键位界面：玩家改完按 Esc 回到 Hub（不自己造一套）
				mc.setScreen(new net.minecraft.client.gui.screen.option.KeybindsScreen(
						mc.currentScreen, mc.options));
				return "已打开键位设置";
			}
			case ADMIN_HEAD -> {
				adminOpen = !adminOpen;
				return adminOpen ? "管理员工具：展开" : "管理员工具：收起";
			}
			case ADMIN_SHOP -> {
				var mc = net.minecraft.client.MinecraftClient.getInstance();
				if (mc.player != null) mc.player.networkHandler.sendChatCommand("shop admin edit");
				return "已请求商店编辑界面";
			}
			case ADMIN_CONFIG -> {
				var mc = net.minecraft.client.MinecraftClient.getInstance();
				if (mc.player != null) mc.player.networkHandler.sendChatCommand("redtale edit");
				return "已请求配置列表";
			}
			case ADMIN_RELOAD -> {
				var mc = net.minecraft.client.MinecraftClient.getInstance();
				if (mc.player != null) mc.player.networkHandler.sendChatCommand("redtale reload");
				return "已请求重载配置";
			}
			case THEME -> {
				ClientUiConfig.theme = HubTheme.next(ClientUiConfig.theme);
				ClientUiConfig.save();
				HubTheme.apply(ClientUiConfig.theme);
				return "界面主题：" + ClientUiConfig.themeLabel();
			}
			case SOUND -> {
				ClientUiConfig.sound = "silent".equals(ClientUiConfig.sound) ? "vanilla" : "silent";
				ClientUiConfig.save();
				return "按钮音效：" + ClientUiConfig.soundLabel();
			}
			case LABELS -> {
				ClientUiConfig.showLabels = !ClientUiConfig.showLabels;
				ClientUiConfig.save();
				return "侧边栏文字：" + (ClientUiConfig.showLabels ? "开" : "关（仅图标）");
			}
			case TIPS -> {
				ClientUiConfig.tipsEnabled = !ClientUiConfig.tipsEnabled;
				ClientUiConfig.save();
				return "底部提示：" + (ClientUiConfig.tipsEnabled ? "开" : "关");
			}
			default -> {
				return "";
			}
		}
	}
}
