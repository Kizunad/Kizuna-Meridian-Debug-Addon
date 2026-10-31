package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.human.fabric.HumanDebugHost;
import dev.kizuna.meridian.humanoid.fabric.HumanoidHealthHost;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import java.util.OptionalDouble;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** 真元调试请求交给目标所属宿主的公开调试入口：玩家归 Human，类人归 Threatening。 */
final class DebugQiBridge {
    private DebugQiBridge() {
        // 身体、版本和存档都由宿主持有，这里只组装一份调试请求。
    }

    static FailureCode set(LivingEntity entity, double amount) {
        // 覆盖为游离态真元，总量由 Framework 按各经脉可用体积分配。
        DebugQi request = DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setAmount(amount)
                .setDensity(QiDensity.FREE)
                .build();
        return submit(entity, request);
    }

    static FailureCode set(LivingEntity entity, double amount, String meridianId, DebugCommands.QiAccount account,
                           double density) {
        // 带参数的覆盖：经脉 ID 为空表示全身，账户选内腔或外壁，数量与密度原样交给 Framework 校验。
        DebugQi request = DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setMeridianId(meridianId)
                .setWall(account == DebugCommands.QiAccount.WALL)
                .setAmount(amount)
                .setDensity(density)
                .build();
        return submit(entity, request);
    }

    static FailureCode fill(LivingEntity entity) {
        // 省略经脉与数量即补满全部内腔。
        DebugQi request = DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .build();
        return submit(entity, request);
    }

    static FailureCode fill(LivingEntity entity, String meridianId, OptionalDouble amount) {
        // 经脉 ID 为空时补满全身内腔；指定经脉时只补该脉，带数量时补到该脉的这一总量。
        DebugQi.Builder request = DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .setMeridianId(meridianId);
        if (amount.isPresent()) {
            request.setAmount(amount.getAsDouble());
        }
        return submit(entity, request.build());
    }

    private static FailureCode submit(LivingEntity entity, DebugQi request) {
        // 权限已在命令层检查；未被认领的实体由类人宿主按无身体拒绝。
        if (entity instanceof ServerPlayer player) {
            return HumanDebugHost.debugQi(player, request);
        }
        return HumanoidHealthHost.debugQi(entity, request);
    }
}
