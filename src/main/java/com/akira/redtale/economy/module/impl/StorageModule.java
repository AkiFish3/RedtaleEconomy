package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.module.ModuleEvents;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.storage.Ledger;
import com.akira.redtale.economy.storage.SnapshotService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * 存储模块（spec v0.6）：流水 + 余额快照 + 到期清理。
 *
 * <p>不注册命令；关掉它 = 不记流水、不写快照，余额本体仍照常工作。
 */
public final class StorageModule implements RedtaleModule {

	@Override
	public String id() {
		return "storage";
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		Ledger.init();
		SnapshotService.init();
		syncMysql();
		ServerTickEvents.END_SERVER_TICK.register(SnapshotService::onServerTick);
		ModuleEvents.onQuit("storage", p -> SnapshotService.forget(p.getUuid()));
	}

	@Override
	public void onReload(ConfigManager cfg) {
		// 目录/间隔全部每次现读配置；这里只重建目录句柄
		Ledger.init();
		SnapshotService.init();
		syncMysql();
	}

	/** backend 从 json 切到 mysql（或反过来）后重连/断开，不用重启。 */
	private static void syncMysql() {
		boolean wantMysql = com.akira.redtale.economy.config.StorageConfig.backendIsMysql();
		if (wantMysql && !com.akira.redtale.economy.storage.MysqlBackend.available()) {
			com.akira.redtale.economy.storage.MysqlBackend.init();
		} else if (!wantMysql && com.akira.redtale.economy.storage.MysqlBackend.available()) {
			com.akira.redtale.economy.storage.MysqlBackend.shutdown();
		}
	}

	/** 关服前由主类调用。 */
	public static void shutdown() {
		Ledger.shutdown();
		com.akira.redtale.economy.storage.MysqlBackend.shutdown();
	}
}
