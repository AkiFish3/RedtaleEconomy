package com.akira.redtale.economy.economy;

import com.akira.redtale.economy.RedtaleEconomy;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Numismatic 桥接（spec v0.5 §12）。
 *
 * <p><b>为什么用反射而不是 compileOnly</b>：本机没有 Numismatic 的 jar，
 * 走 maven 依赖会让构建依赖网络；反射同时天然消除了 NoClassDefFoundError 风险
 * （类找不到就降级），比 compileOnly + 独立类懒加载更彻底。行为与定案一致：
 * 只读写 CCA 组件里的数值，绝不碰实体硬币物品。
 *
 * <p>类名不硬编码：优先试已知包名，失败则扫描模组 jar 里名为 {@code *ModComponents} 的类。
 */
public final class NumismaticBridge implements BalanceProvider {

	private static final String[] MOD_IDS = {"numismatic-overhaul", "numismatic"};

	private static final String[] CLASS_CANDIDATES = {
			"net.conczin.numismatic.component.ModComponents",
			"net.conczin.numismatic.api.ModComponents",
			"net.conczin.numismatic.ModComponents",
			"com.glisco.numismaticoverhaul.ModComponents",
			"com.glisco.numismaticoverhaul.currency.ModComponents"
	};

	private boolean available;
	private boolean warned;
	private Object componentKey;
	private Method keyGet;
	private Method getValue;
	private Method setValue;
	private Method modify;

	public NumismaticBridge() {
		try {
			if (!isLoaded()) {
				RedtaleEconomy.LOGGER.info("[provider] 未检测到 Numismatic，使用内部余额");
				return;
			}
			Class<?> comps = locateComponentsClass();
			if (comps == null) {
				warnOnce("找不到 ModComponents 类，降级到内部余额");
				return;
			}
			Field f = comps.getField("CURRENCY");
			componentKey = f.get(null);
			if (componentKey == null) {
				warnOnce("ModComponents.CURRENCY 为 null，降级到内部余额");
				return;
			}
			keyGet = componentKey.getClass().getMethod("get", Object.class);
			try {
				bind(Class.forName(componentClassOf(comps)));
			} catch (Throwable ignored) {
				// 泛型实参解析不出来就等第一次拿到组件实例时再绑定
			}
			available = true;
			RedtaleEconomy.LOGGER.info("[provider] 已接入 Numismatic（{}）", comps.getName());
		} catch (Throwable t) {
			warnOnce("接入 Numismatic 失败：" + t);
		}
	}

	private void bind(Class<?> comp) throws Exception {
		getValue = comp.getMethod("getValue");
		setValue = comp.getMethod("setValue", long.class);
		modify = comp.getMethod("modify", long.class);
	}

	/** 首次拿到组件实例后按真实类型补绑定。 */
	private void ensureMethods(Object c) throws Exception {
		if (getValue == null && c != null) {
			bind(c.getClass());
		}
	}

	private String componentClassOf(Class<?> comps) throws Exception {
		// CURRENCY 的泛型实参就是 CurrencyComponent；退一步用已知类名
		java.lang.reflect.Type t = comps.getField("CURRENCY").getGenericType();
		String s = t.getTypeName();
		int lt = s.indexOf('<'), gt = s.lastIndexOf('>');
		if (lt > 0 && gt > lt) {
			return s.substring(lt + 1, gt).trim();
		}
		return "net.conczin.numismatic.component.CurrencyComponent";
	}

	private boolean isLoaded() {
		for (String id : MOD_IDS) {
			if (FabricLoader.getInstance().isModLoaded(id)) return true;
		}
		return false;
	}

	@SuppressWarnings("unused")
	private Class<?> locateComponentsClass() {
		for (String name : CLASS_CANDIDATES) {
			try {
				return Class.forName(name);
			} catch (ClassNotFoundException ignored) {
				// 继续找下一个候选
			}
		}
		// 兜底：扫 jar
		for (String id : MOD_IDS) {
			var opt = FabricLoader.getInstance().getModContainer(id);
			if (opt.isEmpty()) continue;
			ModContainer mc = opt.get();
			for (Path root : mc.getRootPaths()) {
				String found = scan(root);
				if (found != null) {
					try {
						return Class.forName(found);
					} catch (ClassNotFoundException ignored) {
						// 扫到了但加载不了，继续
					}
				}
			}
		}
		return null;
	}

	/** 在目录或 jar 中找 *ModComponents.class，返回点分类名。 */
	private String scan(Path root) {
		try {
			if (Files.isDirectory(root)) {
				try (var s = Files.walk(root)) {
					return s.filter(p -> p.getFileName().toString().endsWith("ModComponents.class"))
							.findFirst()
							.map(p -> root.relativize(p).toString().replace(File.separatorChar, '.'))
							.map(n -> n.substring(0, n.length() - ".class".length()))
							.orElse(null);
				}
			}
			try (ZipFile zip = new ZipFile(root.toFile())) {
				Enumeration<? extends ZipEntry> en = zip.entries();
				while (en.hasMoreElements()) {
					String n = en.nextElement().getName();
					if (n.endsWith("ModComponents.class") && n.contains("/")) {
						return n.substring(0, n.length() - ".class".length()).replace('/', '.');
					}
				}
			}
		} catch (Exception ignored) {
			// 扫描失败当作没找到
		}
		return null;
	}

	private void warnOnce(String why) {
		if (warned) return;
		warned = true;
		RedtaleEconomy.LOGGER.warn("[provider] {}；本次运行使用内部余额", why);
	}

	@Override
	public String id() {
		return "numismatic";
	}

	@Override
	public boolean available() {
		return available;
	}

	private Object component(UUID uuid) throws Exception {
		Object player = resolvePlayer(uuid);
		if (player == null) return null;
		Object c = keyGet.invoke(componentKey, player);
		ensureMethods(c);
		return c;
	}

	private Object resolvePlayer(UUID uuid) {
		var server = RedtaleEconomy.server();
		return server == null ? null : server.getPlayerManager().getPlayer(uuid);
	}

	@Override
	public long get(UUID uuid) {
		try {
			Object c = component(uuid);
			if (c == null || getValue == null) return -1L;
			return (Long) getValue.invoke(c);
		} catch (Throwable t) {
			warnOnce("读取余额失败：" + t);
			return -1L;
		}
	}

	@Override
	public void set(UUID uuid, long rawCopper) {
		try {
			Object c = component(uuid);
			if (c != null) setValue.invoke(c, Math.max(0, rawCopper));
		} catch (Throwable t) {
			warnOnce("写入余额失败：" + t);
		}
	}

	@Override
	public void modify(UUID uuid, long deltaCopper) {
		try {
			Object c = component(uuid);
			if (c != null) modify.invoke(c, deltaCopper);
		} catch (Throwable t) {
			warnOnce("修改余额失败：" + t);
		}
	}

	@Override
	public String format(long rawCopper) {
		return Currency.format(rawCopper);
	}

	/** 调试用：候选类列表。 */
	public static List<String> candidates() {
		return new ArrayList<>(List.of(CLASS_CANDIDATES));
	}
}
