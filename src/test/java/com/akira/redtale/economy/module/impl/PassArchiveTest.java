package com.akira.redtale.economy.module.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T03：历史赛季补领（快照读取 + 权限档位 + 补领状态落盘）。 */
class PassArchiveTest {

	@TempDir
	Path dir;

	private static final UUID PAID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID FREE = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID ABSENT = UUID.fromString("33333333-3333-3333-3333-333333333333");

	/** 测试曲线：每 100 xp 一级。 */
	private static final PassArchive.LevelCurve CURVE = xp -> (int) (xp / 100) + 1;

	private void snapshot(Path seasonDir, Object... rows) throws Exception {
		Files.createDirectories(seasonDir);
		JsonArray entries = new JsonArray();
		for (int i = 0; i < rows.length; i += 3) {
			JsonObject o = new JsonObject();
			o.addProperty("uuid", rows[i].toString());
			o.addProperty("xp", (Long) rows[i + 1]);
			o.addProperty("premium", (Boolean) rows[i + 2]);
			entries.add(o);
		}
		JsonObject snap = new JsonObject();
		snap.addProperty("_version", 1);
		snap.addProperty("season", seasonDir.getFileName().toString());
		snap.add("entries", entries);
		Files.writeString(seasonDir.resolve(PassSeasons.SNAPSHOT), snap.toString(), StandardCharsets.UTF_8);
	}

	private PassArchive.Result claim(UUID uuid, String track, int level, PassArchive.Access access) {
		return PassArchive.claim(dir, uuid, track, level, access, CURVE, 30, 1_700_000_000_000L);
	}

	@Test
	void offModeRejectsEverything() throws Exception {
		snapshot(dir, PAID, 900L, true);
		assertEquals(PassArchive.Result.OFF, claim(PAID, "free", 1, PassArchive.Access.OFF));
		assertEquals(PassArchive.Result.OFF, claim(PAID, "free", 1, PassArchive.Access.parse("whatever")));
		assertFalse(Files.exists(dir.resolve(PassArchive.CLAIMS)), "拒绝时不应写状态文件");
	}

	@Test
	void missingSnapshotIsNotArchived() {
		assertFalse(PassArchive.archived(dir));
		assertEquals(PassArchive.Result.NO_SNAPSHOT, claim(PAID, "free", 1, PassArchive.Access.ALL));
		assertTrue(PassArchive.entries(dir).isEmpty());
	}

	@Test
	void purchasedModeOnlyAllowsPremiumBuyers() throws Exception {
		snapshot(dir, PAID, 900L, true, FREE, 900L, false);
		assertEquals(PassArchive.Result.NOT_ENTITLED, claim(FREE, "free", 1, PassArchive.Access.PURCHASED));
		assertEquals(PassArchive.Result.OK, claim(PAID, "free", 1, PassArchive.Access.PURCHASED));
	}

	@Test
	void allModeStillGatesPremiumTrack() throws Exception {
		snapshot(dir, FREE, 500L, false);
		assertEquals(PassArchive.Result.OK, claim(FREE, "free", 2, PassArchive.Access.ALL));
		assertEquals(PassArchive.Result.LOCKED, claim(FREE, "premium", 1, PassArchive.Access.ALL));
	}

	@Test
	void levelBeyondSnapshotXpIsRejected() throws Exception {
		snapshot(dir, PAID, 250L, true);   // 曲线 ⇒ 3 级
		assertEquals(PassArchive.Result.OK, claim(PAID, "free", 3, PassArchive.Access.ALL));
		assertEquals(PassArchive.Result.NOT_REACHED, claim(PAID, "free", 4, PassArchive.Access.ALL));
		assertEquals(PassArchive.Result.BAD_LEVEL, claim(PAID, "free", 99, PassArchive.Access.ALL));
		assertEquals(PassArchive.Result.BAD_LEVEL, claim(PAID, "free", 0, PassArchive.Access.ALL));
	}

	@Test
	void duplicateClaimIsIdempotentAndPersisted() throws Exception {
		snapshot(dir, PAID, 900L, true);
		assertEquals(PassArchive.Result.OK, claim(PAID, "premium", 2, PassArchive.Access.ALL));
		assertEquals(PassArchive.Result.ALREADY, claim(PAID, "premium", 2, PassArchive.Access.ALL));

		Path f = dir.resolve(PassArchive.CLAIMS);
		assertTrue(Files.isRegularFile(f));
		JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
		JsonArray arr = o.getAsJsonObject("players").getAsJsonObject(PAID.toString()).getAsJsonArray("premium");
		assertEquals(1, arr.size(), "重复领取不应重复记录");
		assertEquals(2, arr.get(0).getAsInt());
		assertFalse(Files.exists(dir.resolve(PassArchive.CLAIMS + ".tmp")), "临时文件必须被移走");
	}

	@Test
	void claimableSkipsClaimedAndRespectsAccess() throws Exception {
		snapshot(dir, PAID, 250L, true, FREE, 250L, false);
		assertEquals(List.of(1, 2, 3), PassArchive.claimable(dir, PAID, PassArchive.Access.ALL, CURVE, 30));
		assertEquals(PassArchive.Result.OK, claim(PAID, "free", 1, PassArchive.Access.ALL));
		assertEquals(List.of(2, 3), PassArchive.claimable(dir, PAID, PassArchive.Access.ALL, CURVE, 30));
		assertTrue(PassArchive.claimable(dir, FREE, PassArchive.Access.PURCHASED, CURVE, 30).isEmpty(),
				"purchased 档位对未付费玩家应返回空表");
	}

	@Test
	void unknownPlayerInSnapshotCannotClaim() throws Exception {
		snapshot(dir, PAID, 900L, true);
		assertEquals(PassArchive.Result.NOT_FOUND, claim(ABSENT, "free", 1, PassArchive.Access.ALL));
		assertTrue(PassArchive.claimable(dir, ABSENT, PassArchive.Access.ALL, CURVE, 30).isEmpty());
	}

	@Test
	void snapshotEntriesAreReadBack() throws Exception {
		snapshot(dir, PAID, 900L, true, FREE, 100L, false);
		List<PassArchive.Entry> list = PassArchive.entries(dir);
		assertEquals(2, list.size());
		assertTrue(PassArchive.archived(dir));
		assertEquals(900L, PassArchive.entryOf(dir, PAID).xp());
		assertTrue(PassArchive.entryOf(dir, PAID).premium());
		assertFalse(PassArchive.entryOf(dir, FREE).premium());
	}

	@Test
	void accessParseIsConservative() {
		assertEquals(PassArchive.Access.OFF, PassArchive.Access.parse(null));
		assertEquals(PassArchive.Access.OFF, PassArchive.Access.parse(""));
		assertEquals(PassArchive.Access.OFF, PassArchive.Access.parse("yes"));
		assertEquals(PassArchive.Access.PURCHASED, PassArchive.Access.parse("Purchased"));
		assertEquals(PassArchive.Access.ALL, PassArchive.Access.parse(" ALL "));
	}
}
