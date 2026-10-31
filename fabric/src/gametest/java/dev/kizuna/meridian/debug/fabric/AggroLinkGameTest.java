package dev.kizuna.meridian.debug.fabric;

import net.minecraft.commands.CommandSourceStack;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.List;

import static dev.kizuna.meridian.debug.fabric.DebugGameTestTicks.DEFAULT_TIMEOUT;

/** 仇恨牵引器的目标覆盖、攻击和清理回归。 */
public final class AggroLinkGameTest {
    private static final int SPAWN_X = 2;
    private static final int SPAWN_Y = 2;
    private static final int FIRST_Z = 2;
    private static final int SECOND_Z = 4;
    private static final int THIRD_Z = 6;
    private static final int FAR_Z = 40;
    private static final int TARGET_WAIT_TICKS = 5;
    private static final int ATTACK_WAIT_TICKS = 80;
    private static final PermissionSet COMMAND_PERMISSIONS = LevelBasedPermissionSet.OWNER;
    private static final float HEALTH_EPSILON = 0.001F;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void zombiesKeepMutualTargetsAndAttack(GameTestHelper context) {
        // 同类僵尸通常不会互选，覆盖必须让它们都保留目标并实际造成伤害。
        Mob first = spawn(context, EntityTypes.ZOMBIE, FIRST_Z);
        Mob second = spawn(context, EntityTypes.ZOMBIE, SECOND_Z);
        float firstHealth = first.getHealth();
        float secondHealth = second.getHealth();
        setAggro(context, first, second, AggroLinkMode.MUTUAL);
        context.runAfterDelay(ATTACK_WAIT_TICKS, () -> {
            context.assertTrue(first.getTarget() == second, "第一只僵尸没有保持第二只为目标");
            context.assertTrue(second.getTarget() == first, "第二只僵尸没有保持第一只为目标");
            boolean firstDamaged = first.getHealth() < firstHealth - HEALTH_EPSILON;
            boolean secondDamaged = second.getHealth() < secondHealth - HEALTH_EPSILON;
            context.assertTrue(firstDamaged || secondDamaged, "两只僵尸没有实际互相攻击");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void vindicatorAndIronGolemCanBeLinked(GameTestHelper context) {
        // 不同 Mob 子类共享同一公开目标接口，卫道士和铁傀儡都应可设置。
        Mob vindicator = spawn(context, EntityTypes.VINDICATOR, FIRST_Z);
        Mob golem = spawn(context, EntityTypes.IRON_GOLEM, SECOND_Z);
        setAggro(context, vindicator, golem, AggroLinkMode.MUTUAL);
        context.runAfterDelay(TARGET_WAIT_TICKS, () -> {
            context.assertTrue(vindicator.getTarget() == golem, "卫道士没有锁定铁傀儡");
            context.assertTrue(golem.getTarget() == vindicator, "铁傀儡没有锁定卫道士");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void onewayAndClearRemoveOppositeTarget(GameTestHelper context) {
        // 单向模式只写入 A，clear 后双方都回到原版 AI 的无覆盖状态。
        Mob first = spawn(context, EntityTypes.ZOMBIE, FIRST_Z);
        Mob second = spawn(context, EntityTypes.SKELETON, SECOND_Z);
        CommandSourceStack source = commandSource(context, first);
        int linked = DebugFabric.setAggro(source, first, second, AggroLinkMode.ONEWAY);
        context.assertTrue(linked == 1, "单向仇恨命令未成功");
        context.assertTrue(first.getTarget() == second, "单向模式没有设置 A 的目标");
        context.assertTrue(second.getTarget() != first, "单向模式错误设置了 B 的目标");
        int cleared = DebugFabric.clearAggro(source, List.of(first, second));
        context.assertTrue(cleared == 1, "clear 没有移除单向覆盖");
        context.assertTrue(first.getTarget() == null, "clear 后 A 仍保留调试目标");
        context.assertTrue(second.getTarget() == null, "clear 后 B 仍保留调试目标");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void deadTargetClearsOppositeCoverage(GameTestHelper context) {
        // 一方死亡后，另一方的内存覆盖必须在下一次服务端 tick 清除。
        Mob first = spawn(context, EntityTypes.ZOMBIE, FIRST_Z);
        Mob second = spawn(context, EntityTypes.ZOMBIE, SECOND_Z);
        setAggro(context, first, second, AggroLinkMode.MUTUAL);
        first.setNoAi(true /* aiDisabled */);
        second.kill(context.getLevel());
        context.runAfterDelay(TARGET_WAIT_TICKS, () -> {
            context.assertTrue(first.getTarget() == null, "目标死亡后另一方仍保留覆盖");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void replacementAndDistanceClearCoverage(GameTestHelper context) {
        // 新设置会移除旧关联，目标离开 32 格后剩余覆盖也会被清掉。
        Mob first = spawn(context, EntityTypes.ZOMBIE, FIRST_Z);
        Mob oldTarget = spawn(context, EntityTypes.SKELETON, SECOND_Z);
        Mob newTarget = spawn(context, EntityTypes.VINDICATOR, THIRD_Z);
        first.setNoAi(true /* aiDisabled */);
        setAggro(context, first, oldTarget, AggroLinkMode.MUTUAL);
        setAggro(context, first, newTarget, AggroLinkMode.ONEWAY);
        context.assertTrue(first.getTarget() == newTarget, "再次设置没有替换旧目标");
        context.assertTrue(oldTarget.getTarget() == null, "再次设置没有清除旧关联");
        newTarget.snapTo(SPAWN_X, SPAWN_Y, FAR_Z, 0, 0);
        context.runAfterDelay(TARGET_WAIT_TICKS, () -> {
            context.assertTrue(first.getTarget() == null, "目标超过 32 格后覆盖未清除");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void passiveMobsAndPlayersAreRejected(GameTestHelper context) {
        // 猪没有攻击伤害属性，玩家也不属于可牵引目标，二者都不能进入选择状态。
        Mob pig = spawn(context, EntityTypes.PIG, FIRST_Z);
        context.assertTrue(!AggroLinkRegistry.canAttack(pig), "猪被误判为可攻击生物");
        try (DebugAddonGameTest.DebugTestPlayer online = new DebugAddonGameTest.DebugTestPlayer(context)) {
            ItemStack stack = new ItemStack(DebugItems.AGGRO_LINK);
            DebugItems.AGGRO_LINK.interactLivingEntity(stack, online.player, pig, InteractionHand.MAIN_HAND);
            context.assertTrue(!DebugFabric.hasAggroSelection(online.player), "被动生物进入了选择状态");
            DebugItems.AGGRO_LINK.interactLivingEntity(stack, online.player, online.player, InteractionHand.MAIN_HAND);
            context.assertTrue(!DebugFabric.hasAggroSelection(online.player), "玩家进入了选择状态");
            context.succeed();
        }
    }

    private static Mob spawn(GameTestHelper context, EntityType<? extends Mob> type, int z) {
        Mob mob = (Mob) context.spawn(type, SPAWN_X, SPAWN_Y, z);
        mob.setPersistenceRequired();
        return mob;
    }

    private static void setAggro(GameTestHelper context, Mob first, Mob second,
                                 AggroLinkMode mode) {
        int result = DebugFabric.setAggro(commandSource(context, first), first, second, mode);
        context.assertTrue(result == 1, "仇恨设置失败");
    }

    private static CommandSourceStack commandSource(GameTestHelper context, LivingEntity anchor) {
        // 服务器管理员级命令源，位置取锚点生物所在方块中心。
        return context.getLevel().getServer().createCommandSourceStack()
                .withLevel(context.getLevel())
                .withPosition(Vec3.atCenterOf(anchor.blockPosition()))
                .withPermission(COMMAND_PERMISSIONS);
    }
}
