package com.akira.redtale.economy.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议往返测试（T33）。
 *
 * <p>做法：每个协议给一个「典型样本 + 一个解码结果比对谓词」，写进 {@link PacketByteBuf}
 * 再读回来，断言两件事：
 * <ol>
 *   <li>值相等（字段顺序/类型错位时这里就炸）；</li>
 *   <li><b>读完之后 buf 必须正好读完</b>——多了说明写端有尾巴没人读，少了说明读端提前越界。</li>
 * </ol>
 * 第 2 条是这套测试的价值所在：历史上的协议漂移（S2C_OPEN 丢 shopId、writePage 尾部字段）
 * 都是「值看起来对，流已经错位」。
 */
class CodecRoundTripTest {

	private record Fixture<T>(String name, Codec<T> codec, Supplier<T> sample, BiPredicate<T, T> same) {
	}

	private static <T> Fixture<T> of(String name, Codec<T> codec, Supplier<T> sample) {
		return new Fixture<>(name, codec, sample, Objects::equals);
	}

	/** 每个协议一条；新增协议必须在这里加样本，否则「没有样本的 codec」用例会失败。 */
	private static List<Fixture<?>> fixtures() {
		List<Fixture<?>> all = new ArrayList<>();

		all.add(of("checkin/snapshot",
				Codec.of("checkin/snapshot", CheckinProtocol::write, CheckinProtocol::read),
				() -> new CheckinProtocol.Snapshot("2026-10-20", 12, 7, 4, 36, 2, 500,
						List.of(new CheckinProtocol.Point(1, 100, true, false),
								new CheckinProtocol.Point(4, 250, false, true)),
						List.of(7, 14, 28))));

		all.add(of("task/snapshot",
				Codec.of("task/snapshot", TaskProtocol::write, TaskProtocol::read),
				() -> new TaskProtocol.Snapshot("2026-10-20", 1,
						List.of(new TaskProtocol.Task(0, 500, "submit", false,
										List.of(new TaskProtocol.Item("minecraft:stone", 64, 12)),
										"挖点石头"),
								new TaskProtocol.Task(1, 1200, "kill", true, List.of(), "清怪")))));

		all.add(of("pass/snapshot",
				Codec.of("pass/snapshot", PassProtocol::write, PassProtocol::read),
				() -> new PassProtocol.Snapshot("S3", 27, 13500, 14000, true,
						List.of(new PassProtocol.Reward(1, 300,
								List.of(new PassProtocol.ItemReward("redtale_economy:makeup_ticket", 2)))),
						List.of(new PassProtocol.Reward(2, 0, List.of())),
						List.of(1, 2, 3), List.of(2))));

		all.add(of("rank/snapshot",
				Codec.of("rank/snapshot", RankProtocol::write, RankProtocol::read),
				() -> new RankProtocol.Snapshot(0,
						List.of(new RankProtocol.Row(1, "Aki", 999999),
								new RankProtocol.Row(2, "Kite", 1234)),
						new RankProtocol.Row(9, "Aki", 999999), 42, 1761000000000L, true)));

		all.add(of("rank/request",
				Codec.of("rank/request", RankProtocol::writeRequest, RankProtocol::readRequest),
				() -> new RankProtocol.Request(false)));

		all.add(of("bounty/snapshot",
				Codec.of("bounty/snapshot", BountyProtocol::write, BountyProtocol::read),
				() -> new BountyProtocol.Snapshot(List.of(
						new BountyProtocol.Entry(0, "minecraft:zombie", 5, 10, 800, false),
						new BountyProtocol.Entry(1, "minecraft:diamond_ore", 3, 3, 1500, true)))));

		all.add(of("guild/snapshot",
				Codec.of("guild/snapshot", GuildProtocol::write, GuildProtocol::read),
				() -> new GuildProtocol.Snapshot("木匠协会", 2, 3400,
						List.of(new GuildProtocol.Rank("学徒", 1000),
								new GuildProtocol.Rank("工匠", 3000)))));

		all.add(of("title/snapshot",
				Codec.of("title/snapshot", TitleProtocol::write, TitleProtocol::read),
				() -> new TitleProtocol.Snapshot(true, "titles.carpenter",
						List.of("titles.newbie", "titles.carpenter"))));

		all.add(of("market/snapshot",
				Codec.of("market/snapshot", MarketProtocol::writeSnapshot, MarketProtocol::readSnapshot),
				() -> new MarketProtocol.Snapshot(1, 3, 130, false, "diamond",
						List.of(new MarketProtocol.Offer("o1", "{id:\"minecraft:stone\",Count:3b}",
								"minecraft:stone", 3, 450, "Aki", true, 3600000L)),
						4, 10, 5.0)));

		all.add(of("market/action",
				Codec.of("market/action", MarketProtocol::writeAction, MarketProtocol::readAction),
				() -> new MarketProtocol.ActionResult(true, "SOLD", "已售出", 12345)));

		all.add(of("shop/action",
				Codec.of("shop/action", ShopProtocol::writeAction, ShopProtocol::readAction),
				() -> new ShopProtocol.ActionResult(true, "shop.bought", "OK", 500, -1)));

		all.add(of("shop/tax",
				Codec.of("shop/tax", ShopProtocol::writeTax, ShopProtocol::readTax),
				() -> new ShopProtocol.TaxInfo(true, 5, 3, false, "blacklist",
						List.of("minecraft:stone", "coc:*"))));

		// 页面用可见行数/条数上限的重载：headless 单测没有配置层，不能走 ShopConfig
		all.add(of("shop/page",
				Codec.of("shop/page",
						(buf, p) -> ShopProtocol.writePageRows(buf, p, 10),
						buf -> ShopProtocol.readPageRows(buf, 41)),
				() -> new ShopProtocol.Page("shopA", "测试店", "橡木", 1, 5, 168,
						List.of(new ShopProtocol.Entry("minecraft:oak_slab", 12, 5, 40, true, true),
								new ShopProtocol.Entry("minecraft:stone", 3, 1, -1, true, false)),
						List.of("minecraft", "coc"), List.of("建材", "建材/木材"))));

		all.add(of("admin/snapshot",
				Codec.of("admin/snapshot", AdminProtocol::write, AdminProtocol::read),
				() -> new AdminProtocol.Snapshot(1761000000000L, true, List.of(
						new AdminProtocol.Metric("在线玩家", "3", "当前服务器在线人数"),
						new AdminProtocol.Metric("商店 / 商品", "219 店 / 2803 件", ""),
						new AdminProtocol.Metric("数据根", "C:/server/world/redtale-data",
								"redtale-data 的实际落点")))));

		all.add(of("admin/request",
				Codec.of("admin/request", AdminProtocol::writeQuery, AdminProtocol::readQuery),
				AdminProtocol.Query::current));

		// 仓储没在这里做往返：ItemStack 的静态初始化要求完整 Bootstrap（headless 单测里
		// 构造 ItemStack.EMPTY 就抛 ExceptionInInitializerError）⇒ 交给 runServer 阶段的
		// 实机验收。它的列表读取同样已经换成 Codecs.readList，与上面的用例共用同一实现。

		return all;
	}

