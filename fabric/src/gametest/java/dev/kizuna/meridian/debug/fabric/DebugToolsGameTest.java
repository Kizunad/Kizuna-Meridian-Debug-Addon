package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.debug.fabric.DebugGameTestTicks.DEFAULT_TIMEOUT;

/** 新调试命令的最小真实宿主回归。 */
public final class DebugToolsGameTest {
    private static final int SPAWN_X = 2;
    private static final int SPAWN_Y = 2;
    private static final int SPAWN_Z = 2;
    private static final PermissionSet COMMAND_PERMISSIONS = LevelBasedPermissionSet.OWNER;
    private static final double TEST_QI_AMOUNT = 0.01;
    private static final double RATIO_TOLERANCE = 0.000001;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void fatigueAndQiUseAuthoritativeHumanoidHost(GameTestHelper context) {
        LivingEntity villager = context.spawn(EntityTypes.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(villager);
        BodySnapshot initial = DebugBodyAccess.inspect(villager).orElseThrow();
        String tissueId = initial.getStructure().getTissues(0).getId();
        CommandSourceStack source = source(context, villager);

        int set = DebugFabric.setFatigue(source, List.of(villager), tissueId, 0.5);
        BodySnapshot fatigued = DebugBodyAccess.inspect(villager).orElseThrow();
        double capacity = initial.getStructure().getTissues(0).getFatigueCapacity();
        double actual = fatigued.getTissueStatesList().get(0).getFatigue();
        context.assertTrue(set == 1 && Math.abs(actual / capacity - 0.5) < RATIO_TOLERANCE,
                "疲劳比例没有按 Framework 事务写入：" + actual + "/" + capacity);

        int cleared = DebugFabric.setFatigue(source, List.of(villager), tissueId, 0);
        BodySnapshot clearedBody = DebugBodyAccess.inspect(villager).orElseThrow();
        context.assertTrue(cleared == 1 && clearedBody.getTissueStates(0).getFatigue() == 0,
                "fatigue clear 没有清零");

        int qi = DebugFabric.setQi(source, List.of(villager), TEST_QI_AMOUNT);
        BodySnapshot qiBody = DebugBodyAccess.inspect(villager).orElseThrow();
        double totalQi = qiBody.getMeridianStatesList().stream()
                .flatMap(state -> state.getLumenQi().getEntriesList().stream())
                .mapToDouble(entry -> entry.getAmount())
                .sum();
        context.assertTrue(qi == 1 && Math.abs(totalQi - TEST_QI_AMOUNT) < RATIO_TOLERANCE,
                "qi set 没有写入精确余额：" + totalQi);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void qiSetUsesHumanDebugAuthorization(GameTestHelper context) {
        DebugAddonGameTest.DebugTestPlayer online = new DebugAddonGameTest.DebugTestPlayer(context);
        context.runAfterDelay(2, () -> {
            try (online) {
                CommandSourceStack source = source(context, online.player);
                int result = DebugFabric.setQi(source, List.of(online.player), TEST_QI_AMOUNT);
                BodySnapshot body = DebugBodyAccess.inspect(online.player).orElseThrow();
                double totalQi = body.getMeridianStatesList().stream()
                        .flatMap(state -> state.getLumenQi().getEntriesList().stream())
                        .mapToDouble(entry -> entry.getAmount())
                        .sum();
                context.assertTrue(result == 1 && Math.abs(totalQi - TEST_QI_AMOUNT) < RATIO_TOLERANCE,
                        "玩家 qi set 没有写入精确余额：" + totalQi);
                context.succeed();
            }
        });
    }

    private static CommandSourceStack source(GameTestHelper context, LivingEntity entity) {
        // 服务器管理员级命令源，位置取目标实体所在方块中心。
        return context.getLevel().getServer().createCommandSourceStack()
                .withLevel(context.getLevel())
                .withPosition(Vec3.atCenterOf(entity.blockPosition()))
                .withPermission(COMMAND_PERMISSIONS);
    }

    private static void disableAi(LivingEntity entity) {
        if (entity instanceof Mob mob) {
            mob.setNoAi(true /* aiDisabled */);
        }
    }
}
