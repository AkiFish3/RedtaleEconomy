package com.akira.redtale.economy.ui;

import com.akira.redtale.economy.RedtaleEconomy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 布局写回（R17 / spec §9.4「校验 → 备份 → 原子写」）。
 *
 * <p>写之前先备份成 {@code <id>.json5.bak.<时间戳>}：拖拽编辑器一按错就可能把界面弄乱，
 * 服主必须能一键还原。
 */
public final class LayoutWriter {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private LayoutWriter() {
	}

	/** @return 写出的文件；失败返回 null（错误已记日志） */
	public static Path save(UiLayout layout) {
		Path target = UiLayouts.dir().resolve(layout.id + ".json5");
		try {
			Files.createDirectories(target.getParent());
			if (Files.isRegularFile(target)) {
				Path bak = target.resolveSibling(target.getFileName() + ".bak."
						+ LocalDateTime.now().format(STAMP));
				Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
			}
			Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
			Files.writeString(tmp, layout.toJson(), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception atomicFail) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			UiLayouts.invalidate();
			RedtaleEconomy.LOGGER.info("[ui] 布局已保存：{}", target);
			return target;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[ui] 保存布局 {} 失败：{}", layout.id, e.getMessage());
			return null;
		}
	}
}
