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
    private final ItemStack packageStack;
    private PackageData cachedData;

    private static final int BASE_ANIMATION_TICKS = 60;
    private static final int POST_GIVE_TICKS = 20;
    private static final int GIVE_INTERVAL = 3;

    private int age = 0;
    private boolean animationTriggered = false;
    private boolean rewardGiven = false;

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
        this.animationTriggered = false;
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
            if (!animationTriggered) {
                String animName = "idle".equals(this.getAnimationState()) ? "idle" : "open";
                this.triggerAnim("controller", animName);
                animationTriggered = true;
            }
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

            // 获取目标玩家：优先使用所有者
            PlayerEntity targetPlayer = null;
            if (ownerUuid != null) {
                targetPlayer = this.getWorld().getPlayerByUuid(ownerUuid);
            }
            // 如果所有者不在线，则尝试找最近的玩家（但ALL/RANDOM模式下我们倾向于掉落）
            if (targetPlayer == null) {
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
        this.cachedData = PackageData.fromNbt(packageStack.getNbt());
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (this.getWorld().isClient() && data == ANIMATION_STATE) {
            this.animationTriggered = false;
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
        String currentState = this.getAnimationState();
        if ("idle".equals(currentState)) {
            event.getController().setAnimation(RawAnimation.begin().thenLoop("idle"));
        } else {
            event.getController().setAnimation(RawAnimation.begin().thenPlay("open"));
        }
        return PlayState.CONTINUE;
    }
}