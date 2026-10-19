package com.akira.redtale.economy.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 玩家数据版本兼容（B22）。
 *
 * <p>规则：
 * <ul>
 *   <li>文件版本 == 代码版本 → {@link Verdict#OK}</li>
 *   <li>文件版本 &lt; 代码版本 → {@link Verdict#OLDER}，正常读，写回时升版</li>
 *   <li>文件版本 &gt; 代码版本 → {@link Verdict#NEWER}，<b>只读</b>：备份原文件并拒绝覆盖，
 *       避免「降级运行一次把新版字段全抹掉」</li>
 * </ul>
 *
 * <p>缺 {@code _version} 视为 0（老数据），一律按 OLDER 处理。
 */
public final class DataCompat {

	/** 当前代码写出的账号文件版本。加字段时 +1，并在 migrate 里补默认值。 */
	public static final int ACCOUNT_VERSION = 1;

	public enum Verdict {
		OK, OLDER, NEWER
	}

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private DataCompat() {
	}

	/** 纯函数：仅比大小，便于单测。 */
	public static Verdict verdict(int fileVersion, int codeVersion) {
		if (fileVersion == codeVersion) return Verdict.OK;
		return fileVersion > codeVersion ? Verdict.NEWER : Verdict.OLDER;
	}

	/**
	 * 把一份「版本比代码新」的文件备份到 {@code backup/too-new/}。
	 *
	 * @return 备份路径；失败返回 {@code null}（调用方只记日志，不阻断启动）
	 */
	public static Path backup(String backupRoot, Path file, String tag) {
		if (backupRoot == null || backupRoot.isBlank() || file == null) return null;
		try {
			Path dir = Path.of(backupRoot, "too-new");
			Files.createDirectories(dir);
			String base = file.getFileName().toString();
			Path dst = dir.resolve(base + "." + tag + "." + LocalDateTime.now().format(TS) + ".bak");
			Files.copy(file, dst, StandardCopyOption.REPLACE_EXISTING);
			return dst;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
