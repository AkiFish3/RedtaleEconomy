package com.akira.redtale.economy.command;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 反馈通道（U7）。
 *
 * <p>四类信息走四个地方，避免一件事刷三行聊天：
 * <ul>
 *   <li>{@link #success} 一次性成功 → 动作栏（actionbar），不占聊天；</li>
 *   <li>{@link #list} 列表/多行结果 → 聊天（后续接 GUI 时改这里一处即可）；</li>
 *   <li>{@link #error} 失败 → 聊天（红色，sendError）；</li>
 *   <li>{@link #milestone} 里程碑（升级/成就）→ 屏幕标题式提示（1.20.1 服务端没有 toast 包，
 *       用大标题 + 副标题替代，效果更醒目且不需要客户端改动）。</li>
 * </ul>
 */
public final class Feedback {

	private Feedback() {
	}

	/** 成功：只出 1 行动作栏。 */
	public static void success(ServerCommandSource src, Text text) {
		ServerPlayerEntity p = src.getPlayer();
		if (p != null) {
			p.sendMessage(text, true);
		} else {
			src.sendFeedback(() -> text, false);
		}
	}

	/** 列表/详情：聊天。 */
	public static void list(ServerCommandSource src, Text text) {
		src.sendFeedback(() -> text, false);
	}

	/** 失败：聊天（红色）。 */
	public static void error(ServerCommandSource src, Text text) {
		src.sendError(text);
	}

	/** 里程碑：屏幕中上方大标题，1.5 秒淡入淡出。 */
	public static void milestone(ServerPlayerEntity player, Text text) {
		if (player == null) return;
		player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(text));
		player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(
				Text.empty()));
		player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(10, 40, 10));
	}
}
