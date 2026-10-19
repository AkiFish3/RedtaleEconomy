package com.akira.redtale.economy.config;

/**
 * SDM Economy → Numismatic 一次性余额迁移（R23，spec §22 v0.17）。
 *
 * <p>只做「离线批量搬余额」：scan 报人数/总额/异常，apply 逐人写 Numismatic 并记流水，
 * rollback 按 ledger 的 {@code ref=migrate:sdm} 回退。
 */
public final class SdmMigrateConfig {

	public static final String FILE = "sdm_migrate.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("SDM 余额迁移")
			.field("enabled", ConfigSchema.bool(false,
					"总开关。false 时 /redtale migrate sdm * 一律拒绝执行（防误触）"))
			.field("sourceCurrencyId", ConfigSchema.string("sdm_economy:coin",
					"要搬的 SDM 币种 id（MoneyData.currencies[].currencyID）"))
			.field("skipOtherModCurrencies", ConfigSchema.bool(true,
					"跳过 isOtherMod=true 的条目（属于其它模组的货币，不归我方管）"))
			.field("ratio", ConfigSchema.decimal(1.0, 0.0001, 1_000_000.0,
					"换算比例：1 SDM 单位 = ratio 个铜币"))
			.field("minAmount", ConfigSchema.integer(1, 0, Integer.MAX_VALUE,
					"低于该值的账号跳过（避免刷出海量 1 铜币账号）"))
			.field("backupTag", ConfigSchema.string("sdm_migrate",
					"备份子目录名前缀：<root>/backup/<backupTag>_<时间戳>/"))
			.field("ledgerRef", ConfigSchema.string("migrate:sdm",
					"写流水时使用的 ref，rollback 靠它精准定位本次迁移"))
			.field("batchSize", ConfigSchema.integer(200, 1, 5000,
					"每批处理人数（每批之间让出主线程，避免卡服）"));

	private SdmMigrateConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", false);
	}

	public static String sourceCurrencyId() {
		return store().getString("sourceCurrencyId", "sdm_economy:coin");
	}

	public static boolean skipOtherModCurrencies() {
		return store().getBool("skipOtherModCurrencies", true);
	}

	public static double ratio() {
		return store().getDouble("ratio", 1.0);
	}

	public static long minAmount() {
		return store().getLong("minAmount", 1L);
	}

	public static String backupTag() {
		return store().getString("backupTag", "sdm_migrate");
	}

	public static String ledgerRef() {
		return store().getString("ledgerRef", "migrate:sdm");
	}

	public static int batchSize() {
		return store().getInt("batchSize", 200);
	}
}
