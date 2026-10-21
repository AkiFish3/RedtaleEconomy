package com.akira.redtale.economy.legacy.dev;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.screen.PackageEditHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * T71 可视化编辑器验收夹具的**服务端半边**（开发期，不进发行 jar）。
 *
 * <p>存在标记文件 {@code <gameDir>/b31-edit-shots.flag} 时才注册。客户端
 * {@link EditShotClient} 通过静态字段下单，服务端执行三件事：
 * <ol>
 *   <li>{@code SETUP} —— 切创造、发一件 {@code package_rare}、选中 0 号快捷栏（编辑器只对创造模式开放）；
 *   <li>{@code SEED} —— 往编辑器网格里塞两格物品（空包裹没有权重表行，点不到 +/-，必须先有内容）；
 *   <li>{@code VERIFY} —— 关窗后回读**手上那件物品**的 id 与 NBT，落盘成文本判据。
 * </ol>
 *
 * <p>为什么判据要读手牌而不是读界面：换档是把物品 id 整个换掉（{@code package_rare}→{@code package_legend}），
 * 只有手牌能证明「服务端真的换绑了」，读界面字段只能证明客户端自己以为换了。
 */
public final class EditShotHarness {
	private static final String FLAG = "b31-edit-shots.flag";
	private static final String OUT = "b31-edit-server.txt";

	public static final int REQ_NONE = 0;
	public static final int REQ_SETUP = 1;
	public static final int REQ_SEED = 2;
	public static final int REQ_VERIFY = 3;

	static volatile int request = REQ_NONE;
	private static int lastHandled = REQ_NONE;
	private static Path outFile;

	private EditShotHarness() {
	}

	public static void register() {
		Path flag = FabricLoader.getInstance().getGameDir().resolve(FLAG);
		if (!Files.exists(flag)) {
			return;
		}
		outFile = FabricLoader.getInstance().getGameDir().resolve(OUT);
		try {
			Files.writeString(outFile, "# b31 editor shot harness\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (Exception e) {
			LegacyInit.LOGGER.warn("[b31] 无法写结果文件：{}", e.toString());
		}
		ServerTickEvents.END_SERVER_TICK.register(EditShotHarness::onServerTick);
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			// 同进程（集成服务端）；专用服务端不会走到这里，也不会解析该常量池项。
			EditShotClient.register();
		}
		LegacyInit.LOGGER.info("[b31] edit shot harness armed (flag={})", flag);
	}

	private static void onServerTick(MinecraftServer server) {
		int req = request;
		if (req == REQ_NONE || req == lastHandled) {
			return;
		}
		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		if (players.isEmpty()) {
			return;
		}
		lastHandled = req;
		ServerPlayerEntity p = players.get(0);
		switch (req) {
			case REQ_SETUP -> setup(p);
			case REQ_SEED -> seed(p);
			case REQ_VERIFY -> verify(p);
			default -> {
			}
		}
	}

	private static void setup(ServerPlayerEntity p) {
		p.closeHandledScreen();
		p.getAbilities().creativeMode = true;
		p.sendAbilitiesUpdate();
		p.getInventory().clear();
		ItemStack stack = new ItemStack(Registries.ITEM.get(LegacyInit.id("package_rare")));
		p.getInventory().setStack(0, stack);
		p.getInventory().selectedSlot = 0;
		p.playerScreenHandler.syncState();
		log("SETUP creative=" + p.getAbilities().creativeMode
				+ " item=" + Registries.ITEM.getId(stack.getItem())
				+ " hand=" + Registries.ITEM.getId(p.getMainHandStack().getItem()));
	}

	/**
	 * 塞格：直接走 {@code handler.getSlot(i).setStack} ⇒ 落到 {@code EditablePackageInventory.setStack}
	 * ⇒ 自动 markDirty。权重保持默认 1，客户端连点两次「+」后应变成 3。
	 */
	private static void seed(ServerPlayerEntity p) {
		if (!(p.currentScreenHandler instanceof PackageEditHandler handler)) {
			log("SEED fail handler=" + p.currentScreenHandler.getClass().getName());
			return;
		}
		handler.getSlot(0).setStack(new ItemStack(Items.DIAMOND, 3));
		handler.getSlot(1).setStack(new ItemStack(Items.EMERALD, 1));
		log("SEED ok slot0=" + Registries.ITEM.getId(handler.getSlot(0).getStack().getItem())
				+ " slot1=" + Registries.ITEM.getId(handler.getSlot(1).getStack().getItem()));
	}

	/** 关窗后的落盘回读：手牌 id / mode / items 列表逐条（item id + weight）。 */
	private static void verify(ServerPlayerEntity p) {
		ItemStack stack = p.getStackInHand(Hand.MAIN_HAND);
		NbtCompound nbt = stack.getNbt();
		String mode = nbt == null || !nbt.contains("mode") ? "<absent>" : nbt.getString("mode");
		log("VERIFY hand=" + Registries.ITEM.getId(stack.getItem()) + " mode=" + mode
				+ " size=" + stack.getCount());
		if (nbt == null) {
			log("VERIFY items=<no-nbt>");
			return;
		}
		NbtList items = nbt.getList("items", 10);
		log("VERIFY items.count=" + items.size());
		for (int i = 0; i < items.size(); i++) {
			NbtCompound e = items.getCompound(i);
			ItemStack it = ItemStack.fromNbt(e.getCompound("item"));
			log("VERIFY items[" + i + "]=" + Registries.ITEM.getId(it.getItem())
					+ " x" + it.getCount() + " weight=" + e.getInt("weight"));
		}
		// 编辑器界面此刻应该已经关了（关窗即保存），记录一下服务端侧看到的状态
		log("VERIFY handler=" + p.currentScreenHandler.getClass().getSimpleName());
	}

	private static void log(String line) {
		LegacyInit.LOGGER.info("[b31] {}", line);
		if (outFile == null) {
			return;
		}
		try {
			Files.writeString(outFile, line + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
			// 夹具失败不该影响游戏
		}
	}
}
