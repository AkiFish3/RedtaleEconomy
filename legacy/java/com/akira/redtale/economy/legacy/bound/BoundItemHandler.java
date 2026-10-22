package com.akira.redtale.economy.legacy.bound;

import com.akira.redtale.economy.legacy.LegacyInit;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BoundItemHandler {
    private static final String OWNER_NAME_KEY = "BoundOwnerName";
    /** 历史遗留键：B57 曾双写 UUID 并以 UUID 判定；**2026-10-23 Aki 定案回退为「只认名字」**
     *  （本服自用，正版/盗版的存档迁移都是手动操作，UUID 兜底没有收益，反而把手动迁移后的物品锁死）。
     *  该键现在**只作诊断读取，不参与任何归属判定**；老物品上残留的值不清洗、也不影响结果。
     *  ★不要恢复双写 / UUID 优先★ —— 闸门 `_b57_owner_uuid.py` 已按新口径反向看守。 */
    private static final String OWNER_UUID_KEY = "BoundOwnerUuid";

    public static final String MSG_NOT_OWNER = "bound.error.not_owner";

    /** 提示冷却：1 秒。★必须集中在这里★——拾取路径（{@code ItemEntity.onPlayerCollision}）**每 tick**
     *  都会被调用一次，不冷却就是 20 条/秒刷屏（2026-10-22 实测：非主人站在有主绑定物上 2 秒 = 40 条）。
     *  槽位路径（{@code Slot.canTakeItems}）每次点击也会调用，同样受这条冷却保护。 */
    private static final long PROMPT_COOLDOWN_MS = 1000L;
    private static final Map<UUID, Long> LAST_PROMPT = new ConcurrentHashMap<>();

    /** 非主人提示（actionbar）。同一玩家 1 秒内只发一次。 */
    public static void notifyNotOwner(PlayerEntity player) {
        if (player == null || player.getWorld().isClient()) return;
        long now = System.currentTimeMillis();
        Long last = LAST_PROMPT.get(player.getUuid());
        if (last != null && now - last < PROMPT_COOLDOWN_MS) return;
        LAST_PROMPT.put(player.getUuid(), now);
        player.sendMessage(Text.translatable(MSG_NOT_OWNER), true);
    }

    /**
     * 为物品绑定所有者（**只写名字**，见类内 UUID 键的说明）
     */
    public static void bindToPlayer(ItemStack stack, PlayerEntity player) {
        if (stack.isEmpty()) return;
        if (!BoundItemsConfig.isBoundItem(stack)) return;
        if (hasOwner(stack)) return;

        writeOwner(stack, player.getName().getString());
        applyOwnerLore(stack);
        LegacyInit.LOGGER.info("Bound item {} to player {}", stack.getItem().getTranslationKey(),
                player.getName().getString());
    }

    /**
     * 写归属（**只写名字**）。
     *
     * <p>抽成不依赖 {@code PlayerEntity} 的纯写入方法，是为了让
     * {@code /redtale bound ownerprobe} 能在没有真人/实体的情况下断言写入结果。</p>
     *
     * <p>★签名故意不带 UUID★：带了就会有人顺手 putUuid —— 而判定已经不看了，写进去只会制造死数据。</p>
     */
    public static void writeOwner(ItemStack stack, String name) {
        if (stack.isEmpty()) return;
        if (name == null || name.isEmpty()) return;
        stack.getOrCreateNbt().putString(OWNER_NAME_KEY, name);
    }

    /**
     * 把归属人写进原版 {@code display.Lore}（**不注入 {@code ItemStack.getTooltip}**）。
     *
     * <p>为什么改成写 lore：旧实现用 {@code @Inject(at=RETURN)} 往 getTooltip 的返回列表里追加，
     * 而 Obscure-API 在同一个 {@code RETURN} 上 {@code cancellable=true} + {@code setReturnValue}
     * —— 排在它前面的注入被它重建的列表覆盖，排在它后面的被它的「提前 return」整段跳过，
     * 两个 priority 方向实测都拿不到这一行（`_LEGACY_VERIFY.md §3.7`）。
     * lore 是原版 NBT 数据，任何模组重建 tooltip 都会把它读回来，因此不存在抢注入点的问题。</p>
     *
     * <p>幂等：同一行已存在就不重复追加（重复绑定时不会堆出多条）。</p>
     */
    public static void applyOwnerLore(ItemStack stack) {
        String ownerName = getOwnerName(stack);
        if (ownerName == null || ownerName.isEmpty()) return;
        Text line = Text.translatable("bound.tooltip.owner", ownerName).formatted(Formatting.GRAY);
        String json = Text.Serializer.toJson(line);
        NbtCompound display = stack.getOrCreateSubNbt("display");
        NbtList lore = display.getList("Lore", NbtElement.STRING_TYPE);
        for (int i = 0; i < lore.size(); i++) {
            if (json.equals(lore.getString(i))) return;
        }
        NbtList updated = new NbtList();
        updated.addAll(lore);
        updated.add(NbtString.of(json));
        display.put("Lore", updated);
    }

    /**
     * 检查物品是否已绑定所有者（**只认名字**）。
     *
     * <p>★UUID 不算有主★：只写了 UUID 没有名字的物品按「公共」处理 —— 判定口径只有名字，
     * 保留一个没有钥匙的锁只会让物品永远说不清归谁。</p>
     */
    public static boolean hasOwner(ItemStack stack) {
        if (!stack.hasNbt()) return false;
        return stack.getNbt().contains(OWNER_NAME_KEY);
    }

    /**
     * 获取物品的所有者名称（绑定时的名字快照；旧档可能只有它）
     */
    public static String getOwnerName(ItemStack stack) {
        if (!stack.hasNbt() || !stack.getNbt().contains(OWNER_NAME_KEY)) return null;
        return stack.getNbt().getString(OWNER_NAME_KEY);
    }

    /**
     * 获取物品的归属 UUID（**仅诊断/日志用，不参与判定**）。
     *
     * <p>这是 B57 时期写入的历史字段。现在归属只看名字，这里留着是为了：① 排查老存档时能看出
     * 「这件物品当初被谁绑过」；② 探针能断言「新绑定不再写 UUID」。返回 {@code null} 是常态。</p>
     */
    public static UUID getOwnerUuid(ItemStack stack) {
        if (!stack.hasNbt() || !stack.getNbt().containsUuid(OWNER_UUID_KEY)) return null;
        try {
            return stack.getNbt().getUuid(OWNER_UUID_KEY);
        } catch (RuntimeException e) {
            LegacyInit.LOGGER.warn("Malformed {} on {} (ignored)", OWNER_UUID_KEY,
                    stack.getItem().getTranslationKey());
            return null;
        }
    }

    /**
     * **纯判定**：不判 OP、不写盘、不补 lore（供 {@code isOwner} 与探针共用）。
     *
     * <p>口径（2026-10-23 Aki 定案）：**名字是唯一钥匙**。</p>
     * <ol>
     *   <li>无主 ⇒ true（公共物品）</li>
     *   <li>有主 ⇒ {@code ownerName.equals(name)}；条目里没有名字的一律当公共</li>
     * </ol>
     *
     * <p>★为什么回退★：本服自用，正版/盗版的存档迁移都是**手动**的。UUID 优先带来的收益
     * （防改名易主）在这里不存在，代价却是「手动搬档后 UUID 变了 ⇒ 玩家自己的绑定物全部锁死」。
     * 改名/重名即易主是可接受的，由 OP 手动处理。</p>
     *
     * <p>{@code uuid} 参数保留只为不打断探针的调用签名，**函数体内不得读取它**（闸门 O1 看守）。</p>
     */
    public static boolean ownerMatches(ItemStack stack, UUID uuid, String name) {
        if (!hasOwner(stack)) return true;
        String ownerName = getOwnerName(stack);
        if (ownerName == null || ownerName.isEmpty()) return true;
        return ownerName.equals(name);
    }

    /**
     * 检查玩家是否为物品的所有者。
     * OP 玩家（权限等级 >= 4）无条件允许。
     */
    public static boolean isOwner(ItemStack stack, PlayerEntity player) {
        // 最直接的 OP 判定：权限等级 >= 4
        if (player.hasPermissionLevel(4)) {
            LegacyInit.LOGGER.debug("OP bypass for player: {}", player.getName().getString());
            return true;
        }
        if (!hasOwner(stack)) {
            // 无主物品视为公共，任何人都可以操作
            return true;
        }
        String playerName = player.getName().getString();
        boolean result = ownerMatches(stack, player.getUuid(), playerName);
        // 只服务端写，避免客户端 NBT 抖动
        if (!player.getWorld().isClient()) {
            // 旧存档的绑定物品只有 BoundOwnerName、没有 lore ⇒ 首次被交互时补一次
            applyOwnerLore(stack);
        }
        LegacyInit.LOGGER.debug("isOwner result: {} for player {} on item owned by {}", result,
                playerName, getOwnerName(stack));
        return result;
    }

}
