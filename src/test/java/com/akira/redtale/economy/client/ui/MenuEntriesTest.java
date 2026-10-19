package com.akira.redtale.economy.client.ui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U4 主面板入口表的结构校验（不依赖 Minecraft 运行时）。 */
class MenuEntriesTest {

	/** 服务端已注册、且玩家侧可用的命令（对照 command 包的 literal(...)）。 */
	private static final Set<String> SERVER_COMMANDS = Set.of("bal", "checkin", "tasks", "shop", "storage");

	@Test
	void entriesInDeclaredOrder() {
		List<String> keys = MenuEntries.all().stream().map(MenuEntries.Entry::key).toList();
		assertEquals(List.of("balance", "checkin", "tasks", "shop", "mail", "season", "warehouse"), keys);
	}

	@Test
	void keysAreUniqueAndNonBlank() {
		Set<String> seen = new HashSet<>();
		for (MenuEntries.Entry e : MenuEntries.all()) {
			assertFalse(e.key().isBlank());
			assertTrue(seen.add(e.key()), "重复 key: " + e.key());
		}
	}

	@Test
	void everyReadyEntryPointsAtARegisteredCommand() {
		for (MenuEntries.Entry e : MenuEntries.all()) {
			if (!e.ready()) continue;
			assertTrue(SERVER_COMMANDS.contains(e.command()),
					"ready 入口 " + e.key() + " 指向未注册命令: " + e.command());
		}
	}

	@Test
	void unreadyEntriesAreExactlyMailAndSeason() {
		Set<String> closed = new HashSet<>();
		for (MenuEntries.Entry e : MenuEntries.all()) {
			if (!e.ready()) closed.add(e.key());
		}
		assertEquals(Set.of("mail", "season"), closed);
	}

	@Test
	void readyListMatchesFlags() {
		assertEquals(5, MenuEntries.ready().size(), "U4 + R27：余额/签到/任务/商店/仓储");
		assertTrue(MenuEntries.ready().stream().allMatch(MenuEntries.Entry::ready));
	}

	@Test
	void byKeyFindsAndMisses() {
		assertNotNull(MenuEntries.byKey("balance"));
		assertEquals("bal", MenuEntries.byKey("balance").command());
		assertNull(MenuEntries.byKey("nope"));
	}

	@Test
	void keybindNamesCoverMenuPlusEveryEntry() {
		List<String> names = MenuEntries.keybindNames();
		assertEquals(8, names.size(), "U5 7 个动作 + R27 仓储入口");
		assertEquals("menu", names.get(0));
		assertEquals(MenuEntries.all().size() + 1, names.size());
		assertEquals(names.size(), new HashSet<>(names).size(), "动作名不能重复");
	}

	@Test
	void everyActionHasKeybindLabelInBothLanguages() throws Exception {
		for (String code : List.of("zh_cn", "en_us")) {
			String json = java.nio.file.Files.readString(
					java.nio.file.Path.of("src/main/resources/assets/redtale_economy/lang/" + code + ".json"),
					java.nio.charset.StandardCharsets.UTF_8);
			for (String name : MenuEntries.keybindNames()) {
				String key = "key.redtale_economy." + name;
				assertTrue(json.contains("\"" + key + "\""), code + " 缺少 " + key);
			}
		}
	}

	@Test
	void commandsHaveNoLeadingSlash() {
		for (MenuEntries.Entry e : MenuEntries.all()) {
			assertFalse(e.command().startsWith("/"), "命令不应带斜杠: " + e.command());
			assertFalse(e.command().isBlank());
		}
	}

	@Test
	void everyEntryHasLabelInBothLanguages() throws Exception {
		for (String code : List.of("zh_cn", "en_us")) {
			String json = java.nio.file.Files.readString(
					java.nio.file.Path.of("src/main/resources/assets/redtale_economy/lang/" + code + ".json"),
					java.nio.charset.StandardCharsets.UTF_8);
			for (MenuEntries.Entry e : MenuEntries.all()) {
				String key = "gui.redtale_economy.menu." + e.key();
				assertTrue(json.contains("\"" + key + "\""), code + " 缺少 " + key);
			}
			for (String key : List.of("gui.redtale_economy.menu.title",
					"gui.redtale_economy.menu.hint",
					"gui.redtale_economy.menu.footer",
					"gui.redtale_economy.menu.close",
					"gui.redtale_economy.menu.soon",
					"key.redtale_economy.menu",
					"category.redtale_economy")) {
				assertTrue(json.contains("\"" + key + "\""), code + " 缺少 " + key);
			}
		}
	}
}
