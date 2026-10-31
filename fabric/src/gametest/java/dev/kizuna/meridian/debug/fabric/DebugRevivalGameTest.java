package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.human.server.HumanRevival;
import dev.kizuna.meridian.protocol.pb.HealthMessages.ConfirmBodyDeath;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import net.minecraft.commands.CommandSourceStack;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import static dev.kizuna.meridian.debug.fabric.DebugGameTestTicks.DEFAULT_TIMEOUT;

/** 调试复活经过真实命令树、原版补全和唯一身体健康事务。 */
public final class DebugRevivalGameTest {
    private static final PermissionSet OP_PERMISSIONS = LevelBasedPermissionSet.OWNER;
    private static final int SPAWN_HEIGHT = 2;
    private static final int DEATH_OBSERVATION_TICKS = 3;
    private static final float LETHAL_DAMAGE = 1000000;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void reviveCommandSupportsSelfTargetsCompletionAndRejectsLivingBody(GameTestHelper context) {
        // 自身和显式选择器共用一年费用；活体重复调用不能变成免费治疗或重复扣费。
        try (var online = new DebugAddonGameTest.DebugTestPlayer(context)) {
            var player = online.player;
            var source = player.createCommandSourceStack().withPermission(OP_PERMISSIONS);
            double cost = HumanRevival.request("debug_test:revival").getLifespanSeconds();
            double spent = DebugBodyAccess.inspect(player).orElseThrow().getVitality().getSpentLifespanSeconds();
            confirmDeath(player);
            context.assertTrue(command(source, "meridian body revive") == 1, "自身复活命令失败");
            assertSpent(context, player, spent + cost);
            context.assertTrue(command(source, "meridian body revive @s") == 0, "活体被重复复活");
            assertSpent(context, player, spent + cost);
            confirmDeath(player);
            context.assertTrue(command(source, "meridian body revive @s") == 1, "目标选择器复活失败");
            assertSpent(context, player, spent + cost + cost);
            verifyCommandTree(context, source);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void deadVindicatorStaysDeadWithoutAutomaticLifespanCharge(GameTestHelper context) {
        // 类人实体只走死亡清账，玩家重生回调不能替它自动复活。
        var vindicator = context.spawn(EntityTypes.VINDICATOR, 1, SPAWN_HEIGHT, 1);
        ((Mob) vindicator).setNoAi(true /* aiDisabled */);
        var before = DebugBodyAccess.inspect(vindicator).orElseThrow();
        vindicator.hurtServer(context.getLevel(), vindicator.damageSources().genericKill(), LETHAL_DAMAGE);
        context.runAfterDelay(DEATH_OBSERVATION_TICKS, () -> {
            var after = DebugBodyAccess.inspect(vindicator).orElseThrow();
            context.assertTrue(after.getVitality().getDead() && !vindicator.isAlive(), "卫道士死亡后自动复活");
            context.assertTrue(after.getVitality().getSpentLifespanSeconds()
                    == before.getVitality().getSpentLifespanSeconds(), "卫道士死亡被扣复活寿元");
            context.succeed();
        });
    }

    private static void confirmDeath(LivingEntity entity) {
        // 形成合法死态而保留实体用于 Brigadier 自身选择；死亡账仍由 Framework 提交。
        var death = ConfirmBodyDeath.newBuilder().setSourceId("debug_test:death").build();
        var command = BodyCommand.newBuilder().setConfirmBodyDeath(death).build();
        var body = DebugBodyAccess.inspect(entity).orElseThrow();
        DebugBodyAccess.submit(entity, body, command).orElseThrow();
    }

    private static void assertSpent(GameTestHelper context, LivingEntity entity, double expected) {
        // 费用和存活状态读取真实宿主，不从命令反馈文字推断成功。
        var body = DebugBodyAccess.inspect(entity).orElseThrow();
        context.assertTrue(!body.getVitality().getDead(), "调试复活没有清除死态");
        context.assertTrue(body.getVitality().getSpentLifespanSeconds() == expected, "调试复活寿元扣减不一致");
    }

    private static void verifyCommandTree(GameTestHelper context, CommandSourceStack source) {
        // 检查 body 分支下 revive 节点自带的权限和 literal 补全，再请求原版实体参数补全。
        var dispatcher = source.getServer().getCommands().getDispatcher();
        var revive = dispatcher.getRoot().getChild("meridian").getChild("body").getChild("revive");
        context.assertTrue(!revive.canUse(source.withPermission(PermissionSet.NO_PERMISSIONS)), "普通玩家可调用调试复活");
        String input = "meridian body re";
        var literals = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join();
        context.assertTrue(literals.getList().stream().anyMatch(item -> item.getText().equals("revive")),
                "revive 没有命令补全");
        String targets = "meridian body revive @";
        var selectors = dispatcher.getCompletionSuggestions(dispatcher.parse(targets, source)).join();
        context.assertTrue(selectors.getList().stream().anyMatch(item -> item.getText().equals("@s")),
                "revive 没有实体选择器补全");
    }

    private static int command(CommandSourceStack source, String text) {
        // 与聊天命令相同的执行入口，返回成功目标数量。
        return DebugTestCommands.run(source, text);
    }
}
