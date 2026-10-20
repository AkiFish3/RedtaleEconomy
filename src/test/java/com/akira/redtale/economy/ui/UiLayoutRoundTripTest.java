package com.akira.redtale.economy.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R15/R17：布局解析与写回（拖拽编辑器只改 raw，写回必须原样保住其它字段）。 */
class UiLayoutRoundTripTest {

	private static final String JSON = """
			{
			  "width": 320,
			  "height": 220,
			  "title": "gui.redtale_economy.menu.title",
			  "customField": {"keep": true},
			  "children": [
			    { "type": "label", "x": 12, "y": 28, "text": "hello" },
			    { "type": "bogus", "x": 1, "y": 2 },
			    { "type": "grid", "x": 12, "y": 64, "w": 296, "h": 120,
			      "columns": 3, "rowHeight": 22, "source": "menu.entries", "action": "cmd:%s" }
			  ]
			}
			""";

	@Test
	void parsesTypesAndKeepsUnknownAsUnknown() {
		UiLayout l = UiLayout.parse("menu/main", JSON);
		assertEquals(320, l.width);
		assertEquals(3, l.children.size());
		assertEquals(UiNode.Type.LABEL, l.children.get(0).type);
		assertEquals(UiNode.Type.UNKNOWN, l.children.get(1).type);
		assertEquals(UiNode.Type.GRID, l.children.get(2).type);
		assertEquals(3, l.children.get(2).columns);
	}

	@Test
	void dragOnRawSurvivesWriteBack() {
		UiLayout l = UiLayout.parse("menu/main", JSON);
		l.children.get(0).raw.addProperty("x", 77);
		l.children.get(0).raw.addProperty("y", 91);

		String out = l.toJson();
		assertTrue(out.contains("customField"), "写回不能丢未知字段");
		UiLayout again = UiLayout.parse("menu/main", out);
		assertEquals(77, again.children.get(0).x);
		assertEquals(91, again.children.get(0).y);
		assertEquals(3, again.children.size());
		assertNotNull(again.children.get(2).action);
	}
}
