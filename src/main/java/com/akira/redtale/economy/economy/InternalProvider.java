package com.akira.redtale.economy.economy;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.EconomyConfig;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.storage.DataCompat;
import com.akira.redtale.economy.storage.IdentityMap;
import com.akira.redtale.economy.storage.MysqlBackend;
import com.akira.redtale.economy.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 本 mod 自己的余额存储（未装 Numismatic 时使用）。
 *
 * <p>一人一个 JSON：{@code <root>/<accounts>/<uuid>.json}。
 * 内存缓存 + 防抖落盘（{@code storage.json → account.saveDebounceSeconds}），
 * 写盘走 tmp + 原子 rename，避免断电半截文件。
 *
 * <p>流水与快照由 R19 的存储层接管，这里只负责余额本体。
 */
public final class InternalProvider implements BalanceProvider {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private final Map<UUID, Long> cache = new ConcurrentHashMap<>();
	private final Map<UUID, Long> dirtyAt = new ConcurrentHashMap<>();
	private final Map<UUID, String> names = new ConcurrentHashMap<>();
	/** 文件版本高于本代码的账号：只读，拒绝覆盖（B22）。 */
	private final java.util.Set<UUID> readOnly = ConcurrentHashMap.newKeySet();
	private final ScheduledExecutorService flusher;
	private Path accountDir;
	private volatile boolean ready;

