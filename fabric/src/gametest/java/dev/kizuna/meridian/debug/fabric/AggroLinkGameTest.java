package dev.kizuna.meridian.debug.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** 仇恨牵引器的目标覆盖、攻击和清理回归。 */
public final class AggroLinkGameTest implements FabricGameTest {
    private static final int SPAWN_X = 2;
    private static final int SPAWN_Y = 2;
    private static final int FIRST_Z = 2;
    private static final int SECOND_Z = 4;
    private static final int THIRD_Z = 6;
    private static final int FAR_Z = 40;
    private static final int TARGET_WAIT_TICKS = 5;
    private static final int ATTACK_WAIT_TICKS = 80;
    private static final int COMMAND_PERMISSION_LEVEL = 4;
    private static final float HEALTH_EPSILON = 0.001F;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void zombiesKeepMutualTargetsAndAttack(TestContext context) {
        // 同类僵尸通常不会互选，覆盖必须让它们都保留目标并实际造成伤害。
        MobEntity first = spawn(context, EntityType.ZOMBIE, FIRST_Z);
        MobEntity second = spawn(context, EntityType.ZOMBIE, SECOND_Z);
        float firstHealth = first.getHealth();
        float secondHealth = second.getHealth();
        setAggro(context, first, second, AggroLinkMode.MUTUAL);
        context.waitAndRun(ATTACK_WAIT_TICKS, () -> {
            context.assertTrue(first.getTarget() == second, "第一只僵尸没有保持第二只为目标");
            context.assertTrue(second.getTarget() == first, "第二只僵尸没有保持第一只为目标");
            boolean firstDamaged = first.getHealth() < firstHealth - HEALTH_EPSILON;
            boolean secondDamaged = second.getHealth() < secondHealth - HEALTH_EPSILON;
            context.assertTrue(firstDamaged || secondDamaged, "两只僵尸没有实际互相攻击");
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void vindicatorAndIronGolemCanBeLinked(TestContext context) {
        // 不同 MobEntity 子类共享同一公开目标接口，卫道士和铁傀儡都应可设置。
        MobEntity vindicator = spawn(context, EntityType.VINDICATOR, FIRST_Z);
        MobEntity golem = spawn(context, EntityType.IRON_GOLEM, SECOND_Z);
        setAggro(context, vindicator, golem, AggroLinkMode.MUTUAL);
        context.waitAndRun(TARGET_WAIT_TICKS, () -> {
            context.assertTrue(vindicator.getTarget() == golem, "卫道士没有锁定铁傀儡");
            context.assertTrue(golem.getTarget() == vindicator, "铁傀儡没有锁定卫道士");
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void onewayAndClearRemoveOppositeTarget(TestContext context) {
        // 单向模式只写入 A，clear 后双方都回到原版 AI 的无覆盖状态。
        MobEntity first = spawn(context, EntityType.ZOMBIE, FIRST_Z);
        MobEntity second = spawn(context, EntityType.SKELETON, SECOND_Z);
        ServerCommandSource source = commandSource(context, first);
        int linked = DebugFabric.setAggro(source, first, second, AggroLinkMode.ONEWAY);
        context.assertTrue(linked == 1, "单向仇恨命令未成功");
        context.assertTrue(first.getTarget() == second, "单向模式没有设置 A 的目标");
        context.assertTrue(second.getTarget() != first, "单向模式错误设置了 B 的目标");
        int cleared = DebugFabric.clearAggro(source, List.of(first, second));
        context.assertTrue(cleared == 1, "clear 没有移除单向覆盖");
        context.assertTrue(first.getTarget() == null, "clear 后 A 仍保留调试目标");
        context.assertTrue(second.getTarget() == null, "clear 后 B 仍保留调试目标");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void deadTargetClearsOppositeCoverage(TestContext context) {
        // 一方死亡后，另一方的内存覆盖必须在下一次服务端 tick 清除。
        MobEntity first = spawn(context, EntityType.ZOMBIE, FIRST_Z);
        MobEntity second = spawn(context, EntityType.ZOMBIE, SECOND_Z);
        setAggro(context, first, second, AggroLinkMode.MUTUAL);
        first.setAiDisabled(true /* aiDisabled */);
        second.kill();
        context.waitAndRun(TARGET_WAIT_TICKS, () -> {
            context.assertTrue(first.getTarget() == null, "目标死亡后另一方仍保留覆盖");
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void replacementAndDistanceClearCoverage(TestContext context) {
        // 新设置会移除旧关联，目标离开 32 格后剩余覆盖也会被清掉。
        MobEntity first = spawn(context, EntityType.ZOMBIE, FIRST_Z);
        MobEntity oldTarget = spawn(context, EntityType.SKELETON, SECOND_Z);
        MobEntity newTarget = spawn(context, EntityType.VINDICATOR, THIRD_Z);
        first.setAiDisabled(true /* aiDisabled */);
        setAggro(context, first, oldTarget, AggroLinkMode.MUTUAL);
        setAggro(context, first, newTarget, AggroLinkMode.ONEWAY);
        context.assertTrue(first.getTarget() == newTarget, "再次设置没有替换旧目标");
        context.assertTrue(oldTarget.getTarget() == null, "再次设置没有清除旧关联");
        newTarget.refreshPositionAndAngles(SPAWN_X, SPAWN_Y, FAR_Z, 0, 0);
        context.waitAndRun(TARGET_WAIT_TICKS, () -> {
            context.assertTrue(first.getTarget() == null, "目标超过 32 格后覆盖未清除");
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void passiveMobsAndPlayersAreRejected(TestContext context) {
        // 猪没有攻击伤害属性，玩家也不属于可牵引目标，二者都不能进入选择状态。
        MobEntity pig = spawn(context, EntityType.PIG, FIRST_Z);
        context.assertTrue(!AggroLinkRegistry.canAttack(pig), "猪被误判为可攻击生物");
        try (DebugAddonGameTest.DebugTestPlayer online = new DebugAddonGameTest.DebugTestPlayer(context)) {
            ItemStack stack = new ItemStack(DebugItems.AGGRO_LINK);
            DebugItems.AGGRO_LINK.useOnEntity(stack, online.player, pig, Hand.MAIN_HAND);
            context.assertTrue(!DebugFabric.hasAggroSelection(online.player), "被动生物进入了选择状态");
            DebugItems.AGGRO_LINK.useOnEntity(stack, online.player, online.player, Hand.MAIN_HAND);
            context.assertTrue(!DebugFabric.hasAggroSelection(online.player), "玩家进入了选择状态");
            context.complete();
        }
    }

    private static MobEntity spawn(TestContext context, EntityType<? extends MobEntity> type, int z) {
        MobEntity mob = (MobEntity) context.spawnEntity(type, SPAWN_X, SPAWN_Y, z);
        mob.setPersistent();
        return mob;
    }

    private static void setAggro(TestContext context, MobEntity first, MobEntity second,
                                 AggroLinkMode mode) {
        int result = DebugFabric.setAggro(commandSource(context, first), first, second, mode);
        context.assertTrue(result == 1, "仇恨设置失败");
    }

    private static ServerCommandSource commandSource(TestContext context, LivingEntity anchor) {
        return context.getWorld().getServer().getCommandSource()
                .withWorld(context.getWorld())
                .withPosition(Vec3d.ofCenter(anchor.getBlockPos()))
                .withLevel(COMMAND_PERMISSION_LEVEL);
    }
}
