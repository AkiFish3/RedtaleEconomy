package com.akira.redtale.economy.legacy.entity;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.data.PackageData;
import com.akira.redtale.economy.legacy.init.REEntities;
import com.akira.redtale.economy.legacy.particles.PackageParticles;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

public class PackageEntity extends Entity implements GeoEntity {
    /** ★非 final★：{@code readCustomDataFromNbt} 需要从落盘数据把它还原回来（见 write/read 注释）。 */
    private ItemStack packageStack;
    private PackageData cachedData;

    private static final int BASE_ANIMATION_TICKS = 60;
    private static final int POST_GIVE_TICKS = 20;
    private static final int GIVE_INTERVAL = 3;

    private int age = 0;
    private boolean rewardGiven = false;
    /** 客户端：predicate 已下发的动画名。★只在目标变化时才 setAnimation★——
     *  每 tick 重设会把 thenPlay/thenLoop 动画钉在第一帧（看起来像静止贴图）。 */
    private String appliedAnimation = null;

    private static final TrackedData<String> ANIMATION_STATE = DataTracker.registerData(PackageEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Boolean> CHOOSE_MODE_ACTIVE = DataTracker.registerData(PackageEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    /** 包裹皮肤（{@code <series>_<tier>} 形式的物品 path）。★必须同步★：客户端拿不到 packageStack，
     *  渲染贴图只能靠 DataTracker 下发，否则所有系列/档位在世界上长得一模一样。 */
    private static final TrackedData<String> PACKAGE_SKIN = DataTracker.registerData(PackageEntity.class, TrackedDataHandlerRegistry.STRING);

    private boolean activated = false;
    private PackageData.PackageItem selectedItem = null;
    private UUID ownerUuid = null;
    /**
     * CHOOSE 模式选择超时：**100 tick（5 秒）**，见 `_PACKAGE_SPEC.md` §三
     * 「滚动动画 + 玩家确认；100 tick 未选自动 discard」。
     *
     * <p>★旧实现是 72000 tick（1 小时）★，两处口径还不一致：有主走 72000，无主走 100。
     * 结果是「玩家关掉界面走人」的包裹实体带着整包奖励在地上飘一小时
     * （每区块 tick 都在算目标玩家/距离），既是性能垃圾也是刷物品的入口
     * （1 小时内允许中途回来再选）。现在统一 100 tick。
     */
    private static final int CHOOSE_TIMEOUT_TICKS = 100;
    private int chooseModeTickCount = 0;

    private int giveCounter = 0;
    private int currentGiveIndex = 0;

    /** 开箱爆发只放一次（区块重载/存档读回不重放，否则每次加载都炸一次 60 颗）。 */
    private boolean burstDone = false;
    /** 领取特效只放一次（ALL 模式是逐件发，每件 25 颗会刷成粒子墙）。 */
    private boolean claimDone = false;

    // ————————————————— T74 special（surprise 彩蛋） —————————————————

    /** 蛋壳碎裂阶段长度（tick）。碎裂演完才切 open + 爆发，否则「先炸后裂」顺序反了。
     *  ★必须等于动画时长★：{@code surprise.animation.json} 的 crack = 1.2s = 24 tick（两下鼓胀 + 摇晃）。
     *  旧值 12 tick 只够播第一下，0.6~1.2s 的第二下永远看不到 ⇒ 看起来像「抖一下就炸」。 */
    private static final int CRACK_TICKS = 24;
    /** 开箱时随机决定的档位 id（只有 special 会填，其它包裹档位来自物品 id）。 */
    private String rolledTier = null;
    /** 正在播 crack：-1 = 不适用；>=0 = 已播的 tick 数。 */
    private int crackTicks = -1;
    private boolean eggCrackStarted = false;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public PackageEntity(EntityType<?> type, World world) {
        super(type, world);
        this.packageStack = ItemStack.EMPTY;
        this.cachedData = null;
        this.setNoGravity(true);
    }

    public PackageEntity(World world, double x, double y, double z, ItemStack stack, String initialAnimationState, boolean isChooseMode) {
        super(REEntities.PACKAGE_ENTITY, world);
        this.packageStack = stack == null ? ItemStack.EMPTY : stack.copy();
        this.cachedData = PackageData.fromNbt(this.packageStack.getNbt());
        this.setPosition(x, y, z);
        this.setNoGravity(true);
        this.dataTracker.set(ANIMATION_STATE, initialAnimationState != null ? initialAnimationState : "open");
        this.dataTracker.set(CHOOSE_MODE_ACTIVE, isChooseMode);
        this.dataTracker.set(PACKAGE_SKIN, skinOf(this.packageStack));
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(ANIMATION_STATE, "open");
        this.dataTracker.startTracking(CHOOSE_MODE_ACTIVE, false);
        this.dataTracker.startTracking(PACKAGE_SKIN, "package_normal");
    }

    /**
     * 物品 id 就是皮肤：{@code chest_legend} / {@code package_rare} / 旧别名 {@code legendary_package}。
     * 解析统一走 {@link PackageCatalog}（同一份 packages.json），别在这里写前缀判断 —— 3 个系列 × 6 档后
     * {@code startsWith("rare")} 这类写法会全部落到 normal。
     */
    private static String skinOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "package_normal";
        String path = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
        String[] skin = com.akira.redtale.economy.legacy.data.PackageCatalog.skinOf(path);
        return skin == null ? "package_normal" : path;
    }

    /** 皮肤 key（{@code <series>_<tier>}）。渲染层 / 粒子层都用这个。 */
    public String getPackageSkin() {
        return this.dataTracker.get(PACKAGE_SKIN);
    }

    /**
     * 档位 id（皮肤的后半段）。
     *
     * <p>★不能只取最后一个下划线之后★：旧 id 别名（{@code normal_package} / {@code rare_package} /
     * {@code legendary_package}）的 item path 是「档位在前、系列在后」⇒ 截出来是 {@code "package"}，
     * 于是 `particleMultiplier("package")` 回落 1.0、`tierColor("package")` 回落米黄
     * ⇒ 老存档里的稀有包裹与普通包裹粒子和颜色完全一样（零报错，只有肉眼看得出「怎么没特效」）。
     * 正确解法是问注册表要（同一份 packages.json 已经记了 alias → {series, tier}）。
     */
    public String getPackageTier() {
        String skin = getPackageSkin();
        String tier = com.akira.redtale.economy.legacy.data.PackageCatalog.tierOf(skin);
        if (tier != null) return tier;
        int i = skin.lastIndexOf('_');
        return i < 0 ? "normal" : skin.substring(i + 1);
    }

    /** 物品 path（{@code chest_legend} / {@code surprise}）。判 special 用。 */
    private String itemPath() {
        if (this.packageStack == null || this.packageStack.isEmpty()) return "";
        return net.minecraft.registry.Registries.ITEM.getId(this.packageStack.getItem()).getPath();
    }

    /** 是否是 special 单件（T74：彩蛋开箱随机档位）。 */
    public boolean isSpecialPackage() {
        return com.akira.redtale.economy.legacy.data.PackageCatalog.isSpecial(itemPath());
    }

    /**
     * 蛋壳还在裂（只有 special 会经过这个阶段）。
     *
     * <p>★这是「开箱」与「发奖」两段效果的**唯一闸门**★（第 62 轮）：规格 §九 的顺序是
     * 「碎裂 → 露出档位光效 → 给对应奖励」，而旧实现完全没看裂壳进度 —— special 实体在
     * 第 1 tick 就把奖励发了（同时放掉 25 颗领取粒子 + 升级音），而 `crack` 要 24 tick、
     * `open` 爆发要等 `crackTicks` 归 -1 才放 ⇒ **实测顺序变成「东西先到手 → 再裂壳 → 再炸」**。
     * 零报错、零日志，只有盯着看才发现，而 T74 的全部意义就是这一段演出。
     *
     * <p>普通包裹 {@code crackTicks} 恒 -1 ⇒ 本判据恒 false，行为与旧版完全一致。
     * 与「爆发」共用它（`!isOpeningShell()`）而不是各写一次 `crackTicks < 0`：
     * 两处口径一分叉就会出现「爆发等裂完、发奖不等」这类只在 special 上出现的错序。
     *
     * <p>反死锁：{@link #readCustomDataFromNbt} 一律把 {@code crackTicks} 归一成 -1
     * （存档停在裂壳中途时不能永远等下去），门 {@code _b62_egg_order.py} 的 E5 盯这一条。
     */
    public boolean isOpeningShell() {
        return this.crackTicks >= 0;
    }

    /**
     * 粒子/特效用的**有效档位**：special 在开箱时掷出一个普通档位，其它包裹就是自身档位。
     *
     * <p>★不能用 getPackageTier() 替代★：surprise 的皮肤档位恒是 {@code surprise}，
     * 粒子表里查不到 ⇒ 倍率回落 1.0，"开到传说"和"开到普通"的光效完全一样。
     */
    public String getEffectiveTier() {
        return this.rolledTier != null ? this.rolledTier : getPackageTier();
    }

    /** 掷一个非 special 档位（均匀）。同一实体只掷一次，落在 {@link #rolledTier}。 */
    private void rollTierIfSpecial() {
        if (this.rolledTier != null || !isSpecialPackage()) return;
        java.util.List<com.akira.redtale.economy.legacy.data.PackageCatalog.Tier> pool =
                new java.util.ArrayList<>();
        for (com.akira.redtale.economy.legacy.data.PackageCatalog.Tier t
                : com.akira.redtale.economy.legacy.data.PackageCatalog.tiers()) {
            if (!t.special()) pool.add(t);
        }
        if (pool.isEmpty()) return;
        this.rolledTier = pool.get(this.random.nextInt(pool.size())).id();
        LegacyInit.LOGGER.info("[legacy] surprise 开出档位 {} @ {}", this.rolledTier, this.getBlockPos());
    }

    /** special 先裂壳再开箱；普通包裹直接 open。 */
    private void beginOpenAnimation() {
        if (isSpecialPackage()) {
            this.eggCrackStarted = true;
            this.crackTicks = 0;
            this.setAnimationState("crack");
        } else {
            this.setAnimationState("open");
        }
    }

    public void setAnimationState(String state) {
        this.dataTracker.set(ANIMATION_STATE, state);
    }

    public String getAnimationState() {
        return this.dataTracker.get(ANIMATION_STATE);
    }

    public boolean isChooseModeActive() {
        return this.dataTracker.get(CHOOSE_MODE_ACTIVE);
    }

    public void setOwnerUuid(UUID uuid) {
        this.ownerUuid = uuid;
    }

    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }

