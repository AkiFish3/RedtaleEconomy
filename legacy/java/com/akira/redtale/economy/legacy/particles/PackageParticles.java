package com.akira.redtale.economy.legacy.particles;

import com.akira.redtale.economy.legacy.data.PackageCatalog;
import com.akira.redtale.economy.legacy.data.PackageConfig;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

/**
 * 包裹粒子（{@code _PACKAGE_SPEC.md} §十，Aki 2026-10-22「旧模太少」）。
 *
 * <table>
 *   <tr><th>时机</th><th>数量（density=1）</th><th>粒子</th></tr>
 *   <tr><td>待机</td><td>每 5 tick 3~5 颗</td><td>ENCHANT / END_ROD 各半 + 档位色 DUST</td></tr>
 *   <tr><td>开箱</td><td>60 颗外扩 + 20 颗上升</td><td>END_ROD / FIREWORK / TOTEM</td></tr>
 *   <tr><td>滚动切换</td><td>8 颗</td><td>HAPPY_VILLAGER + CRIT</td></tr>
 *   <tr><td>领取</td><td>25 颗上飘</td><td>TOTEM_OF_UNDYING</td></tr>
 *   <tr><td>高级档额外</td><td>24 颗一圈</td><td>FIREWORK</td></tr>
 * </table>
 *
 * <p>倍率 = {@code tiers[].p}（packages.json，1.0~1.8）× {@link PackageConfig#densityScale()}（0/1/1.6）。
 * 数量全部走 {@link #scaled(double, double)} 统一取整并保证 ≥1，避免「低档 + 高数量」被舍成 0。
 */
public final class PackageParticles {

    /** 待机发射间隔（tick）。 */
    public static final int IDLE_INTERVAL = 5;
    /** 视为「高级档」的倍率阈值：legend 1.6 / mythic 1.8 才有 FIREWORK 一圈。 */
    private static final double HIGH_TIER_P = 1.5;

    private PackageParticles() {
    }

    // ------------------------------------------------------------------ 数量

    /** {@code base × k(档位) × densityScale}，四舍五入且至少 1；关闭时返回 0。 */
    private static int scaled(double base, String tier) {
        double scale = PackageConfig.densityScale();
        if (scale <= 0) return 0;
        double k = PackageCatalog.particleMultiplier(tier);
        return Math.max(1, (int) Math.round(base * k * scale));
    }

