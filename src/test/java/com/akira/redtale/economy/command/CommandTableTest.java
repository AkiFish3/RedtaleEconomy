package com.akira.redtale.economy.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T35 声明式命令注册：只测声明表本身（不建命令树）。
 *
 * <p>重点保护两条实测过的语义：
 * <ul>
 *   <li>reload 会让同一个命令类重复声明 —— 同 owner 重声明<b>不是</b>冲突</li>
 *   <li>两个模块抢同一个根名字 —— 先声明者留，后者记冲突（Brigadier 侧是静默合并，看不出来）</li>
 * </ul>
 */
class CommandTableTest {

	@AfterEach
	void tearDown() {
		CommandTable.resetForTest();
	}

	@Test
	void sameOwnerRedeclareIsNotConflict() {
		CommandTable.declare("shop", "shop");
		CommandTable.declare("shop", "shop");
		assertTrue(CommandTable.isDeclared("shop"));
		assertEquals(List.of(), CommandTable.conflicts());
	}

	@Test
	void differentOwnerKeepsFirstAndRecordsConflict() {
		CommandTable.declare("pass", "battlepass");
		CommandTable.declare("pass", "premium");
		assertEquals(1, CommandTable.entries().size(), "冲突时不应多出条目");
		assertEquals("battlepass", CommandTable.entries().get(0).owner(), "保留先声明者");
		assertEquals(1, CommandTable.conflicts().size());
		assertTrue(CommandTable.conflicts().get(0).contains("/pass"));
	}

	@Test
	void sameConflictIsRecordedOnce() {
		CommandTable.declare("tasks", "task");
		CommandTable.declare("tasks", "bounty");
		CommandTable.declare("tasks", "bounty");
		assertEquals(1, CommandTable.conflicts().size(), "重复声明同一冲突只记一次");
	}

	@Test
	void entriesKeepDeclarationOrderAndResetClearsAll() {
		CommandTable.declare("redtale", "core");
		CommandTable.declare("rt", "core");
		CommandTable.declare("shop", "shop");
		assertEquals(List.of("redtale", "rt", "shop"),
				CommandTable.entries().stream().map(CommandTable.Entry::name).toList());

		CommandTable.resetForTest();
		assertFalse(CommandTable.isDeclared("shop"));
		assertEquals(0, CommandTable.entries().size());
	}

	@Test
	void blankNameIsIgnored() {
		CommandTable.declare("  ", "core");
		CommandTable.declare(null, "core");
		assertEquals(0, CommandTable.entries().size());
	}
}