    public void setSelectedItem(PackageData.PackageItem item) {
        if (activated) return;
        this.selectedItem = item;
        this.activated = true;
        this.beginOpenAnimation();
        this.dataTracker.set(CHOOSE_MODE_ACTIVE, false);
        this.appliedAnimation = null;
        this.chooseModeTickCount = 0;
    }

    /**
     * 立即销毁实体（用于关闭界面时）
     */
    public void destroyImmediately() {
        if (this.getWorld() != null && !this.getWorld().isClient()) {
            this.discard();
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient()) {
            // 动画统一由 predicate 驱动（同一目标动画只 setAnimation 一次）。
            // 旧写法：这里 triggerAnim + predicate 里每 tick thenPlay("open") 互相抢控制权，
            // 结果是 open 播完被重置回第一帧、idle 也每 tick 重置 ⇒ 看起来完全是静止贴图。
            return;
        }

        // 服务端逻辑
        // ★T74 special★：蛋壳先裂再炸 —— 掷档位 → 播 crack（CRACK_TICKS）→ 切 open 才允许爆发。
        // 普通包裹 crackTicks 恒 -1，走的还是原来那条「第一 tick 就爆」的路径。
        if (!this.isChooseModeActive() && isSpecialPackage()) {
            rollTierIfSpecial();
            if (!eggCrackStarted) {
                beginOpenAnimation();
            } else if (this.crackTicks >= 0 && ++this.crackTicks >= CRACK_TICKS) {
                this.crackTicks = -1;
                this.setAnimationState("open");
            }
        }

        // ★只在「真的开箱」时爆发★：CHOOSE 模式实体刚生成时还是待选状态，玩家选完
        // （setSelectedItem ⇒ 不再是 choose 模式）才轮到开箱爆发，否则没选就先炸一次。
        // ★蛋壳没裂完不许炸★（T74）—— 与下面「发奖」共用同一个 isOpeningShell() 口径。
        if (this.getWorld() instanceof ServerWorld burstWorld && !burstDone && !this.isChooseModeActive()
                && !isOpeningShell()) {
            burstDone = true;
            PackageParticles.openBurst(burstWorld, this.getPos(), this.getEffectiveTier());
        }
        if (this.isChooseModeActive()) {
            chooseModeTickCount++;
            // 选择中也是「待机」：调色板里保证有一圈档位色，玩家才分得出这是哪档包裹
            if (this.getWorld() instanceof ServerWorld idleWorld && chooseModeTickCount % PackageParticles.IDLE_INTERVAL == 0) {
                PackageParticles.idle(idleWorld, this.getPos(), this.getEffectiveTier());
            }
            if (chooseModeTickCount > CHOOSE_TIMEOUT_TICKS) {
                this.discard();
                return;
            }
            // 有主：只认主人（下线/走远都直接销毁，别把整包内容留在世界里）
            if (ownerUuid != null) {
                PlayerEntity owner = this.getWorld().getPlayerByUuid(ownerUuid);
                if (owner == null || owner.squaredDistanceTo(this) > 400.0) {
                    this.discard();
                    return;
                }
            }
            return;
        }

        // 非 CHOOSE 模式
        age++;

        // 待机粒子：旧实现 age 20~70 每 tick 1 颗 ENCHANT（共 50 颗，还都在同一个点），
        // 「除了出生那几秒完全看不出是个包裹」。现在每 5 tick 3~5 颗 × 档位倍率（见 PackageParticles）。
        if (this.getWorld() instanceof ServerWorld idleWorld && age % PackageParticles.IDLE_INTERVAL == 0) {
            PackageParticles.idle(idleWorld, this.getPos(), this.getEffectiveTier());
        }

        // 奖励给予逻辑
        // ★蛋壳没裂完不许发奖★（第 62 轮）：规格 §九 是「碎裂 → 档位光效 → 给奖励」。
        // 旧实现不看裂壳进度 ⇒ special 在第 1 tick 就 `offerOrDrop` 并放掉领取粒子/音效，
        // 而 crack 要 CRACK_TICKS=24、open 爆发更晚 ⇒ 玩家看到「物品先到手，蛋后炸」，
        // 自动化的判据也读不出错（RewardGiven 早就是 true，零报错零日志）。
        // 非 special 的 crackTicks 恒 -1 ⇒ 这里恒真，行为与旧版一致。
        if (!rewardGiven && !isOpeningShell()) {
            if (cachedData == null) {
                cachedData = PackageData.fromNbt(packageStack.getNbt());
                if (cachedData == null) {
                    rewardGiven = true;
                    return;
                }
            }

            // 获取目标玩家：有主包裹只认主人
            PlayerEntity targetPlayer = null;
            if (ownerUuid != null) {
                // ★主人下线时不能退化成「谁离得近给谁」★：包裹是主人右键放出来的，
                // 主人断线后实体仍留在原地继续 tick，旧实现在 getPlayerByUuid 返回 null 时
                // 回落到 getClosestPlayer(…,5.0,…) ⇒ 站在旁边的任何陌生人都会被当成收件人，
                // 把主人没领完的奖励直接收进背包（2026-10-22 实测：主人领 5/12、剩余 7 件全进陌生人，
                // 地面掉落 0）。现在主人不在线就按「掉落」处理，与代码注释的原意一致。
                targetPlayer = this.getWorld().getPlayerByUuid(ownerUuid);
            } else {
                // 无主包裹（命令/活动生成）才允许就近发放
                targetPlayer = this.getWorld().getClosestPlayer(this.getX(), this.getY(), this.getZ(), 5.0, false);
            }

            // 判断玩家是否有效（在线且在一定距离内）
            boolean hasValidPlayer = (targetPlayer instanceof ServerPlayerEntity serverPlayer && serverPlayer.squaredDistanceTo(this) <= 400.0);

            // RANDOM 模式
            if (cachedData.mode() == PackageData.PackageMode.RANDOM) {
                PackageData.PackageItem itemToGive = cachedData.getRandomItem(this.getWorld().getRandom());
                if (itemToGive != null && itemToGive.weight() > 0 && !itemToGive.item().isEmpty()) {
                    if (hasValidPlayer) {
                        // 玩家在线：直接加入背包
                        ((ServerPlayerEntity) targetPlayer).getInventory().offerOrDrop(itemToGive.item().copy());
                        playGiveSound((ServerPlayerEntity) targetPlayer);
                    } else {
                        // 玩家离线或距离过远：掉落物品
                        dropItemAsEntity(itemToGive.item().copy());
                    }
                } else {
                    LegacyInit.LOGGER.warn("RANDOM package has no valid items to give at {}", this.getPos());
                }
                rewardGiven = true;
            }
            // CHOOSE 模式已由其他逻辑处理（selectedItem），这里只处理 ALL 和 RANDOM
            else if (selectedItem != null) {
                // 这是 CHOOSE 模式的结果
                if (hasValidPlayer) {
                    ((ServerPlayerEntity) targetPlayer).getInventory().offerOrDrop(selectedItem.item().copy());
                    playGiveSound((ServerPlayerEntity) targetPlayer);
                } else {
                    dropItemAsEntity(selectedItem.item().copy());
                }
                rewardGiven = true;
            }
            // ALL 模式
            else if (cachedData.mode() == PackageData.PackageMode.ALL) {
                // ★空 ALL 包必须留痕★（B56）：items 为空时下面两个分支都一次不发、直接 rewardGiven，
                // 80 tick 后实体消失。旧实现零日志 ⇒ 和 RANDOM 分支的 "no valid items to give" 不对称，
                // 管理员只能看到「包裹开了但没东西」。只记一次（下一 tick rewardGiven 已 true）。
                if (!cachedData.hasGiveableItem()) {
                    LegacyInit.LOGGER.warn("空 ALL 包裹实体 {} 不发奖（items=0，位置 {}）", this.getUuid(), this.getPos());
                }
                // 如果玩家无效（离线或太远），将剩余所有物品掉落，然后结束
                if (!hasValidPlayer) {
                    // 掉落所有尚未给予的物品
                    while (currentGiveIndex < cachedData.items().size()) {
                        PackageData.PackageItem itemToGive = cachedData.items().get(currentGiveIndex);
                        if (shouldGiveInAll(itemToGive)) {
                            dropItemAsEntity(itemToGive.item().copy());
                        }
                        currentGiveIndex++;
                    }
                    rewardGiven = true;
                } else {
                    // 玩家在线：按间隔给予
                    // ★giveCounter 每 tick 自增★：旧实现漏了 ++ ⇒ `0 % 3 == 0` 恒真，
                    // 10 件物品会在 10 个 tick 内一次性发完（GIVE_INTERVAL 是死代码，包裹看起来像"瞬间空包"）。
                    if (giveCounter % GIVE_INTERVAL == 0) {
                        if (currentGiveIndex < cachedData.items().size()) {
                            PackageData.PackageItem itemToGive = cachedData.items().get(currentGiveIndex);
                            if (shouldGiveInAll(itemToGive)) {
                                ((ServerPlayerEntity) targetPlayer).getInventory().offerOrDrop(itemToGive.item().copy());
                                playGiveSound((ServerPlayerEntity) targetPlayer);
                            }
                            currentGiveIndex++;
                            if (currentGiveIndex >= cachedData.items().size()) {
                                rewardGiven = true;
                            }
                        } else {
                            rewardGiven = true;
                        }
                    }
                    giveCounter++;
                }
            }
        }

        // 移除条件
        if (rewardGiven) {
            if (age >= BASE_ANIMATION_TICKS + POST_GIVE_TICKS) {
                this.discard();
            }
        } else {
            if (age > 600) {
                this.discard();
            }
        }
    }

