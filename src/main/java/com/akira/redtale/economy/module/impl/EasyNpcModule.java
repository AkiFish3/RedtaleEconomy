package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.EasyNpcConfig;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.perm.Permissions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * EasyNPC 联动（R33）：只做两件事——打等级 tag、生成 3 套对话预设。
 *
 * <p>不打 tag 也不影响主线功能：没装 EasyNPC 时预设文件不生成，tag 仍然会打
 * （服主以后装了 EasyNPC 直接就能用 tag 做条件分支）。
 */
public final class EasyNpcModule implements RedtaleModule {

	private static volatile boolean hooksInstalled;

	@Override
	public String id() {
		return "easynpc";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(EasyNpcConfig.FILE, EasyNpcConfig.SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		installHooks();
		if (EasyNpcConfig.writePresets()) writePresets();
		if (!FabricLoader.getInstance().isModLoaded("easy_npc")) {
			RedtaleEconomy.LOGGER.info("[easynpc] 未检测到 easy_npc，只打 rank tag，不生成预设");
		}
	}

	@Override
	public void onReload(ConfigManager cfg) {
		if (EasyNpcConfig.writePresets()) writePresets();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("easynpc")
				.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
				.executes(EasyNpcModule::help)
				.then(CommandManager.literal("sync").executes(EasyNpcModule::syncSelf))
				.then(CommandManager.literal("presets").executes(EasyNpcModule::regenerate)));
	}

	private static boolean enabled() {
		return com.akira.redtale.economy.module.ModuleManager.get().isEnabled("easynpc")
				&& EasyNpcConfig.enabled();
	}

	// ---------------------------------------------------------------- tag

	/** 同步等级 tag：先清旧的 rt_rank_*，再按当前等级打一个。 */
	public static void syncRankTag(ServerPlayerEntity player) {
		if (player == null || !enabled()) return;
		try {
			String prefix = EasyNpcConfig.tagPrefix();
			if (EasyNpcConfig.removeStaleTags()) {
				// 1.20.1 的 Entity.getCommandTags() 直接返回可变集合，没有 removeCommandTag
				player.getCommandTags().removeIf(tag -> tag.startsWith(prefix));
			}
			GuildConfigHolder holder = new GuildConfigHolder();
			String rank = holder.rankId(player.getUuid());
			if (rank != null && !rank.isEmpty()) {
				player.addCommandTag(prefix + rank);
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[easynpc] 同步等级 tag 失败：{}", e.getMessage());
		}
	}

	/** 避免与 GuildModule 循环依赖的小包装。 */
	private static final class GuildConfigHolder {
		String rankId(UUID uuid) {
			var rank = GuildModule.rankOf(uuid);
			return rank == null ? "" : rank.id();
		}
	}

	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;
		if (!EasyNpcConfig.syncOnJoin()) return;
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncRankTag(handler.getPlayer()));
	}

	// ---------------------------------------------------------------- 预设

	private static void writePresets() {
		try {
			Path dir = ConfigManager.get().baseDir().resolve(EasyNpcConfig.presetsDir());
			Files.createDirectories(dir);
			for (String name : EasyNpcConfig.presets()) {
				Path f = dir.resolve(name + ".json");
				if (Files.isRegularFile(f)) continue;
				Files.writeString(f, presetJson(name), StandardCharsets.UTF_8);
				RedtaleEconomy.LOGGER.info("[easynpc] 已生成预设 {}", f);
			}
			Path readme = dir.resolve("README.md");
			if (!Files.isRegularFile(readme)) {
				Files.writeString(readme, readmeText(), StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[easynpc] 生成预设失败：{}", e.getMessage());
		}
	}

	private static String presetJson(String name) {
		String npc;
		String[][] buttons;
		switch (name) {
			case "shop_keeper" -> {
				npc = "商店老板";
				buttons = new String[][]{
						{"买东西", "/shop"},
						{"看看货架", "/shop list"},
				};
			}
			case "warehouse_keeper" -> {
				npc = "仓库管理员";
				buttons = new String[][]{
						{"我要存东西", "/storage"},
						{"扩容仓库", "/storage upgrade"},
						{"我的仓库多大", "/storage info"},
				};
			}
			default -> {
				npc = "协会接待员";
				buttons = new String[][]{
						{"我要接悬赏", "/bounty"},
						{"看看我的等级", "/guild"},
						{"我的称号", "/titles"},
						{"战令进度", "/pass"},
				};
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("{\n  \"_comment\": \"Redtale Economy 预设：在 EasyNPC 编辑器里给 NPC 加对话按钮，label/command 照抄；executeAsUser=true（以玩家身份执行）\",\n");
		sb.append("  \"npc\": \"").append(npc).append("\",\n");
		sb.append("  \"conditionTagPrefix\": \"").append(EasyNpcConfig.tagPrefix()).append("\",\n");
		sb.append("  \"buttons\": [\n");
		for (int i = 0; i < buttons.length; i++) {
			sb.append("    { \"label\": \"").append(buttons[i][0])
					.append("\", \"type\": \"command\", \"command\": \"").append(buttons[i][1])
					.append("\", \"executeAsUser\": true }")
					.append(i + 1 < buttons.length ? ",\n" : "\n");
		}
		sb.append("  ]\n}\n");
		return sb.toString();
	}

	private static String readmeText() {
		return """
				# EasyNPC 预设（R33）

				这里的 3 个 JSON 是**给服主照抄用**的清单，不是 EasyNPC 的原生导出格式
				（我们不做它的数据读写，避免版本变动断掉）。做法：

				1. 在 EasyNPC 编辑器里选中 NPC → 对话 → 加按钮；
				2. `label` 填 JSON 里的 label；
				3. `type` 选 Command，命令填 `command` 字段（不带斜杠也可以）；
				4. 勾选「以玩家身份执行 / execute as user」。

				按等级换台词：玩家身上有 tag `rt_rank_F` … `rt_rank_S`（前缀可在
				`config/redtale/easynpc.json → tagPrefix` 改），在 EasyNPC 的对话条件里
				直接按 tag 分支即可，不需要写任何代码。

				手动重打 tag：`/easynpc sync`（需要 admin）。
				""";
	}

	private static int help(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal("§7/easynpc sync 重打自己的等级 tag · §f/easynpc presets 重新生成预设文件"), false);
		return 1;
	}

	private static int syncSelf(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity p = ctx.getSource().getPlayer();
		if (p == null) {
			ctx.getSource().sendError(Text.literal("§c该命令只能由玩家执行"));
			return 0;
		}
		syncRankTag(p);
		ctx.getSource().sendFeedback(() -> Text.literal("§a等级 tag 已同步：" + p.getCommandTags()), false);
		return 1;
	}

	private static int regenerate(CommandContext<ServerCommandSource> ctx) {
		writePresets();
		ctx.getSource().sendFeedback(() -> Text.literal("§a预设文件已生成到 config/redtale/"
				+ EasyNpcConfig.presetsDir() + "/（已存在的不会覆盖）"), false);
		return 1;
	}
}
