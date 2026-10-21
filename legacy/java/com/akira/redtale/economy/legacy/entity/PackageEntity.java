package com.akira.redtale.economy.legacy.entity;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.data.PackageData;
import com.akira.redtale.economy.legacy.init.REEntities;
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
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
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
    /** 包裹档位（normal/rare/legendary）。★必须同步★：客户端拿不到 packageStack（只有服务端构造器有），
     *  渲染贴图只能靠 DataTracker 下发，否则三种包裹在世界上长得一模一样。 */
    private static final TrackedData<String> PACKAGE_TIER = DataTracker.registerData(PackageEntity.class, TrackedDataHandlerRegistry.STRING);

    private boolean activated = false;
    private PackageData.PackageItem selectedItem = null;
    private UUID ownerUuid = null;
    private static final int MAX_CHOOSE_MODE_TICKS = 72000;
    private int chooseModeTickCount = 0;

    private int giveCounter = 0;
    private int currentGiveIndex = 0;

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
        this.dataTracker.set(PACKAGE_TIER, tierOf(this.packageStack));
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(ANIMATION_STATE, "open");
        this.dataTracker.startTracking(CHOOSE_MODE_ACTIVE, false);
        this.dataTracker.startTracking(PACKAGE_TIER, "normal");
    }

    /** 物品 id 的前缀就是档位：{@code rare_package} / {@code legendary_package} / 其余按 normal。 */
    private static String tierOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "normal";
        String path = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.startsWith("legendary")) return "legendary";
        if (path.startsWith("rare")) return "rare";
        return "normal";
    }

    /** 渲染层用：客户端只有这个值可用（packageStack 不同步）。 */
    public String getPackageTier() {
        return this.dataTracker.get(PACKAGE_TIER);
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
        this.setAnimationState("open");
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
        if (this.isChooseModeActive()) {
            chooseModeTickCount++;
            if (chooseModeTickCount > MAX_CHOOSE_MODE_TICKS) {
                this.discard();
                return;
            }
            if (ownerUuid != null) {
                PlayerEntity owner = this.getWorld().getPlayerByUuid(ownerUuid);
                if (owner == null || owner.squaredDistanceTo(this) > 400.0) {
                    this.discard();
                    return;
                }
            } else {
                if (chooseModeTickCount > 100) {
                    this.discard();
                    return;
                }
            }
            return;
        }

        // 非 CHOOSE 模式
        age++;

        // 粒子效果
        if (age >= 20 && age <= BASE_ANIMATION_TICKS + 10) {
            if (this.getWorld() instanceof ServerWorld serverWorld) {
                Vec3d pos = getPos();
                serverWorld.spawnParticles(ParticleTypes.ENCHANT, pos.x, pos.y + 0.5, pos.z, 1, 0.1, 0.1, 0.1, 0.01);
            }
        }

        // 奖励给予逻辑
        if (!rewardGiven) {
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
                // 如果玩家无效（离线或太远），将剩余所有物品掉落，然后结束
                if (!hasValidPlayer) {
                    // 掉落所有尚未给予的物品
                    while (currentGiveIndex < cachedData.items().size()) {
                        PackageData.PackageItem itemToGive = cachedData.items().get(currentGiveIndex);
                        if (itemToGive.weight() > 0 && !itemToGive.item().isEmpty()) {
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
                            if (itemToGive.weight() > 0 && !itemToGive.item().isEmpty()) {
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

    private void playGiveSound(ServerPlayerEntity player) {
        this.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0F, 1.0F);
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
        // ★必须存★：packageStack 不落盘（重载后是无参构造的 EMPTY），档位只能靠这个字段还原，
        // 否则实体存盘再读回会统一变 normal 贴图。
        nbt.putString("PackageTier", this.getPackageTier());
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
        if (nbt.contains("PackageTier")) {
            this.dataTracker.set(PACKAGE_TIER, nbt.getString("PackageTier"));
        }
        this.chooseModeTickCount = nbt.getInt("ChooseModeTickCount");
        this.giveCounter = nbt.getInt("GiveCounter");
        this.currentGiveIndex = nbt.getInt("CurrentGiveIndex");
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
        String target = "idle".equals(this.getAnimationState()) ? "idle" : "open";
        if (!target.equals(this.appliedAnimation)) {
            this.appliedAnimation = target;
            event.getController().setAnimation("idle".equals(target)
                    ? RawAnimation.begin().thenLoop("idle")
                    : RawAnimation.begin().thenPlay("open"));
        }
        return PlayState.CONTINUE;
    }
}