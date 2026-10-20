package com.akira.redtale.economy.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R39：布局声明的音效档必须能被解析并原样写回。 */
class UiSoundProfileTest {

	@Test
	void defaultIsVanilla() {
		UiLayout l = UiLayout.parse("t", "{\"width\":320,\"height\":220,\"children\":[]}");
		assertEquals("vanilla", l.sound);
	}

	@Test
	void silentIsParsedAndPreserved() {
		UiLayout l = UiLayout.parse("t", "{\"width\":320,\"height\":220,\"sound\":\"silent\",\"children\":[]}");
		assertEquals("silent", l.sound);
		UiLayout again = UiLayout.parse("t", l.toJson());
		assertEquals("silent", again.sound, "写回要保留 silent，否则保存一次就自动开音");
	}
}