    /**
     * ALL 模式的一条该不该发：**只看物品是否为空，不看权重**。
     *
     * <p>★权重是 RANDOM 专属概念★（{@code _PACKAGE_SPEC.md} §三：ALL=全部给 / RANDOM=按 weight 抽 1 个；
     * {@code PackageEditHandler.applySetWeight} 的注释也写明「0 = 该条目在 RANDOM 里被跳过（但仍留在包里）」）。
     * 旧实现两处 ALL 分支都写了 {@code weight() > 0}，于是把权重调到 0 的条目在 ALL 包里**静默消失**：
     * 编辑器照样画金色 0、NBT 里条目还在（总数没变），玩家只会看到「礼包少给了一件」，零报错零日志。
     *
     * <p>抽成方法是为了让 {@code /redtale bound weightprobe} 能纯逻辑断言（不需要客户端/实体），
     * 也避免「两处 ALL 分支各写一遍判据」再次分叉。
     */
    public static boolean shouldGiveInAll(PackageData.PackageItem item) {
        return item != null && !item.item().isEmpty();
    }

    private void playGiveSound(ServerPlayerEntity player) {
        this.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0F, 1.0F);
        // 领取特效（25 颗 TOTEM 上飘）★每个实体只放一次★：ALL 模式逐件发放，
        // 每件都放一遍就是 12×25=300 颗，画面直接糊成一片白。
        if (!claimDone && this.getWorld() instanceof ServerWorld claimWorld) {
            claimDone = true;
            PackageParticles.claim(claimWorld, this.getPos(), this.getEffectiveTier());
        }
    }

    /**
     * 在实体位置生成物品掉落物
     */
    private void dropItemAsEntity(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemEntity itemEntity = new ItemEntity(this.getWorld(), this.getX(), this.getY(), this.getZ(), stack);
        itemEntity.setVelocity(0, 0.1, 0); // 轻微向上速度
        this.getWorld().spawnEntity(itemEntity);
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putString("AnimationState", this.getAnimationState());
        // ★必须存★：packageStack 不落盘（重载后是无参构造的 EMPTY），皮肤只能靠这个字段还原，
        // 否则实体存盘再读回会统一变 package_normal 贴图。
        nbt.putString("PackageSkin", this.getPackageSkin());
        // ★必须存★（本轮修复）：packageStack 才是奖励内容的唯一真值。旧实现只存档位字符串，
        // 读回时 packageStack = EMPTY ⇒ cachedData = null ⇒ tick 里直接 `rewardGiven = true` 静默结束
        // ⇒ **区块卸载/存档重载后包裹实体不发奖也不报错**（多人服挂机过区块边界即复现）。
        if (this.packageStack != null && !this.packageStack.isEmpty()) {
            nbt.put("PackageStack", this.packageStack.writeNbt(new NbtCompound()));
        }
        nbt.putInt("Age", this.age);
        nbt.putBoolean("RewardGiven", this.rewardGiven);
        nbt.putBoolean("Activated", this.activated);
        nbt.putBoolean("ChooseModeActive", this.isChooseModeActive());
        nbt.putInt("ChooseModeTickCount", this.chooseModeTickCount);
        nbt.putInt("GiveCounter", this.giveCounter);
        nbt.putInt("CurrentGiveIndex", this.currentGiveIndex);
        nbt.putBoolean("BurstDone", this.burstDone);
        nbt.putBoolean("ClaimDone", this.claimDone);
        // T74：special 掷出的档位必须落盘 —— 否则区块重载后同一次开箱会重掷一个不同档位
        // （粒子/日志与实际发放对不上，且重掷没有上限）。
        if (this.rolledTier != null) {
            nbt.putString("RolledTier", this.rolledTier);
        }
        if (ownerUuid != null) {
            nbt.putUuid("OwnerUUID", ownerUuid);
        }
        if (this.selectedItem != null) {
            NbtCompound selectedItemNbt = new NbtCompound();
            selectedItemNbt.put("item", this.selectedItem.item().writeNbt(new NbtCompound()));
            selectedItemNbt.putInt("weight", this.selectedItem.weight());
            nbt.put("SelectedItem", selectedItemNbt);
        }
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        if (this.age == 0 && nbt.contains("AnimationState")) {
            this.setAnimationState(nbt.getString("AnimationState"));
        }
        this.age = nbt.getInt("Age");
        this.rewardGiven = nbt.getBoolean("RewardGiven");
        this.activated = nbt.getBoolean("Activated");
        this.dataTracker.set(CHOOSE_MODE_ACTIVE, nbt.getBoolean("ChooseModeActive"));
        if (nbt.contains("PackageSkin")) {
            this.dataTracker.set(PACKAGE_SKIN, nbt.getString("PackageSkin"));
        } else if (nbt.contains("PackageTier")) {
            // 旧存档：只有档位、没有系列 ⇒ 归到 package 系列（legendary 是旧 id 的写法）
            String old = nbt.getString("PackageTier");
            if ("legendary".equals(old)) old = "legend";
            this.dataTracker.set(PACKAGE_SKIN, "package_" + old);
        }
        this.chooseModeTickCount = nbt.getInt("ChooseModeTickCount");
        this.giveCounter = nbt.getInt("GiveCounter");
        this.currentGiveIndex = nbt.getInt("CurrentGiveIndex");
        this.burstDone = nbt.getBoolean("BurstDone");
        this.claimDone = nbt.getBoolean("ClaimDone");
        if (nbt.contains("RolledTier")) {
            this.rolledTier = nbt.getString("RolledTier");
        }
        // 读回时碎裂阶段一律视为已结束：重放 crack 会把「已经开过的蛋」再裂一次，
        // 且 crackTicks 归零会卡住 burst 门（burstDone 已 true，不会再炸）。
        this.eggCrackStarted = true;
        this.crackTicks = -1;
        // ★状态也要跟着归一★：存档可能正好停在 "crack"（裂到一半区块卸载），而上面已把 crackTicks
        // 置 -1、eggCrackStarted 置 true ⇒ tick 里再没有任何分支会把它推到 "open" ⇒ 实体永久停在裂壳帧
        // （客户端 predicate 照这个状态播）。读到 crack 就当开箱已完成。
        if ("crack".equals(this.getAnimationState())) {
            this.setAnimationState("open");
        }
        if (nbt.containsUuid("OwnerUUID")) {
            this.ownerUuid = nbt.getUuid("OwnerUUID");
        }
        if (nbt.contains("SelectedItem")) {
            NbtCompound sel = nbt.getCompound("SelectedItem");
            ItemStack item = ItemStack.fromNbt(sel.getCompound("item"));
            int weight = sel.getInt("weight");
            this.selectedItem = new PackageData.PackageItem(item, weight);
        }
        if (nbt.contains("PackageStack")) {
            this.packageStack = ItemStack.fromNbt(nbt.getCompound("PackageStack"));
        }
        this.cachedData = PackageData.fromNbt(this.packageStack.getNbt());
        if (!nbt.contains("PackageSkin") && !nbt.contains("PackageTier") && !this.packageStack.isEmpty()) {
            // 更老的存档（连 PackageTier 都没写）：皮肤直接从物品 id 反推
            this.dataTracker.set(PACKAGE_SKIN, skinOf(this.packageStack));
        }
        if (this.packageStack.isEmpty() && !this.isChooseModeActive()) {
            // 落盘里没有 PackageStack（本修复之前存档的实体）⇒ 奖励内容真丢了，留一条可诊断的日志。
            // ★注意：fromNbt(null) 返回的是 RANDOM/空列表而不是 null★，所以不能靠 cachedData == null 判断，
            // 要判包本身是否空；否则旧行为是在 tick 里打一句 "no valid items" 然后静默结束。
            LegacyInit.LOGGER.warn("PackageEntity {} 读档后 PackageStack 为空，奖励内容丢失（旧存档）", this.getUuid());
        }
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (this.getWorld().isClient() && data == ANIMATION_STATE) {
            this.appliedAnimation = null;
        }
    }

    @Override
    public boolean canHit() { return false; }
    public ItemStack getPackageStack() { return packageStack; }
    public boolean isRewardGiven() { return this.rewardGiven; }
    public boolean isActivated() { return this.activated; }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, this::predicate));
    }

	private <T extends GeoEntity> PlayState predicate(AnimationState<T> event) {
		// ★三种状态都要映射★：旧实现只认 idle，其余一律当 open ⇒ T74 的 `crack` 永远播不到
		// （beginOpenAnimation 明明设了 "crack"，surprise.animation.json 里也有 crack 键，
		// 但客户端拿到的永远是 open ⇒ 蛋壳不裂直接炸，零报错）。这就是 T38 那类「代码里的动画名
		// 与 json 键对不上」的同一个坑，只是这次是「映射表漏了一项」。
		String state = this.getAnimationState();
		String target = ("idle".equals(state) || "crack".equals(state)) ? state : "open";
		if (!target.equals(this.appliedAnimation)) {
			this.appliedAnimation = target;
			event.getController().setAnimation(switch (target) {
				case "idle" -> RawAnimation.begin().thenLoop("idle");
				case "crack" -> RawAnimation.begin().thenPlay("crack");
				default -> RawAnimation.begin().thenPlay("open");
			});
		}
		return PlayState.CONTINUE;
	}
}