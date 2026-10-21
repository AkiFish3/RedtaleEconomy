package com.akira.redtale.economy.module.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T02：赛季归档快照（目录级纯操作，不读全局配置）。 */
class PassSeasonsTest {

	@TempDir
	Path dir;

	private void player(String uuid, long xp, boolean premium, int free, int paid) throws Exception {
		JsonObject claimed = new JsonObject();
		var f = new com.google.gson.JsonArray();
		for (int i = 0; i < free; i++) f.add(i + 1);
		claimed.add("free", f);
		var p = new com.google.gson.JsonArray();
		for (int i = 0; i < paid; i++) p.add(i + 1);
		claimed.add("premium", p);
		JsonObject o = new JsonObject();
		o.addProperty("season", "s1");
		o.addProperty("xp", xp);
		o.addProperty("premium", premium);
		o.add("claimed", claimed);
		Files.writeString(dir.resolve(uuid + ".json"), o.toString(), StandardCharsets.UTF_8);
	}

	@Test
	void archiveWritesSortedSnapshot() throws Exception {
		player("aaaa", 100, false, 2, 0);
		player("bbbb", 900, true, 5, 3);
		player("cccc", 400, false, 1, 0);

		assertEquals(PassSeasons.ArchiveResult.OK, PassSeasons.archiveDir(dir, "s1", 1_700_000_000_000L));

		Path snap = dir.resolve(PassSeasons.SNAPSHOT);
		assertTrue(Files.isRegularFile(snap));
		JsonObject o = JsonParser.parseString(Files.readString(snap, StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("s1", o.get("season").getAsString());
		assertEquals(3, o.get("players").getAsInt());
		assertEquals(1400L, o.get("totalXp").getAsLong());
		assertEquals(1, o.get("premium").getAsInt());
		assertEquals(8, o.get("claimedFree").getAsInt());
		assertEquals(3, o.get("claimedPremium").getAsInt());
		var entries = o.getAsJsonArray("entries");
		assertEquals(3, entries.size());
		assertEquals("bbbb", entries.get(0).getAsJsonObject().get("uuid").getAsString(), "按 xp 降序");
		assertEquals("aaaa", entries.get(2).getAsJsonObject().get("uuid").getAsString());
		assertFalse(Files.exists(dir.resolve(PassSeasons.SNAPSHOT + ".tmp")));
	}

	@Test
	void archiveIsIdempotent() throws Exception {
		player("aaaa", 10, false, 0, 0);
		assertEquals(PassSeasons.ArchiveResult.OK, PassSeasons.archiveDir(dir, "s1", 1L));
		assertEquals(PassSeasons.ArchiveResult.ALREADY, PassSeasons.archiveDir(dir, "s1", 2L),
				"第二次归档不许覆盖已有快照");
	}

	@Test
	void underscoreFilesAndBadJsonAreSkipped() throws Exception {
		player("aaaa", 50, false, 0, 0);
		Files.writeString(dir.resolve("_snapshot_old.json"), "{\"players\":0}", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("broken.json"), "not json", StandardCharsets.UTF_8);
		assertEquals(PassSeasons.ArchiveResult.OK, PassSeasons.archiveDir(dir, "s1", 1L));
		JsonObject o = JsonParser.parseString(
				Files.readString(dir.resolve(PassSeasons.SNAPSHOT), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(1, o.get("players").getAsInt(), "坏档与下划线文件都不能算玩家");
	}

	@Test
	void missingDirReturnsNoData() {
		assertEquals(PassSeasons.ArchiveResult.NO_DATA,
				PassSeasons.archiveDir(dir.resolve("nope"), "s1", 1L));
	}
}
