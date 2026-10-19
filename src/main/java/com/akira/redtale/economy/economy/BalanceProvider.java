package com.akira.redtale.economy.economy;

import java.util.UUID;

/**
 * 余额来源抽象（spec v0.5 §12）。
 *
 * <p>内部只认「铜」为单位的 long。两种实现：
 * {@link InternalProvider}（本 mod 自己的 JSON）与 {@link NumismaticBridge}（外部模组）。
 * 上层只依赖本接口，不感知具体实现。
 */
public interface BalanceProvider {

	/** 用于日志与 /redtale info：internal / numismatic。 */
	String id();

	/** 当前是否可用（外部模组被卸载 / 初始化失败时返回 false）。 */
	boolean available();

	long get(UUID uuid);

	void set(UUID uuid, long rawCopper);

	void modify(UUID uuid, long deltaCopper);

	/** 把 raw 值渲染成「X 金 Y 银 Z 铜」。 */
	String format(long rawCopper);
}
