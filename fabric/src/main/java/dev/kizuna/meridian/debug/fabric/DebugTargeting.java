package dev.kizuna.meridian.debug.fabric;

import java.util.Comparator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 服务端准星实体选择，限制在当前视线和已加载实体内。 */
final class DebugTargeting {
    private static final double MAX_RAY_DISTANCE = 32.0;
    private static final double RAY_MARGIN = 0.5;

    private DebugTargeting() {
        // 目标选择只依赖当前玩家视线，不保留跨 tick 的实体引用。
    }

    static LivingEntity entity(ServerPlayer player) {
        // 方块命中先截断实体射线，避免透过墙读取远处身体。
        Vec3 start = player.getEyePosition(1.0F);
        Vec3 direction = player.getViewVector(1.0F);
        Vec3 fullEnd = start.add(direction.scale(MAX_RAY_DISTANCE));
        HitResult blockHit = player.pick(MAX_RAY_DISTANCE, 1.0F, false /* includeFluids */);
        Vec3 lineEnd;
        if (blockHit.getType() != HitResult.Type.MISS) {
            lineEnd = blockHit.getLocation();
        } else {
            lineEnd = fullEnd;
        }
        AABB search = player.getBoundingBox().expandTowards(direction.scale(MAX_RAY_DISTANCE)).inflate(RAY_MARGIN);
        return player.level().getEntities(player, search, candidate -> canTarget(candidate, start, lineEnd))
                .stream()
                .filter(candidate -> candidate instanceof LivingEntity)
                .map(candidate -> (LivingEntity) candidate)
                .min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(start)))
                .orElse(null);
    }

    private static boolean canTarget(Entity candidate, Vec3 start, Vec3 end) {
        // 只接受实体碰撞箱确实穿过准星线的活体，避免扫描到视线旁的目标。
        if (!(candidate instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
            return false;
        }
        return living.getBoundingBox().inflate(RAY_MARGIN).clip(start, end).isPresent();
    }
}
