package com.akira.redtale.economy.legacy.item;

import com.akira.redtale.economy.legacy.data.PackageData;
import com.akira.redtale.economy.legacy.entity.PackageEntity;
import com.akira.redtale.economy.legacy.init.REHandlers;
import com.akira.redtale.economy.legacy.screen.PackageEditHandler;
import com.akira.redtale.economy.legacy.screen.PackageChooseHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
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
            stack.getOrCreateNbt().putBoolean(PROCESSED_ON_BLOCK_USE, true);
            PackageData data = PackageData.fromNbt(stack.getNbt());

            if (data.mode() == PackageData.PackageMode.CHOOSE) {
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

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (world.isClient()) {
            return TypedActionResult.success(stack);
        }

        if (!isPackage(stack)) {
            return TypedActionResult.pass(stack);
        }

        if (stack.hasNbt() && stack.getNbt().getBoolean(PROCESSED_ON_BLOCK_USE)) {
            stack.getNbt().remove(PROCESSED_ON_BLOCK_USE);
            return TypedActionResult.pass(stack);
        }

        if (user.getAbilities().creativeMode) {
            user.openHandledScreen(new ExtendedScreenHandlerFactory() {
                @Override
                public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
                    buf.writeNbt(stack.getNbt());
                }
                @Override
                public Text getDisplayName() {
                    return Text.translatable("screen.redtale_economy.edit_package");
                }
                @Override
                public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
                    return new PackageEditHandler(syncId, inv, new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
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

    @Override
    public double getTick(Object itemStack) {
        return 0;
    }

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
        super.appendTooltip(stack, world, tooltip, context);
    }
}