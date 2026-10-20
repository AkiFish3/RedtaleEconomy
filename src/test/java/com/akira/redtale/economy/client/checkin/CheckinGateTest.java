package com.akira.redtale.economy.client.checkin;

import com.akira.redtale.economy.net.CheckinProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H04：签到防连点闸门（同日只成功一次、点格子不签到）。 */
class CheckinGateTest {

	private static CheckinProtocol.Point p(int day, boolean done, boolean current) {
		return new CheckinProtocol.Point(day, 100, done, current);
	}

	@Test
	void detectsSignedToday() {
		assertTrue(CheckinGate.alreadySigned(List.of(p(1, true, false), p(2, true, true))));
		assertFalse(CheckinGate.alreadySigned(List.of(p(1, true, false), p(2, false, true))));
		assertFalse(CheckinGate.alreadySigned(List.of()));
		assertFalse(CheckinGate.alreadySigned(null));
	}

	@Test
	void cannotClaimTwiceInCooldown() {
		long t = 1_000_000L;
		assertTrue(CheckinGate.canClaim(false, 0L, t));
		assertFalse(CheckinGate.canClaim(false, t, t + 10));
		assertFalse(CheckinGate.canClaim(false, t, t + CheckinGate.COOLDOWN_MS - 1));
		assertTrue(CheckinGate.canClaim(false, t, t + CheckinGate.COOLDOWN_MS));
	}

	@Test
	void signedDisablesButtonRegardlessOfCooldown() {
		assertFalse(CheckinGate.canClaim(true, 0L, 5_000L));
		assertFalse(CheckinGate.canClaim(true, 1_000L, 9_999_999L));
	}
}
