package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.math.Vec3d;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.List;

/** 新调试命令的最小真实宿主回归。 */
public final class DebugToolsGameTest {
    private static final int SPAWN_X = 2;
    private static final int SPAWN_Y = 2;
    private static final int SPAWN_Z = 2;
    private static final int COMMAND_PERMISSION_LEVEL = 4;
    private static final double TEST_QI_AMOUNT = 0.01;
    private static final double RATIO_TOLERANCE = 0.000001;

    @GameTest(templateName = net.fabricmc.fabric.api.gametest.v1.FabricGameTest.EMPTY_STRUCTURE)
    public void fatigueAndQiUseAuthoritativeHumanoidHost(TestContext context) {
        LivingEntity villager = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(villager);
        BodySnapshot initial = DebugBodyAccess.inspect(villager).orElseThrow();
        String tissueId = initial.getStructure().getTissues(0).getId();
        ServerCommandSource source = source(context, villager);

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
        context.complete();
    }

    @GameTest(templateName = net.fabricmc.fabric.api.gametest.v1.FabricGameTest.EMPTY_STRUCTURE)
    public void qiSetUsesHumanDebugAuthorization(TestContext context) {
        DebugAddonGameTest.DebugTestPlayer online = new DebugAddonGameTest.DebugTestPlayer(context);
        context.waitAndRun(2, () -> {
            try (online) {
                ServerCommandSource source = source(context, online.player);
                int result = DebugFabric.setQi(source, List.of(online.player), TEST_QI_AMOUNT);
                BodySnapshot body = DebugBodyAccess.inspect(online.player).orElseThrow();
                double totalQi = body.getMeridianStatesList().stream()
                        .flatMap(state -> state.getLumenQi().getEntriesList().stream())
                        .mapToDouble(entry -> entry.getAmount())
                        .sum();
                context.assertTrue(result == 1 && Math.abs(totalQi - TEST_QI_AMOUNT) < RATIO_TOLERANCE,
                        "玩家 qi set 没有写入精确余额：" + totalQi);
                context.complete();
            }
        });
    }

    private static ServerCommandSource source(TestContext context, LivingEntity entity) {
        return context.getWorld().getServer().getCommandSource()
                .withWorld(context.getWorld())
                .withPosition(Vec3d.ofCenter(entity.getBlockPos()))
                .withLevel(COMMAND_PERMISSION_LEVEL);
    }

    private static void disableAi(LivingEntity entity) {
        if (entity instanceof MobEntity mob) {
            mob.setAiDisabled(true /* aiDisabled */);
        }
    }
}
