package com.akira.redtale.economy.net;

import java.util.List;

/**
 * 服务端商品数据源。R4 只定义协议侧接口，具体实现由 R7 的商店模块注入。
 *
 * <p>实现方必须保证 {@link #slice} 是<b>按需切片</b>，
 * 绝不允许把整份商品表塞进返回值（2803 条商品时的老问题）。
 */
public interface ShopSource {

	boolean has(String shopId);

	int count(String shopId, String query);

	List<ShopProtocol.Entry> slice(String shopId, String query, int offset, int limit);

	// ---------------------------------------------------------------- U11b：多维筛选

	/** 带筛选条件的计数；默认实现退回纯搜索（老实现无需改动即可编译）。 */
	default int count(String shopId, String query, ShopFilter filter) {
		return count(shopId, query);
	}

	/** 带筛选条件的切片。 */
	default List<ShopProtocol.Entry> slice(String shopId, String query, ShopFilter filter,
			int offset, int limit) {
		return slice(shopId, query, offset, limit);
	}

	/** 当前商店可用的筛选维度；默认空列表（客户端就拿不到分类栏）。 */
	default List<String> mods(String shopId) {
		return List.of();
	}

	default List<String> categories(String shopId) {
		return List.of();
	}

	/** 当前实现是否就绪（文件读失败 / 未加载时返回 false）。 */
	default boolean ready() {
		return true;
	}
}