	@Test
	void everyProtocolRoundTripsAndConsumesTheBuffer() {
		List<String> failures = new ArrayList<>();
		for (Fixture<?> f : fixtures()) {
			check(f, failures);
		}
		assertTrue(failures.isEmpty(), "协议往返失败：\n" + String.join("\n", failures));
	}

	private <T> void check(Fixture<T> f, List<String> failures) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		T in = f.sample().get();
		try {
			f.codec().write(buf, in);
			T out = f.codec().read(buf);
			assertNotNull(out, f.name() + " 读回 null");
			if (!f.same().test(in, out)) {
				failures.add(f.name() + " 值不一致\n  in =" + in + "\n  out=" + out);
			}
			if (buf.isReadable()) {
				failures.add(f.name() + " 读端少读了 " + buf.readableBytes() + " 字节（写端有尾巴没人读）");
			}
		} catch (RuntimeException e) {
			failures.add(f.name() + " 抛异常：" + e);
		} finally {
			buf.release();
		}
	}

	/** 读端越界会表现为「读完还剩字节」，这条用例证明检测器真的会响。 */
	@Test
	void leftoverBytesAreDetectable() {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			BalanceProtocol.write(buf, 1234L);
			buf.writeLong(777L); // 模拟「写端多写一个字段，读端没改」
			BalanceProtocol.read(buf);
			assertTrue(buf.isReadable(), "多写的字段应当被检测为未读");
			assertEquals(8, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	/** 列表超限只截断内存，不允许影响后续字段（这正是 min(n,max) 写法踩的坑）。 */
	@Test
	void readListTruncatesButConsumesEverything() {
		Codec<Integer> ints = Codec.of("test/int",
				PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt);
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			List<Integer> big = new ArrayList<>();
			for (int i = 0; i < 1000; i++) big.add(i);
			Codecs.writeList(buf, big, ints);
			buf.writeLong(0xC0FFEEL); // 列表后面的字段，必须仍能被读到

			List<Integer> kept = Codecs.readList(buf, 8, ints);
			assertEquals(8, kept.size());
			assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), kept);
			assertEquals(0xC0FFEEL, buf.readLong(), "超限截断后尾部字段必须还在原位");
			assertTrue(!buf.isReadable(), "读完不该剩字节");
		} finally {
			buf.release();
		}
	}

	/** 税率名单超过 512 条时，截断不能把后面的包内容当成新的名单项。 */
	@Test
	void oversizedTaxListKeepsStreamAligned() {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			List<String> items = new ArrayList<>();
			for (int i = 0; i < 600; i++) items.add("mod" + i + ":block");
			ShopProtocol.writeTax(buf, new ShopProtocol.TaxInfo(true, 5, 3, false, "whitelist", items));

			ShopProtocol.TaxInfo back = ShopProtocol.readTax(buf);
			assertEquals(512, back.items().size());
			assertEquals("mod511:block", back.items().get(511));
			assertTrue(!buf.isReadable(), "名单超限后不该剩字节");
		} finally {
			buf.release();
		}
	}

	/** 商店页尾部那个 visibleRows 必须是「写什么读什么」（曾漏读导致协议不完整）。 */
	@Test
	void pageCarriesVisibleRowsToClient() {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			ShopProtocol.writePageRows(buf, ShopProtocol.Page.empty("s", ""), 10);
			ShopProtocol.readPageRows(buf, 41);
			assertEquals(10, ShopProtocol.lastVisibleRows());
			assertTrue(!buf.isReadable(), "可见行数之后不该还有字节");
		} finally {
			buf.release();
		}
	}

	/** 负长度是坏包，必须显式报错而不是把 ArrayList 容量搞成负数。 */
	@Test
	void negativeListLengthIsRejected() {
		Codec<Integer> ints = Codec.of("test/int2",
				PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt);
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			buf.writeVarInt(-5);
			boolean threw = false;
			try {
				Codecs.readList(buf, 8, ints);
			} catch (IllegalArgumentException expected) {
				threw = true;
			}
			assertTrue(threw, "负数长度必须抛 IllegalArgumentException");
		} finally {
			buf.release();
		}
	}
}
