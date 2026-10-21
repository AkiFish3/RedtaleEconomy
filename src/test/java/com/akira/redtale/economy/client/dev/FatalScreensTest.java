package com.akira.redtale.economy.client.dev;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T52：致命界面判定的负样本必须能过 —— 判宽了会把「连接失败退回标题界面」
 * 也当成致命，整轮截图直接中止，比原先的傻等更糟。
 */
class FatalScreensTest {

	@Test
	void noticeAndCrashAreFatal() {
		// 1.20.1 yarn 的真实类名（javap 核对过；没有 ErrorScreen/CrashScreen）
		assertTrue(FatalScreens.matches("net.minecraft.client.gui.screen.FatalErrorScreen"));
		assertTrue(FatalScreens.matches("net.minecraft.client.gui.screen.NoticeScreen"));
		assertTrue(FatalScreens.matches("net.minecraft.client.gui.screen.OutOfMemoryScreen"));
	}

	@Test
	void disconnectScreenIsExempt() {
		List<String> chain = List.of(
				"net.minecraft.client.gui.screen.DisconnectedScreen",
				"net.minecraft.client.gui.screen.Screen");
		assertFalse(FatalScreens.matches("net.minecraft.client.gui.screen.DisconnectedScreen"));
		assertFalse(FatalScreens.matchesChain(chain));
	}

	@Test
	void ourOwnPagesAreNotFatal() {
		assertFalse(FatalScreens.matches("com.akira.redtale.economy.client.ui.hub.HubScreen"));
		assertFalse(FatalScreens.matches("com.akira.redtale.economy.client.tasks.TaskScreen"));
		assertFalse(FatalScreens.matchesChain(List.of("com.akira.redtale.economy.client.dev.UiShots")));
	}

	@Test
	void nullIsSafe() {
		assertFalse(FatalScreens.matches(null));
		assertFalse(FatalScreens.matchesChain(null));
		assertEquals("unknown screen", FatalScreens.hint(null));
	}

	@Test
	void hintPointsAtTheRightNextStep() {
		assertTrue(FatalScreens.hint("x.FatalErrorScreen").contains("latest.log"));
		assertTrue(FatalScreens.hint("x.NoticeScreen").contains("_mod_deps_check"));
		assertTrue(FatalScreens.hint("x.OutOfMemoryScreen").contains("Xmx"));
	}
}
