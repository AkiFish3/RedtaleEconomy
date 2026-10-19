package com.akira.redtale.economy.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B22：玩家数据版本兼容的纯函数测试。
 *
 * <p>规则：缺 {@code _version} 的旧文件按 0 处理 → OLDER（补默认值）；
 * 版本比代码新的文件 → NEWER（先备份再按已知字段读，绝不覆盖写回）。
 */
class DataCompatTest {

	@Test
	void sameVersionIsOk() {
		assertEquals(DataCompat.Verdict.OK, DataCompat.verdict(1, 1));
		assertEquals(DataCompat.Verdict.OK, DataCompat.verdict(0, 0));
	}

	@Test
	void olderFileIsUpgraded() {
		// 老数据（含完全没有 _version 的 0）一律算 OLDER，按默认值补齐即可
		assertEquals(DataCompat.Verdict.OLDER, DataCompat.verdict(0, 1));
		assertEquals(DataCompat.Verdict.OLDER, DataCompat.verdict(1, 2));
	}

	@Test
	void newerFileIsNeverDowngraded() {
		// 版本比代码新：必须识别成 NEWER，调用方会先备份，避免被旧代码写坏
		assertEquals(DataCompat.Verdict.NEWER, DataCompat.verdict(2, 1));
		assertEquals(DataCompat.Verdict.NEWER, DataCompat.verdict(99, DataCompat.ACCOUNT_VERSION));
	}

	@Test
	void accountVersionIsStable() {
		// 加字段才允许 +1；这条断言是提醒，不是限制
		assertEquals(1, DataCompat.ACCOUNT_VERSION);
	}
}