    /** 档位色 {@code #RRGGBB} → DUST。 */
    private static ParticleEffect dust(String tier, float scale) {
        int rgb = PackageCatalog.tierColor(tier);
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float g = ((rgb >> 8) & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;
        // 全黑/近黑会让 DUST 几乎不可见（活动档的白 0xFFFFFF 没问题），抬一个下限
        return new DustParticleEffect(new Vector3f(Math.max(r, 0.12f), Math.max(g, 0.12f), Math.max(b, 0.12f)), scale);
    }

    private static boolean highTier(String tier) {
        return PackageCatalog.particleMultiplier(tier) >= HIGH_TIER_P;
    }

    // ------------------------------------------------------------------ 待机

    /** 待机：每 5 tick 3~5 颗（规格表），ENCHANT / END_ROD 各半，余数补档位色 DUST。 */
    public static void idle(ServerWorld world, Vec3d pos, String tier) {
        if (PackageConfig.density() == 0) return;
        Random random = world.getRandom();
        int n = scaled(3 + random.nextInt(3), tier); // 3/4/5
        if (n <= 0) return;
        int enchant = n / 2;
        int endRod = n - enchant;
        for (int i = 0; i < enchant; i++) {
            world.spawnParticles(ParticleTypes.ENCHANT,
                    pos.x + (random.nextDouble() - 0.5) * 0.9,
                    pos.y + 0.25 + random.nextDouble() * 0.55,
                    pos.z + (random.nextDouble() - 0.5) * 0.9,
                    1, 0, 0.06, 0, 0.02);
        }
        for (int i = 0; i < endRod; i++) {
            world.spawnParticles(ParticleTypes.END_ROD,
                    pos.x + (random.nextDouble() - 0.5) * 0.7,
                    pos.y + 0.3 + random.nextDouble() * 0.5,
                    pos.z + (random.nextDouble() - 0.5) * 0.7,
                    1, 0, 0.05, 0, 0.015);
        }
        // 档位色永远至少 1 颗：否则 normal 的米黄与 mythic 的赤红在远处完全分不出
        world.spawnParticles(dust(tier, 1.1f), pos.x, pos.y + 0.55, pos.z, 1, 0.16, 0.14, 0.16, 0.01);
    }

    // ------------------------------------------------------------------ 开箱

    /** 开箱一次性爆发：60 颗外扩 + 20 颗上升，高级档（legend/mythic）额外 24 颗 FIREWORK 一圈。 */
    public static void openBurst(ServerWorld world, Vec3d pos, String tier) {
        if (PackageConfig.density() == 0) return;
        Random random = world.getRandom();
        int outward = scaled(60, tier);
        for (int i = 0; i < outward; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double dy = (random.nextDouble() - 0.35) * 0.9;
            double radius = 0.12 + random.nextDouble() * 0.22;
            // 前三成用档位色 DUST（远看是「这个档位的颜色炸开」），其余按规格用 END_ROD / FIREWORK
            ParticleEffect effect;
            int pick = i % 10;
            if (pick < 3) effect = dust(tier, 1.3f);
            else if (pick < 7) effect = ParticleTypes.END_ROD;
            else effect = ParticleTypes.FIREWORK;
            world.spawnParticles(effect,
                    pos.x + dx * radius, pos.y + 0.4 + dy * radius, pos.z + dz * radius,
                    1, dx * 0.30, dy * 0.30 + 0.03, dz * 0.30, 0.16);
        }
        int rising = scaled(20, tier);
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                pos.x, pos.y + 0.25, pos.z, rising, 0.32, 0.12, 0.32, 0.06);
        if (highTier(tier)) {
            int ring = scaled(24, tier);
            for (int i = 0; i < ring; i++) {
                double angle = (Math.PI * 2 * i) / ring;
                double radius = 0.75;
                world.spawnParticles(ParticleTypes.FIREWORK,
                        pos.x + Math.cos(angle) * radius, pos.y + 0.45, pos.z + Math.sin(angle) * radius,
                        1, 0, 0.02, 0, 0.02);
            }
        }
    }

    // ------------------------------------------------------------------ 领取

    /** 领取：25 颗 TOTEM_OF_UNDYING 上飘（每个包裹实体只放一次，见 PackageEntity）。 */
    public static void claim(ServerWorld world, Vec3d pos, String tier) {
        if (PackageConfig.density() == 0) return;
        int n = scaled(25, tier);
        if (n <= 0) return;
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                pos.x, pos.y + 0.45, pos.z, n, 0.22, 0.20, 0.22, 0.05);
        world.spawnParticles(dust(tier, 1.4f), pos.x, pos.y + 0.5, pos.z, Math.max(1, n / 5), 0.25, 0.2, 0.25, 0.02);
    }

    // ------------------------------------------------------------------ 滚动

    /**
     * 滚动切换候选：8 颗。★客户端调用★（{@code PackageChooseScreen} 悬停格子变化），
     * 所以走 {@link World#addParticle} 而不是 server 的 spawnParticles。
     */
    public static void scroll(World world, Vec3d pos, String tier) {
        if (PackageConfig.density() == 0) return;
        int n = scaled(8, tier);
        if (n <= 0) return;
        Random random = world.getRandom();
        for (int i = 0; i < n; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.55;
            double oy = 0.45 + random.nextDouble() * 0.35;
            double oz = (random.nextDouble() - 0.5) * 0.55;
            ParticleEffect effect;
            switch (i % 4) {
                case 0, 1 -> effect = ParticleTypes.HAPPY_VILLAGER;
                case 2 -> effect = ParticleTypes.CRIT;
                default -> effect = dust(tier, 1.0f);
            }
            world.addParticle(effect,
                    pos.x + ox, pos.y + oy, pos.z + oz,
                    (random.nextDouble() - 0.5) * 0.05, 0.03, (random.nextDouble() - 0.5) * 0.05);
        }
    }
}
