package com.akira.redtale.economy.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q6：迁移链按版本顺序执行，缺步骤就停（不跳级、不猜）。 */
class ConfigMigrationsTest {

	@Test
	void noStepsMeansStayAtSourceVersion() {
		JsonObject o = new JsonObject();
		assertEquals(1, ConfigMigrations.migrate(o, 1, 3));
	}

	@Test
	void chainsStepsInOrder() {
		StringBuilder trace = new StringBuilder();
		// 用高版本号，避免与真实迁移表冲突
		ConfigMigrations.register(new ConfigMigrations.Step() {
			@Override
			public int to() {
				return 91;
			}

			@Override
			public void apply(JsonObject root) {
				trace.append("a");
				root.addProperty("v91", true);
			}
		});
		ConfigMigrations.register(new ConfigMigrations.Step() {
			@Override
			public int to() {
				return 92;
			}

			@Override
			public void apply(JsonObject root) {
				trace.append("b");
				root.addProperty("v92", true);
			}
		});
		JsonObject o = new JsonObject();
		assertEquals(92, ConfigMigrations.migrate(o, 90, 92));
		assertEquals("ab", trace.toString());
		assertTrue(o.get("v91").getAsBoolean());
		assertTrue(o.get("v92").getAsBoolean());
	}

	@Test
	void stopsWhenStepMissing() {
		JsonObject o = new JsonObject();
		// v95 没有注册 ⇒ 从 94 出发只能停住
		assertEquals(94, ConfigMigrations.migrate(o, 94, 96));
	}
}
