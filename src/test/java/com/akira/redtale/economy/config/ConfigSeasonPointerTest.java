package com.akira.redtale.economy.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 回归：战令赛季指针的<b>写路径必须等于读路径</b>。
 *
 * <p>2026-10-21 实机 bug（第 N+33 轮）：{@code BattlePassModule.rolloverTo} 写根键
 * {@code seasonId}，而 {@link BattlePassConfig#seasonId()} 读 {@code season.id}
 * ⇒ {@code /pass rollover S2} 打印「赛季 S1 → S2」成功，实际仍跑 S1，
 * 且归档跟着 {@code NO_DATA}（找不到旧赛季的玩家数据目录），
 * 命令面与落盘全部「看起来成功」。这类静默失败只能靠回读断言挡住。
 *
 * <p>每个用例自己 seed 一份配置，避免用执行顺序换稳定性。
 */
class ConfigSeasonPointerTest {

	@TempDir
	static Path dir;

	@BeforeAll
	static void setUp() {
		ConfigManager.createAt(dir);
	}

	@AfterAll
	static void tearDown() {
		ConfigManager.clearInstance();
	}

	/** 把战令配置重置成「当前赛季 = id」，并丢弃内存里的旧根。 */
	private static void seed(String id) throws Exception {
		String json = """
				{
				  "_version": 1,
				  "enabled": true,
				  "season": {"id": "%s", "name": "测试赛季", "start": "", "end": ""}
				}
				""".formatted(id);
		Files.writeString(dir.resolve(BattlePassConfig.FILE), json, StandardCharsets.UTF_8);
		ConfigManager.get().define(BattlePassConfig.FILE, BattlePassConfig.SCHEMA);
		BattlePassConfig.store().load();
	}

	@Test
	void seasonPointerIsReadFromNestedSeasonId() throws Exception {
		seed("S1");
		assertEquals("S1", BattlePassConfig.seasonId());
	}

	@Test
	void writingDottedPathIsReadBackBySameGetter() throws Exception {
		seed("S1");
		ConfigStore store = ConfigManager.get().store(BattlePassConfig.FILE);
		store.setPath("season.id", "S7");

		// 不落盘：save(true) 会触发 schema 校验链上的 Minecraft Bootstrap（单测环境 "invoked too early?"）。
		// 这里要证明的是「同一个点号路径写得进、读得出」，落盘由 runServer 实测覆盖。
		assertEquals("S7", BattlePassConfig.seasonId(),
				"写 season.id 后必须能读回 —— 读写路径不同就是静默失败（本轮实机 bug）");
		assertEquals(0, store.getPath("seasonId") == null ? 0 : 1, "根键 seasonId 不该被写");
	}

	@Test
	void rootLevelSeasonIdKeyIsNotThePointer() throws Exception {
		// 反向证明：只写根键（旧实现的写法）不会改变读到的赛季
		seed("S1");
		ConfigStore store = ConfigManager.get().store(BattlePassConfig.FILE);
		store.setPath("seasonId", "S9");

		assertNotEquals("S9", BattlePassConfig.seasonId(),
				"根 seasonId 不是真指针；若这里相等，说明读侧改成了根键，需同步 rolloverTo");
		assertEquals("S1", BattlePassConfig.seasonId());
	}
}
