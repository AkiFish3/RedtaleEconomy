package com.akira.redtale.economy.module;

import com.akira.redtale.economy.module.impl.CheckinModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** B08：签到档位循环（loop 回绕）的纯逻辑部分。 */
class CheckinLoopTest {

	@Test
	void loopWrapsAtPeriod() {
		assertEquals(1, CheckinModule.loopDay(1, 7));
		assertEquals(5, CheckinModule.loopDay(5, 7));
		assertEquals(7, CheckinModule.loopDay(7, 7));
		assertEquals(1, CheckinModule.loopDay(8, 7));
		assertEquals(7, CheckinModule.loopDay(14, 7));
		assertEquals(1, CheckinModule.loopDay(15, 7));
	}

	@Test
	void loopOneAlwaysFirstDay() {
		assertEquals(1, CheckinModule.loopDay(1, 1));
		assertEquals(1, CheckinModule.loopDay(99, 1));
	}

	@Test
	void nonPositiveInputsClampToOne() {
		assertEquals(1, CheckinModule.loopDay(0, 7));
		assertEquals(1, CheckinModule.loopDay(-5, 7));
		assertEquals(1, CheckinModule.loopDay(3, 0));
	}
}
