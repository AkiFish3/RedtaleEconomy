package com.akira.redtale.economy.storage;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** T18：防小号的纯判定口径（不读配置、不碰盘）。 */
class AltGuardTest {

	private static final long NOW = 1_700_000_000_000L;
	private static final long HOUR = 3_600_000L;

	private static Map<String, AltGuard.Rec> table(Object... kv) {
		Map<String, AltGuard.Rec> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], new AltGuard.Rec((String) kv[i + 1], "", NOW - HOUR));
		}
		return m;
	}

	@Test
	void firstAccountOnIpIsAllowed() {
		assertTrue(AltGuard.evaluate(table(), "u1", "1.1.1.1", NOW, 24, 3).allowed());
	}

	@Test
	void newFaceOverLimitIsDenied() {
		Map<String, AltGuard.Rec> m = table("u1", "1.1.1.1", "u2", "1.1.1.1", "u3", "1.1.1.1");
		AltGuard.Decision d = AltGuard.evaluate(m, "u4", "1.1.1.1", NOW, 24, 3);
		assertFalse(d.allowed());
		assertTrue(d.reason().contains("上限 3"));
	}

	@Test
	void boundaryIsInclusive() {
		Map<String, AltGuard.Rec> m = table("u1", "1.1.1.1", "u2", "1.1.1.1");
		// 2 个已有 + 本人 = 3，正好等于上限 → 放行
		assertTrue(AltGuard.evaluate(m, "u3", "1.1.1.1", NOW, 24, 3).allowed());
	}

	@Test
	void knownAccountIsAlwaysAllowed() {
		Map<String, AltGuard.Rec> m = table("u1", "1.1.1.1", "u2", "1.1.1.1", "u3", "1.1.1.1");
		assertTrue(AltGuard.evaluate(m, "u1", "1.1.1.1", NOW, 24, 3).allowed(), "老玩家不能被关在门外");
	}

	@Test
	void staleRecordsOutsideWindowAreIgnored() {
		Map<String, AltGuard.Rec> m = new HashMap<>();
		m.put("u1", new AltGuard.Rec("1.1.1.1", "", NOW - 48 * HOUR));
		m.put("u2", new AltGuard.Rec("1.1.1.1", "", NOW - 48 * HOUR));
		m.put("u3", new AltGuard.Rec("1.1.1.1", "", NOW - 48 * HOUR));
		assertTrue(AltGuard.evaluate(m, "u4", "1.1.1.1", NOW, 24, 3).allowed());
	}

	@Test
	void differentIpDoesNotCount() {
		Map<String, AltGuard.Rec> m = table("u1", "2.2.2.2", "u2", "2.2.2.2", "u3", "2.2.2.2");
		assertTrue(AltGuard.evaluate(m, "u4", "1.1.1.1", NOW, 24, 3).allowed());
	}

	@Test
	void blankIpIsNeverBlocked() {
		Map<String, AltGuard.Rec> m = table("u1", "1.1.1.1", "u2", "1.1.1.1", "u3", "1.1.1.1");
		assertTrue(AltGuard.evaluate(m, "u4", "", NOW, 24, 3).allowed(), "判定不了就不拦");
	}

	@Test
	void normalizeIpStripsSlash() {
		assertEquals("127.0.0.1", AltGuard.normalizeIp("/127.0.0.1"));
		assertEquals("127.0.0.1", AltGuard.normalizeIp(" 127.0.0.1 "));
		assertEquals("", AltGuard.normalizeIp(null));
		assertEquals("", AltGuard.normalizeIp("unknown"));
	}
}