	public InternalProvider() {
		resolvePaths();
		this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "redtale-econ-flush");
			t.setDaemon(true);
			return t;
		});
		this.flusher.scheduleWithFixedDelay(this::flushDue, 1, 1, TimeUnit.SECONDS);
		if (com.akira.redtale.economy.config.StorageConfig.backendIsMysql()) {
			MysqlBackend.init();
			if (!MysqlBackend.available()) {
				// 明确告警：配置要 mysql 但连不上，本次退回 JSON —— 两套数据会分叉
				RedtaleEconomy.LOGGER.error("[storage] backend=mysql 但连接不可用，退回 JSON 存储（{}）。"
						+ "修好前不要同时用两套数据对账", MysqlBackend.lastError());
			}
		}
		this.ready = accountDir != null || MysqlBackend.available();
	}

	/** 配置可能被 reload，路径与防抖时间都重新取。 */
	public void resolvePaths() {
		PathResolver.Resolved r = PathResolver.resolveDir(StorageConfig.root(), "%server%/redtale-data");
		try {
			Path dir = r.path().resolve(StorageConfig.accountsDir().replace("/", java.io.File.separator));
			Files.createDirectories(dir);
			Path old = this.accountDir;
			this.accountDir = dir;
			this.ready = true;
			if (old == null) {
				RedtaleEconomy.LOGGER.info("[storage] 账号目录：{}", dir);
			}
		} catch (Exception e) {
			this.ready = false;
			RedtaleEconomy.LOGGER.error("[storage] 账号目录不可用：{}", e.getMessage());
		}
	}

	@Override
	public String id() {
		return "internal";
	}

	@Override
	public boolean available() {
		return ready && (accountDir != null || MysqlBackend.available());
	}

	@Override
	public long get(UUID uuid) {
		Long cached = cache.get(uuid);
		if (cached != null) return cached;
		long v = MysqlBackend.available() ? readMysql(uuid) : readFile(uuid);
		cache.put(uuid, v);
		return v;
	}

	private long readMysql(UUID uuid) {
		try {
			if (!MysqlBackend.available()) return EconomyConfig.startingBalance();
			long v = MysqlBackend.loadBalance(uuid);
			return MysqlBackend.hasAccount(uuid) ? v : EconomyConfig.startingBalance();
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[storage] 读取账号 {} 失败：{}", uuid, e.getMessage());
			return EconomyConfig.startingBalance();
		}
	}

	private long readFile(UUID uuid) {
		if (!available()) return 0L;
		Path f = accountDir.resolve(uuid + ".json");
		if (!Files.isRegularFile(f)) {
			// 离线↔正版切换后 UUID 变了：沿别名表找旧账号（B22）
			String mapped = IdentityMap.resolve(IdentityMap.load(accountDir), uuid.toString());
			if (!mapped.equals(uuid.toString())) {
				Path alt = accountDir.resolve(mapped + ".json");
				if (Files.isRegularFile(alt)) {
					RedtaleEconomy.LOGGER.info("[storage] 账号 {} 走身份别名 → {}", uuid, mapped);
					f = alt;
				}
			}
		}
		if (!Files.isRegularFile(f)) {
			return EconomyConfig.startingBalance();
		}
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			if (o != null) {
				int ver = o.has("_version") ? o.get("_version").getAsInt() : 0;
				if (DataCompat.verdict(ver, DataCompat.ACCOUNT_VERSION) == DataCompat.Verdict.NEWER) {
					// 版本比代码新：降级运行会把新字段写没，故整份文件只读 + 备份
					readOnly.add(uuid);
					Path root = backupRoot();
					Path bak = DataCompat.backup(root == null ? null : root.toString(), f, "account");
					RedtaleEconomy.LOGGER.error(
							"[storage] 账号 {} 数据版本 {} 高于本代码 {}（是否用旧版登录过？），已置为只读并备份到 {}；"
									+ "请升级服务端后再操作该账号",
							uuid, ver, DataCompat.ACCOUNT_VERSION, bak == null ? "(备份失败)" : bak);
				}
				if (o.has("balance")) return o.get("balance").getAsLong();
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[storage] 读取账号 {} 失败：{}", uuid, e.getMessage());
		}
		return EconomyConfig.startingBalance();
	}

	@Override
	public void set(UUID uuid, long rawCopper) {
		cache.put(uuid, Math.max(0, rawCopper));
		dirtyAt.put(uuid, System.currentTimeMillis());
	}

	@Override
	public void modify(UUID uuid, long deltaCopper) {
		set(uuid, get(uuid) + deltaCopper);
	}

	@Override
	public String format(long rawCopper) {
		return Currency.format(rawCopper);
	}

	// ---------------------------------------------------------------- 落盘

	private void flushDue() {
		try {
			long debounce = Math.max(1, StorageConfig.saveDebounceSeconds()) * 1000L;
			long now = System.currentTimeMillis();
			for (Map.Entry<UUID, Long> e : dirtyAt.entrySet()) {
				if (now - e.getValue() >= debounce) {
					if (write(e.getKey())) {
						dirtyAt.remove(e.getKey(), e.getValue());
					}
				}
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[storage] 落盘任务异常", e);
		}
	}

	/** 备份根目录：与 accounts 同级（{@code <root>/backup/}）。 */
	private Path backupRoot() {
		Path parent = accountDir == null ? null : accountDir.getParent();
		return parent == null ? null : parent.resolve("backup");
	}

	private boolean write(UUID uuid) {
		if (!available()) return false;
		Long balance = cache.get(uuid);
		if (balance == null) return true;
		if (readOnly.contains(uuid)) return true; // 只读账号：丢弃脏标记，绝不动盘
		if (MysqlBackend.available()) {
			return MysqlBackend.saveBalance(uuid, names.get(uuid), balance);
		}
		Path f = accountDir.resolve(uuid + ".json");
		Path tmp = accountDir.resolve(uuid + ".json.tmp");
		try {
			JsonObject o = new JsonObject();
			o.addProperty("_version", DataCompat.ACCOUNT_VERSION);
			o.addProperty("uuid", uuid.toString());
			o.addProperty("balance", balance);
			o.addProperty("updatedAt", LocalDateTime.now().format(TS));
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(o, w);
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[storage] 写入账号 {} 失败：{}", uuid, e.getMessage());
			return false;
		}
	}

	/** 关服 / 重载前强制落盘。 */
	public void flushAll() {
		for (UUID u : dirtyAt.keySet()) {
			if (write(u)) dirtyAt.remove(u);
		}
	}

	public void shutdown() {
		flushAll();
		flusher.shutdownNow();
		MysqlBackend.shutdown();
	}
}
