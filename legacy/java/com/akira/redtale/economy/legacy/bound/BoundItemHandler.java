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
    /** 归属人的 UUID（{@code NbtCompound.putUuid} 的 `[I;` 四段式）。
     *  ★必须与名字并存、且判定以 UUID 为准★：只存名字时，玩家改名（正版改名 / 离线服改 usercache）
     *  会让绑定物永久锁死；更糟的是旧名字被别人抢占后，**别人能直接拿走你的绑定物**（名字即钥匙）。
     *  名字保留只用于 tooltip / 旧档回退。 */
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
     * 为物品绑定所有者（名字 + UUID 双写）
     */
    public static void bindToPlayer(ItemStack stack, PlayerEntity player) {
        if (stack.isEmpty()) return;
        if (!BoundItemsConfig.isBoundItem(stack)) return;
        if (hasOwner(stack)) return;

        writeOwner(stack, player.getUuid(), player.getName().getString());
        applyOwnerLore(stack);
        LegacyInit.LOGGER.info("Bound item {} to player {} ({})", stack.getItem().getTranslationKey(),
                player.getName().getString(), player.getUuid());
    }

    /**
     * 写归属（UUID + 名字）。
     *
     * <p>抽成不依赖 {@code PlayerEntity} 的纯写入方法，是为了让
     * {@code /redtale bound ownerprobe} 能在没有真人/实体的情况下断言
     * 「UUID 真的写进去了」（B57）。</p>
     */
    public static void writeOwner(ItemStack stack, UUID uuid, String name) {
        if (stack.isEmpty()) return;
        NbtCompound tag = stack.getOrCreateNbt();
        if (uuid != null) tag.putUuid(OWNER_UUID_KEY, uuid);
        if (name != null && !name.isEmpty()) tag.putString(OWNER_NAME_KEY, name);
    }

    /** 旧档迁移用：只补 UUID，不动名字/lore。 */
    public static void adoptOwner(ItemStack stack, UUID uuid) {
        if (stack.isEmpty() || uuid == null) return;
        stack.getOrCreateNbt().putUuid(OWNER_UUID_KEY, uuid);
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
     * 检查物品是否已绑定所有者（名字或 UUID 任一存在即算有主）
     */
    public static boolean hasOwner(ItemStack stack) {
        if (!stack.hasNbt()) return false;
        NbtCompound tag = stack.getNbt();
        return tag.contains(OWNER_NAME_KEY) || tag.containsUuid(OWNER_UUID_KEY);
    }

    /**
     * 获取物品的所有者名称（绑定时的名字快照；旧档可能只有它）
     */
    public static String getOwnerName(ItemStack stack) {
        if (!stack.hasNbt() || !stack.getNbt().contains(OWNER_NAME_KEY)) return null;
        return stack.getNbt().getString(OWNER_NAME_KEY);
    }

    /**
     * 获取物品的归属 UUID；旧存档（只有名字）返回 {@code null}。
     */
    public static UUID getOwnerUuid(ItemStack stack) {
        if (!stack.hasNbt() || !stack.getNbt().containsUuid(OWNER_UUID_KEY)) return null;
        try {
            return stack.getNbt().getUuid(OWNER_UUID_KEY);
        } catch (RuntimeException e) {
            // 损坏的 UUID 不能变成"永久锁死"：当作没有，回退到名字判定
            LegacyInit.LOGGER.warn("Malformed {} on {}, falling back to name", OWNER_UUID_KEY,
                    stack.getItem().getTranslationKey());
            return null;
        }
    }

    /**
     * **纯判定**：不判 OP、不写盘、不补 lore（供 {@code isOwner} 与探针共用）。
     *
     * <p>口径（B57）：</p>
     * <ol>
     *   <li>无主 ⇒ true（公共物品）</li>
     *   <li>有 UUID ⇒ **只认 UUID**。名字不参与，否则改名/重名就能易主</li>
     *   <li>只有名字（旧档）⇒ 按名字，且首次交互会补 UUID（{@link #adoptOwner}）</li>
     * </ol>
     */
    public static boolean ownerMatches(ItemStack stack, UUID uuid, String name) {
        if (!hasOwner(stack)) return true;
        UUID ownerUuid = getOwnerUuid(stack);
        if (ownerUuid != null) return ownerUuid.equals(uuid);
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
            if (result && getOwnerUuid(stack) == null) {
                // 旧档（只有名字）第一次被合法主人碰到 ⇒ 补 UUID。之后改名/重名都不再改归属。
                adoptOwner(stack, player.getUuid());
                LegacyInit.LOGGER.info("Migrated bound owner name={} -> uuid={} on {}", playerName,
                        player.getUuid(), stack.getItem().getTranslationKey());
            }
            // 旧存档的绑定物品只有 BoundOwnerName、没有 lore ⇒ 首次被交互时补一次
            applyOwnerLore(stack);
        }
        LegacyInit.LOGGER.debug("isOwner result: {} for player {} on item owned by {} (uuid {})", result,
                playerName, getOwnerName(stack), getOwnerUuid(stack));
        return result;
    }

}
