package com.akira.redtale.economy.integration;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.SdmMigrateConfig;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.storage.Ledger;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.sixik.sdm_economy.adv.PlayerMoneyData;
import net.sixik.sdm_economy.common.cap.MoneyData;
import net.sixik.sdm_economy.common.currency.AbstractCurrency;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SDM Economy → Numismatic 一次性余额迁移（R23，spec §22）。
 *
 * <p>只在 {@code sdm_economy} 已加载时被调用；scan 只读内存里的 {@code PlayerMoneyData.SERVER.PLAYER_MONEY}，
 * apply 逐人写余额并记流水（ref 可配，默认 {@code migrate:sdm}），同时把「谁拿了多少」快照到
 * {@code <root>/backup/<backupTag>_<时间戳>.json}，rollback 就按这份快照扣回去。
 */
public final class SdmMigrate {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private SdmMigrate() {
	}

	public static boolean available() {
		return FabricLoader.getInstance().isModLoaded("sdm_economy");
	}

	/** 一条待迁移记录。 */
	public record Row(UUID uuid, long raw, long copper) {
	}

	/** 读取 SDM 内存数据并换算成铜币（只读，不改任何东西）。 */
	public static List<Row> snapshot(MinecraftServer server) {
		List<Row> out = new ArrayList<>();
		if (!available() || server == null) return out;
		try {
			PlayerMoneyData.load(server);
			var data = PlayerMoneyData.SERVER;
			if (data == null || data.PLAYER_MONEY == null) return out;
			double ratio = SdmMigrateConfig.ratio();
			String want = SdmMigrateConfig.sourceCurrencyId();
			for (var e : data.PLAYER_MONEY.entrySet()) {
				MoneyData md = e.getValue();
				if (md == null) continue;
				if (SdmMigrateConfig.skipOtherModCurrencies() && md.isOtherMod) continue;
				long raw = md.moneyBase;
				if (md.currencies != null) {
					for (AbstractCurrency c : md.currencies) {
						if (c == null) continue;
						try {
							if (want != null && !want.isEmpty() && !want.equals(c.getID())) continue;
						} catch (Exception ignored) {
							continue;
						}
						raw += c.moneys;
					}
				}
				if (raw < SdmMigrateConfig.minAmount()) continue;
				long copper = Math.round(raw * ratio);
				if (copper <= 0) continue;
				out.add(new Row(e.getKey(), raw, copper));
			}
		} catch (Throwable t) {
			RedtaleEconomy.LOGGER.error("[migrate] 读取 SDM 数据失败：{}", t.toString());
		}
		return out;
	}

	public static long total(List<Row> rows) {
		long sum = 0;
		for (Row r : rows) sum += r.copper();
		return sum;
	}

	public static int negativeCount(List<Row> rows) {
		int n = 0;
		for (Row r : rows) if (r.raw() < 0) n++;
		return n;
	}

	/** 执行迁移；返回成功人数。迁移前写快照，失败可 rollback。 */
	public static int apply(MinecraftServer server, List<Row> rows) {
		if (!SdmMigrateConfig.enabled()) return -1;
		int ok = 0;
		String ref = SdmMigrateConfig.ledgerRef();
		for (Row r : rows) {
			try {
				BalanceService.add(r.uuid(), r.copper(), Ledger.TYPE_MIGRATE, ref);
				ok++;
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[migrate] {} 写入失败：{}", r.uuid(), e.getMessage());
			}
		}
		writeSnapshot(rows, ok);
		return ok;
	}

	/** 按最近一份快照回滚（只扣到 0 为止，绝不让余额变负）。返回回滚人数。 */
	public static int rollback() {
		JsonObject snap = readSnapshot();
		if (snap == null || !snap.has("rows")) return -1;
		int n = 0;
		for (var e : snap.getAsJsonArray("rows")) {
			try {
				JsonObject o = e.getAsJsonObject();
				UUID uuid = UUID.fromString(o.get("uuid").getAsString());
				long copper = o.get("copper").getAsLong();
				long have = BalanceService.get(uuid);
				long take = Math.min(have, copper);
				if (take > 0 && BalanceService.take(uuid, take, Ledger.TYPE_MIGRATE,
						SdmMigrateConfig.ledgerRef() + ":rollback")) {
					n++;
				}
			} catch (Exception ex) {
				RedtaleEconomy.LOGGER.warn("[migrate] 回滚单条失败：{}", ex.getMessage());
			}
		}
		return n;
	}

	// ---------------------------------------------------------------- 快照

	private static Path backupDir() {
		PathResolver.Resolved r = DataRoot.resolve();
		return r.path().resolve("backup");
	}

	private static Path snapshotFile() {
		Path dir = backupDir();
		if (dir == null || !Files.isDirectory(dir)) return null;
		try (var s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().startsWith(SdmMigrateConfig.backupTag())
							&& p.getFileName().toString().endsWith(".json"))
					.sorted(java.util.Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
					.findFirst().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	private static void writeSnapshot(List<Row> rows, int applied) {
		try {
			Path dir = backupDir();
			Files.createDirectories(dir);
			JsonObject o = new JsonObject();
			o.addProperty("_version", 1);
			o.addProperty("createdAt", java.time.LocalDateTime.now().toString());
			o.addProperty("ledgerRef", SdmMigrateConfig.ledgerRef());
			o.addProperty("ratio", SdmMigrateConfig.ratio());
			o.addProperty("applied", applied);
			JsonArray arr = new JsonArray();
			for (Row r : rows) {
				JsonObject ro = new JsonObject();
				ro.addProperty("uuid", r.uuid().toString());
				ro.addProperty("raw", r.raw());
				ro.addProperty("copper", r.copper());
				arr.add(ro);
			}
			o.add("rows", arr);
			Path f = dir.resolve(SdmMigrateConfig.backupTag() + "_"
					+ java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now())
					+ ".json");
			Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
			RedtaleEconomy.LOGGER.info("[migrate] 迁移快照已写入 {}", f);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[migrate] 写快照失败（回滚将不可用）：{}", e.getMessage());
		}
	}

	private static JsonObject readSnapshot() {
		Path f = snapshotFile();
		if (f == null) return null;
		try {
			return GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[migrate] 读快照失败：{}", e.getMessage());
			return null;
		}
	}

	public static String snapshotPath() {
		Path f = snapshotFile();
		return f == null ? "" : f.toString();
	}
}
