package com.akira.redtale.economy.legacy.item;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.data.PackageData;
import com.akira.redtale.economy.legacy.entity.PackageEntity;
import com.akira.redtale.economy.legacy.init.REHandlers;
import com.akira.redtale.economy.legacy.screen.PackageEditHandler;
import com.akira.redtale.economy.legacy.screen.PackageChooseHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.RenderProvider;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.akira.redtale.economy.legacy.init.RENetworking.isPackage;

public class PackageItem extends Item implements GeoItem {

    private static final String PROCESSED_ON_BLOCK_USE = "ProcessedOnBlockUse";
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Supplier<Object> renderProvider = GeoItem.makeRenderer(this);

    public PackageItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        PlayerEntity player = context.getPlayer();
        ItemStack stack = context.getStack();

        if (!world.isClient() && player != null && isPackage(stack)) {
            // ★不再写 ProcessedOnBlockUse 标记★：本方法在成功分支一律返回 success，
            // 原版 `ServerPlayerInteractionManager.interactBlock` 收到非 PASS 就不会再调 {@code use}，
            // 所以这个"防重复处理"标记根本没有机会被读到 —— 它只会残留在物品 NBT 里，
            // 让下次右键（空气）被 `use` 吞掉一次，并让同名包裹因为 NBT 不同而无法堆叠。
            // 旧存档残留的键在这里顺手清掉（幂等）。
            if (stack.hasNbt() && stack.getNbt().contains(PROCESSED_ON_BLOCK_USE)) {
                stack.getNbt().remove(PROCESSED_ON_BLOCK_USE);
            }
            PackageData data = PackageData.fromNbt(stack.getNbt());
            // ★空包裹不许开★：裸 id（没有 NBT）的包裹 `mode` 默认成 ALL、items 是空表，
            // 旧实体会被刷出来、80 tick 后静默消失 —— 物品扣了、奖励 0 件、日志 0 行。
            // 这里直接拒绝并留痕（不 spawn 也不 decrement），管理员能从日志看到是哪件物品。
            if (!data.hasGiveableItem()) {
                LegacyInit.LOGGER.warn("[legacy] 空包裹 {} 被 {} 右键，已拒绝开启（物品未消耗）：{}",
                        net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath(),
                        player.getName().getString(), context.getBlockPos());
                return ActionResult.PASS;
            }

            if (data.mode() == PackageData.PackageMode.CHOOSE) {
                // ★一个玩家同时只能有一个「未激活的 CHOOSE 实体」★
                // 旧实现每次右键都 spawn 一个新实体、不清理上一个 ⇒ 连点两下（或改过的客户端连发
                // interact 包）会在同一格堆出 N 个 choose 实体，界面只开最后一个，其余全成孤儿：
                // 它们带着整包内容飘在世界上、既不会被奖励也不会被清理，只能等 1 小时超时/主人走远。
                // 2026-10-22 实测：连点两次 → `kill @e[type=package_entity]` 回 "Killed 2 entities"。
                discardStaleChooseEntities(world, player);
                PackageEntity entity = new PackageEntity(
                        world,
                        context.getBlockPos().getX() + 0.5,
                        context.getBlockPos().getY() + 1.0,
                        context.getBlockPos().getZ() + 0.5,
                        stack.copy(),
                        "idle",
                        true
                );
                // 设置所有者 UUID
                entity.setOwnerUuid(player.getUuid());
                world.spawnEntity(entity);

                int entityId = entity.getId();
                NbtCompound packageNbt = stack.getNbt();
                if (packageNbt == null) packageNbt = new NbtCompound();

                NbtCompound finalPackageNbt = packageNbt;
                ((ServerPlayerEntity) player).openHandledScreen(new ExtendedScreenHandlerFactory() {
                    @Override
                    public void writeScreenOpeningData(ServerPlayerEntity serverPlayer, PacketByteBuf buf) {
                        buf.writeVarInt(entityId);
                        buf.writeNbt(finalPackageNbt);
                    }

                    @Override
                    public Text getDisplayName() {
                        return Text.translatable("screen.redtale_economy.choose_package");
                    }

                    @Override
                    public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity playerEntity) {
                        return new PackageChooseHandler(syncId, inv, entityId, finalPackageNbt);
                    }
                });

                return ActionResult.success(world.isClient());
            } else {
                PackageEntity entity = new PackageEntity(
                        world,
                        context.getBlockPos().getX() + 0.5,
                        context.getBlockPos().getY() + 1.0,
                        context.getBlockPos().getZ() + 0.5,
                        stack.copy(),
                        "open",
                        false
                );
                // 设置所有者 UUID（ALL/RANDOM 模式也需要，确保奖励给正确的玩家）
                entity.setOwnerUuid(player.getUuid());
                world.spawnEntity(entity);

                if (!player.getAbilities().creativeMode) {
                    stack.decrement(1);
                }

                return ActionResult.success(world.isClient());
            }
        }

        return ActionResult.PASS;
    }

    /**
     * 销毁该玩家身上「未激活」的旧 CHOOSE 实体（本人 + 16 格内）。
     *
     * <p>只清未激活的：已选择（{@code activated}）的实体正处在发奖过程中，
     * 杀掉它就等于吞掉玩家已经选中的奖励。距离限制 16 格是为了不误伤同一玩家在别的维度/远处
     * 正常打开的第二个界面（正常情况下不可能同时开两个，留余量）。
     */
    private static void discardStaleChooseEntities(World world, PlayerEntity player) {
        if (world.isClient() || player == null) return;
        Box box = player.getBoundingBox().expand(16.0);
        for (PackageEntity old : world.getEntitiesByClass(PackageEntity.class, box,
                e -> e.isChooseModeActive() && !e.isActivated() && player.getUuid().equals(e.getOwnerUuid()))) {
            old.destroyImmediately();
        }
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (world.isClient()) {
            return TypedActionResult.success(stack);
        }

        if (!isPackage(stack)) {
            return TypedActionResult.pass(stack);
        }

        if (stack.hasNbt() && stack.getNbt().contains(PROCESSED_ON_BLOCK_USE)) {
            // 旧存档残留标记：清掉但**不吞这次右键**（旧实现直接 return pass ⇒ 玩家第一次右键空气无效）。
            stack.getNbt().remove(PROCESSED_ON_BLOCK_USE);
        }

        if (user.getAbilities().creativeMode) {
            final Hand usedHand = hand;
            user.openHandledScreen(new ExtendedScreenHandlerFactory() {
                @Override
                public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
                    buf.writeNbt(stack.getNbt());
                    // 顺序必须与 createMenu 一致：nbt → hand（PackageEditHandler 按此顺序读）
                    buf.writeEnumConstant(usedHand);
                }
                @Override
                public Text getDisplayName() {
                    return Text.translatable("screen.redtale_economy.edit_package");
                }
                @Override
                public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
                    PacketByteBuf b = PacketByteBufs.create();
                    b.writeNbt(stack.getNbt());
                    b.writeEnumConstant(usedHand);
                    return new PackageEditHandler(syncId, inv, b);
                }
            });
            return TypedActionResult.success(stack);
        }

        return TypedActionResult.pass(stack);
    }

    // --- GeoItem Interface Methods ---
    @Override
    public void createRenderer(Consumer<Object> consumer) {
        consumer.accept(new RenderProvider() {
            private com.akira.redtale.economy.legacy.item.client.PackageItemRenderer renderer;

            @Override
            public net.minecraft.client.render.item.BuiltinModelItemRenderer getCustomRenderer() {
                if (this.renderer == null) {
                    this.renderer = new com.akira.redtale.economy.legacy.item.client.PackageItemRenderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public Supplier<Object> getRenderProvider() {
        return this.renderProvider;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, this::predicate));
    }

    private <T extends GeoItem> PlayState predicate(AnimationState<T> event) {
        // ★动画名必须与 package_item.animation.json 里的键一致★：该文件里叫 item.idle，
        // 旧模这里写 "idle" ⇒ geckolib 找不到动画，物品永远是静止姿势（且只在日志里留一句 warning）。
        event.getController().setAnimation(RawAnimation.begin().thenLoop("item.idle"));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    // ★不要覆盖 GeoItem#getTick★：默认实现返回 RenderUtils.getCurrentTick()（活的渲染 tick），
    // 而旧模这里写死 `return 0`。javap 实证（geckolib 4.7.1.2）：
    //   GeoItemRenderer.actuallyRender → AnimationState.setData(DataTickets.TICK, getTick(stack))  ← 非 null
    //   → GeoModel.handleAnimations：TICK 非 null 所以**不会**回落到 getCurrentTick，
    //     物品分支算 animTime = tick - firstTickTime（恒为常数）
    //   → 之后每帧 `animTime == getLastUpdateTime()` 为真、instanceId 未变 ⇒ 方法在 updatedAt() 之前就 return
    // ⇒ 管理器一辈子只被更新一次：控制器只跑首帧，物品动画永远停在第 0 帧。
    // 现场症状：T74 给 surprise 画的 item.idle（egg 骨骼 ±4° 摇晃，3 秒循环）从来没有播放过，
    // 包裹/宝箱/货箱的 item.idle 是空动画，所以肉眼也看不出差别 —— 零日志、零报错。
    // 回归门：`python _b37_item_tick.py`。

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        PackageData data = PackageData.fromNbt(stack.getNbt());
        if (data != null) {
            Text modeText = Text.translatable("mode.redtale_economy." + data.mode().name().toLowerCase());
            MutableText tooltipText = Text.translatable("tooltip.redtale_economy.mode")
                    .append(": ")
                    .append(modeText);
            tooltipText.setStyle(tooltipText.getStyle().withColor(Formatting.GRAY));
            tooltip.add(tooltipText);
        }

        // 档位行：皮肤 key 的后半段就是 tier id（package_rare → rare），名字走 lang 的 tier.* 键。
        String path = net.minecraft.registry.Registries.ITEM.getId(this).getPath();
        String[] skin = com.akira.redtale.economy.legacy.data.PackageCatalog.skinOf(path);
        if (skin != null && skin[1] != null) {
            MutableText tierLine = Text.translatable("tooltip.redtale_economy.tier")
                    .append(": ")
                    .append(Text.translatable("tier.redtale_economy." + skin[1]));
            tierLine.setStyle(tierLine.getStyle().withColor(Formatting.DARK_GRAY));
            tooltip.add(tierLine);
        }

        super.appendTooltip(stack, world, tooltip, context);
    }
}