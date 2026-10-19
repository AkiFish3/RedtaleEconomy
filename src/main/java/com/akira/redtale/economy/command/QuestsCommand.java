package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.config.FtbqConfig;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * {@code /quests} —— FTB Quests 的快捷入口（R9，spec §6 展示侧）。
 *
 * <p>只做「跳转」，不代领奖励（v0.4 §8 定案）：实际执行 {@code ftbq.json → entryCommand}
 * （默认 {@code ftbquests open}）。FTBQ 版本不同命令名不一样时改配置即可，不用改代码。
 *
 * <p>刻意<b>不</b>引用 {@code FtbqIntegration}（那个类持有 FTBQ 类型引用），
 * 这样未装 FTBQ 的服务器加载本类也不会触发 NoClassDefFoundError。
 */
public final class QuestsCommand {

	private QuestsCommand() {
	}

	public static LiteralArgumentBuilder<ServerCommandSource> build(int perm) {
		return CommandManager.literal("quests")
				.requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(QuestsCommand::open);
	}

	public static boolean available() {
		return FabricLoader.getInstance().isModLoaded("ftbquests")
				&& FtbqConfig.enabled() && FtbqConfig.showEntry();
	}

	private static int open(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		if (!available()) {
			src.sendError(Text.literal("§c未安装 FTB Quests 或已在 ftbq.json 关闭入口"));
			return 0;
		}
		String cmd = FtbqConfig.entryCommand().trim();
		if (cmd.startsWith("/")) cmd = cmd.substring(1);
		if (cmd.isEmpty()) {
			src.sendError(Text.literal("§cftbq.json → entryCommand 为空"));
			return 0;
		}
		try {
			src.getServer().getCommandManager().executeWithPrefix(src, cmd);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c转发失败：" + e.getMessage()
					+ "（FTBQ 版本可能改了命令名，改 ftbq.json → entryCommand）"));
			return 0;
		}
	}
}
