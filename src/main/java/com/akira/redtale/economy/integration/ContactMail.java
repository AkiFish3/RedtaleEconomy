package com.akira.redtale.economy.integration;

import com.akira.redtale.economy.RedtaleEconomy;
import com.flechazo.contact.common.item.LetterItem;
import com.flechazo.contact.common.storage.IMailboxDataProvider;
import com.flechazo.contact.common.storage.MailboxDataManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Contact（1.2.x，包 {@code com.flechazo.contact}）信箱适配（R21）。
 *
 * <p><b>单一数据源</b>：物品奖励直接投进 Contact 的信箱内容数组，玩家在邮筒里取走即消失；
 * 我们不二次存储，所以「哪边领了另一边就不显示」天然成立。
 *
 * <p>软依赖：本类只在 {@code isModLoaded("contact")} 为真时被 {@link MailService} 调用，
 * 未安装时不会加载 Contact 的类型。
 */
public final class ContactMail {

	private ContactMail() {
	}

	private static Boolean apiOk;

	/**
	 * Contact 是否可用：不仅要装了 mod，还要有 1.2.1+ 的 API 类。
	 *
	 * <p>线上服装的是 1.2.0-Beta，包名还是 {@code cloud.lemonslice.contact}，
	 * 没有 {@code com.flechazo.contact.common.storage.MailboxDataManager}；
	 * 直接调用会抛 NoClassDefFoundError（HMCL 会误判成崩溃）。所以先探测类是否存在。
	 */
	public static boolean available() {
		if (apiOk != null) return apiOk;
		apiOk = false;
		if (!FabricLoader.getInstance().isModLoaded("contact")) return false;
		try {
			Class.forName("com.flechazo.contact.common.storage.MailboxDataManager");
			apiOk = true;
		} catch (Throwable t) {
			RedtaleEconomy.LOGGER.info("[mail] Contact 已安装但 API 不匹配（需 1.2.1+，当前疑似 1.2.0），邮件改走待领取队列");
		}
		return apiOk;
	}

	/**
	 * 把一组物品包成一封信投进玩家信箱。
	 *
	 * @return true=已投递；false=信箱满/异常（调用方转入待领取队列）
	 */
	public static boolean deliver(MinecraftServer server, UUID target, ItemStack stack, String senderName) {
		if (server == null || stack == null || stack.isEmpty() || !available()) return false;
		try {
			IMailboxDataProvider data = MailboxDataManager.getData(server);
			if (data == null) return false;
			if (data.isMailboxFull(target)) return false;
			SimpleInventory contents = new SimpleInventory(9);
			contents.setStack(0, stack.copy());
			ItemStack letter = LetterItem.getLetter(contents, senderName);
			if (letter == null || letter.isEmpty()) return false;
			return data.addMailboxContents(target, letter);
		} catch (Throwable t) {
			RedtaleEconomy.LOGGER.warn("[mail] 投递 Contact 信件失败，转入待领取：{}", t.toString());
			return false;
		}
	}

	/** 信箱是否已满（未装 Contact 视为满，直接走队列）。 */
	public static boolean isFull(MinecraftServer server, UUID target) {
		if (server == null || !available()) return true;
		try {
			IMailboxDataProvider data = MailboxDataManager.getData(server);
			return data == null || data.isMailboxFull(target);
		} catch (Throwable t) {
			return true;
		}
	}
}
