package dev.kizuna.meridian.debug.fabric;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;

/** 服务端准星实体选择，限制在当前视线和已加载实体内。 */
final class DebugTargeting {
    private static final double MAX_RAY_DISTANCE = 32.0;
    private static final double RAY_MARGIN = 0.5;

    private DebugTargeting() {
        // 目标选择只依赖当前玩家视线，不保留跨 tick 的实体引用。
    }

    static LivingEntity entity(ServerPlayerEntity player) {
        // 方块命中先截断实体射线，避免透过墙读取远处身体。
        Vec3d start = player.getCameraPosVec(1.0F);
        Vec3d direction = player.getRotationVec(1.0F);
        Vec3d fullEnd = start.add(direction.multiply(MAX_RAY_DISTANCE));
        HitResult blockHit = player.raycast(MAX_RAY_DISTANCE, 1.0F, false /* includeFluids */);
        Vec3d lineEnd;
        if (blockHit.getType() != HitResult.Type.MISS) {
            lineEnd = blockHit.getPos();
        } else {
            lineEnd = fullEnd;
        }
        Box search = player.getBoundingBox().stretch(direction.multiply(MAX_RAY_DISTANCE)).expand(RAY_MARGIN);
        return player.getWorld().getOtherEntities(player, search, candidate -> canTarget(candidate, start, lineEnd))
                .stream()
                .filter(candidate -> candidate instanceof LivingEntity)
                .map(candidate -> (LivingEntity) candidate)
                .min(Comparator.comparingDouble(candidate -> candidate.squaredDistanceTo(start)))
                .orElse(null);
    }

    private static boolean canTarget(Entity candidate, Vec3d start, Vec3d end) {
        // 只接受实体碰撞箱确实穿过准星线的活体，避免扫描到视线旁的目标。
        if (!(candidate instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
            return false;
        }
        return living.getBoundingBox().expand(RAY_MARGIN).raycast(start, end).isPresent();
    }
}
