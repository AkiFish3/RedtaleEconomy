package com.akira.redtale.economy.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R37：离线服改名（旧 UUID → 新 UUID）的规划 / 复制 / 内容改写。 */
class PlayerRenameTest {

	@TempDir
	Path root;

	private static final String OLD = "Akiko";
	private static final String NEW = "Aki";

	@Test
	void offlineUuidMatchesVanillaFormula() {
		UUID expect = UUID.nameUUIDFromBytes(("OfflinePlayer:" + OLD).getBytes(StandardCharsets.UTF_8));
		assertEquals(expect, PlayerRename.offlineUuid(OLD));
	}

	@Test
	void planFindsUuidFilesInAnyDepthAndCopiesThem() throws IOException {
		String old = PlayerRename.offlineUuid(OLD).toString();
		String neu = PlayerRename.offlineUuid(NEW).toString();
		write(root.resolve("accounts/" + old + ".json"), "{\"uuid\":\"" + old + "\",\"name\":\"" + OLD + "\"}");
		write(root.resolve("warehouse/" + old + ".json"), "{\"used\":3}");
		write(root.resolve("battlepass/s1/" + old + ".json"), "{\"level\":7}");
		write(root.resolve("accounts/other.json"), "{\"name\":\"Nobody\"}");

		var plan = PlayerRename.plan(root, OLD, NEW);
		assertEquals(3, plan.items().size(), "三个带旧 UUID 的文件都要进计划");
		assertEquals(0, plan.conflicts());
		assertFalse(plan.empty());

		var res = PlayerRename.execute(root, plan);
		assertEquals(3, res.copied());
		assertEquals(0, res.failed());
		assertTrue(res.aliasWritten(), "别名表应写入 accounts/_identities.json");

		String acc = Files.readString(root.resolve("accounts/" + neu + ".json"));
		assertTrue(acc.contains(neu), "内容里的旧 UUID 要换掉");
		assertTrue(acc.contains("\"" + NEW + "\""), "name 字段要换掉");
		assertFalse(acc.contains(old));
		assertTrue(Files.isRegularFile(root.resolve("warehouse/" + neu + ".json")));
		assertTrue(Files.isRegularFile(root.resolve("battlepass/s1/" + neu + ".json")));
		// 源文件保留（可回退）
		assertTrue(Files.isRegularFile(root.resolve("accounts/" + old + ".json")));
		// 无关文件不动
		assertFalse(Files.exists(root.resolve("accounts/other-" + neu + ".json")));
		assertTrue(Files.readString(root.resolve("accounts/other.json")).contains("Nobody"));
		// 别名
		String ids = Files.readString(root.resolve("accounts/_identities.json"));
		assertTrue(ids.contains(old) && ids.contains(neu));
	}

	@Test
	void existingTargetIsConflictNotOverwrite() throws IOException {
		String old = PlayerRename.offlineUuid(OLD).toString();
		String neu = PlayerRename.offlineUuid(NEW).toString();
		write(root.resolve("accounts/" + old + ".json"), "{\"v\":1}");
		write(root.resolve("accounts/" + neu + ".json"), "{\"v\":2}");

		var plan = PlayerRename.plan(root, OLD, NEW);
		assertEquals(1, plan.conflicts());
		assertTrue(plan.empty());
		PlayerRename.execute(root, plan);
		assertEquals("{\"v\":2}", Files.readString(root.resolve("accounts/" + neu + ".json")).trim());
	}

	@Test
	void rewriteHandlesNamesAndNonJson() {
		String old = "11111111-2222-3333-4444-555555555555";
		String neu = "99999999-8888-7777-6666-555555555555";
		String json = "{\"uuid\":\"" + old + "\",\"posterName\":\"" + OLD + "\",\"other\":\"" + OLD + "\"}";
		String out = PlayerRename.rewrite(json, old, neu, OLD, NEW);
		assertTrue(out.contains(neu));
		assertTrue(out.contains("\"posterName\": \"" + NEW + "\"") || out.contains("\"posterName\":\"" + NEW + "\""));
		assertTrue(out.contains("\"other\": \"" + OLD + "\"") || out.contains("\"other\":\"" + OLD + "\""),
				"非白名单字段不该被改");

		// jsonl（整体不是 JSON）：只做 UUID 替换，不炸
		String lines = "{\"uuid\":\"" + old + "\"}\n{\"uuid\":\"" + old + "\"}\n";
		String o2 = PlayerRename.rewrite(lines, old, neu, OLD, NEW);
		assertFalse(o2.contains(old));
		assertEquals(2, o2.split("\n").length);
	}

	@Test
	void statsReadsChecklistFields() throws IOException {
		UUID u = PlayerRename.offlineUuid(OLD);
		String id = u.toString();
		write(root.resolve("warehouse/" + id + ".json"), "{\"slots\":27,\"purchased\":2}");
		write(root.resolve("checkin/" + id + ".json"), "{\"streak\":12,\"totalDays\":40}");
		write(root.resolve("guild/" + id + ".json"), "{\"rep\":880}");
		write(root.resolve("battlepass/s3/" + id + ".json"), "{\"xp\":450,\"premium\":true}");
		write(root.resolve("battlepass/s2/" + id + ".json"), "{\"xp\":900}");

		var st = PlayerRename.stats(root, u);
		assertEquals(27, st.warehouseSlots());
		assertEquals(2, st.warehousePurchased());
		assertEquals(12, st.checkinStreak());
		assertEquals(40, st.checkinTotal());
		assertEquals(900, st.passXp(), "跨赛季取最高 xp");
		assertTrue(st.passPremium());
		assertEquals(880, st.guildRep());
		assertEquals(5, st.dataFiles());
	}

	@Test
	void statsOnEmptyRootIsZero() {
		var st = PlayerRename.stats(root, PlayerRename.offlineUuid("Nobody"));
		assertEquals(0, st.warehouseSlots());
		assertEquals(0, st.passXp());
		assertEquals(0, st.dataFiles());
	}

	private static void write(Path p, String text) throws IOException {
		Files.createDirectories(p.getParent());
		Files.writeString(p, text, StandardCharsets.UTF_8);
	}
}
