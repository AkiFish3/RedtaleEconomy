package com.akira.redtale.economy.module;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T35：ModuleEvents 的隔离性与顺序。
 *
 * <p>要证明的两件事（以前散着注册时都不成立）：
 * <ol>
 *   <li>一个钩子抛异常，<b>后面的钩子照旧执行</b> —— 这是集中化的主要动机；</li>
 *   <li>执行顺序 = 注册顺序，可复现。</li>
 * </ol>
 *
 * <p>不需要 Minecraft 运行时：dispatch 只在钩子里用到 player，这里传 null。
 */
class ModuleEventsTest {

	@AfterEach
	void cleanup() {
		ModuleEvents.resetForTest();
	}

	private static ModuleEvents.PlayerHook record(List<String> sink, String name) {
		return p -> sink.add(name);
	}

	@Test
	void throwingHookDoesNotBlockLaterHooks() {
		List<String> seen = new ArrayList<>();
		List<ModuleEvents.Named<ModuleEvents.PlayerHook>> hooks = List.of(
				new ModuleEvents.Named<>("bad", p -> {
					throw new IllegalStateException("boom");
				}),
				new ModuleEvents.Named<>("after1", record(seen, "after1")),
				new ModuleEvents.Named<>("after2", record(seen, "after2")));

		ModuleEvents.dispatch(hooks, "join", null);

		assertEquals(List.of("after1", "after2"), seen,
				"第一个钩子抛异常后，后面的钩子必须照旧执行（旧实现会让同事件后续模块全跳过）");
	}

	@Test
	void hooksRunInRegistrationOrder() {
		List<String> seen = new ArrayList<>();
		List<ModuleEvents.Named<ModuleEvents.PlayerHook>> hooks = List.of(
				new ModuleEvents.Named<>("bounty", record(seen, "bounty")),
				new ModuleEvents.Named<>("guild", record(seen, "guild")),
				new ModuleEvents.Named<>("easynpc", record(seen, "easynpc")));

		ModuleEvents.dispatch(hooks, "join", null);

		assertEquals(List.of("bounty", "guild", "easynpc"), seen);
	}

	@Test
	void describeListsEveryKind() {
		String d = ModuleEvents.describe();
		assertTrue(d.startsWith("join:"), "describe 必须以 join: 开头，实际=" + d);
		assertTrue(d.contains(";quit:"), "缺 quit 段：" + d);
		assertTrue(d.contains(";world:"), "缺 world 段：" + d);
		assertTrue(d.contains(";block:"), "缺 block 段：" + d);
	}

	@Test
	void resetClearsEverything() {
		List<ModuleEvents.Named<ModuleEvents.PlayerHook>> hooks = List.of(
				new ModuleEvents.Named<>("x", record(new ArrayList<>(), "x")));
		ModuleEvents.dispatch(hooks, "quit", null);
		ModuleEvents.resetForTest();
		assertEquals("join:;quit:;world:;block:", ModuleEvents.describe());
	}
}
