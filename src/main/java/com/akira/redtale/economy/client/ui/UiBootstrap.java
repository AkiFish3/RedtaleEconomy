package com.akira.redtale.economy.client.ui;

import com.akira.redtale.economy.ui.UiDataRegistry;
import com.akira.redtale.economy.ui.UiLayouts;
import com.akira.redtale.economy.ui.UiTheme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据驱动 UI 的客户端装配（R15）。
 *
 * <p>把 {@link MenuEntries} 变成布局可消费的数据源 `menu.entries`（每行 `名称|命令`），
 * 并提供 {@code /rtui <布局id>} 用于调试与服主自测。
 */
@Environment(EnvType.CLIENT)
public final class UiBootstrap {

	private UiBootstrap() {
	}

	public static void register() {
		// T56：Hub 任务页收包（独立于老的任务面板通道，收包只更新状态不弹界面）
		com.akira.redtale.economy.client.ui.hub.TasksHubClientNetworking.register();

		UiDataRegistry.register("menu.entries", source -> {
			List<String> rows = new ArrayList<>();
			for (MenuEntries.Entry e : MenuEntries.all()) {
				rows.add(label(e.key()) + "|" + e.command());
			}
			return rows;
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
				dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("rtui")
						.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("layout",
										com.mojang.brigadier.arguments.StringArgumentType.greedyString())
								.executes(ctx -> {
									String id = com.mojang.brigadier.arguments.StringArgumentType
											.getString(ctx, "layout");
									LayoutScreen.open(id);
									return 1;
								}))));

		// R16：表格编辑器（批量改价 / 搜索 / 排序 / 勾选）
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
				dispatcher.register(
						net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("rtedit")
						.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("shop",
										com.mojang.brigadier.arguments.StringArgumentType.word())
								.executes(ctx -> {
									String shop = com.mojang.brigadier.arguments.StringArgumentType
											.getString(ctx, "shop");
									com.akira.redtale.economy.client.admin.TableEditorScreen.open(shop);
									return 1;
								}))));

		// R17：布局拖拽编辑器
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
				dispatcher.register(
						net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("rtlayout")
								.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
										.argument("id",
												com.mojang.brigadier.arguments.StringArgumentType.greedyString())
										.executes(ctx -> {
											String id = com.mojang.brigadier.arguments.StringArgumentType
													.getString(ctx, "id");
											LayoutEditorScreen.open(id);
											return 1;
										}))));

		UiTheme.load();
		UiLayouts.reload();
		com.akira.redtale.economy.RedtaleEconomy.LOGGER.info("[ui] 数据驱动 UI 就绪，布局 {} 份",
				UiLayouts.ids().size());
	}

	private static String label(String key) {
		return Text.translatable("gui.redtale_economy.menu." + key).getString();
	}
}
