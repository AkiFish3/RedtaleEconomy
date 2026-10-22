package com.akira.redtale.economy.module.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连登里程碑解析（Aki 2026-10-23：7/14 多档 + 自定义天数）。
 *
 * <p>为什么值得单测：解析错一天就是「该发的没发 / 不该发的发了」，
 * 而配置是手写的，四种写法都要吃（旧纯数字、days 单值、days 数组、day 简写）。
 */
class CheckinMilestoneParseTest {

	private static JsonArray parse(String json) {
		return JsonParser.parseString(json).getAsJsonArray();
	}

	@Test
	void legacyNumberArrayStillWorks() {
		List<CheckinModule.Milestone> list =
				CheckinModule.parseMilestones(parse("[7,14,28]"));
		assertEquals(3, list.size());
		assertEquals(7, list.get(0).day());
		assertEquals(0L, list.get(0).money(), "旧写法只有天数，不该凭空发钱");
		assertEquals(28, list.get(2).day());
	}

	@Test
	void readsMoneyFromRewardObject() {
		List<CheckinModule.Milestone> list = CheckinModule.parseMilestones(parse(
				"[{\"days\":7,\"reward\":{\"money\":2000}},{\"days\":14,\"reward\":{\"money\":5000}}]"));
		assertEquals(2, list.size());
		assertEquals(7, list.get(0).day());
		assertEquals(2000L, list.get(0).money());
		assertEquals(5000L, list.get(1).money());
	}

	@Test
	void daysArrayExpandsToMultipleTiers() {
		List<CheckinModule.Milestone> list = CheckinModule.parseMilestones(parse(
				"[{\"days\":[14,30],\"reward\":{\"money\":3000}}]"));
		assertEquals(2, list.size());
		assertEquals(14, list.get(0).day());
		assertEquals(30, list.get(1).day());
		assertEquals(3000L, list.get(0).money());
		assertEquals(3000L, list.get(1).money());
	}

	@Test
	void sortsAndDropsDuplicatesKeepingFirst() {
		List<CheckinModule.Milestone> list = CheckinModule.parseMilestones(parse(
				"[{\"days\":30,\"reward\":{\"money\":9}},"
						+ "{\"days\":7,\"reward\":{\"money\":1}},"
						+ "{\"days\":7,\"reward\":{\"money\":2}}]"));
		assertEquals(2, list.size());
		assertEquals(7, list.get(0).day(), "必须按天数升序，进度条与提示都依赖顺序");
		assertEquals(1L, list.get(0).money(), "同一天保留第一条");
		assertEquals(30, list.get(1).day());
	}

	@Test
	void skipsIllegalDaysButKeepsValidOnes() {
		List<CheckinModule.Milestone> list = CheckinModule.parseMilestones(parse(
				"[0,-5,9999,14]"));
		assertEquals(1, list.size());
		assertEquals(14, list.get(0).day());
	}

	@Test
	void readsItemsWhenConfigured() {
		List<CheckinModule.Milestone> list = CheckinModule.parseMilestones(parse(
				"[{\"days\":7,\"reward\":{\"money\":0,"
						+ "\"items\":[{\"id\":\"minecraft:diamond\",\"count\":3}]}}]"));
		assertEquals(1, list.get(0).items().size());
		assertEquals("minecraft:diamond", list.get(0).items().get(0).id());
		assertEquals(3, list.get(0).items().get(0).count());
	}

	@Test
	void emptyOrNullIsEmpty() {
		assertTrue(CheckinModule.parseMilestones(new JsonArray()).isEmpty());
		assertTrue(CheckinModule.parseMilestones(null).isEmpty());
		assertTrue(CheckinModule.parseMilestones(parse("[]")).isEmpty());
	}
}
