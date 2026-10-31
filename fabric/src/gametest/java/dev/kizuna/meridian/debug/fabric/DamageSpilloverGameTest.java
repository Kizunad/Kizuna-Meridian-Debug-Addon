package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.debug.DamageLogFormatter;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.vanilla.DamagePreferences;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;

/** 完整装配中的部位蔓延、单次事务、护甲边界和实体死亡同步。 */
public final class DamageSpilloverGameTest implements FabricGameTest {
    private static final String ARM = "left_upper_arm";
    private static final int SPAWN_COORDINATE = 2;
    private static final int LOGIN_TICKS = 61;
    private static final int MAX_SEED = 100_000;
    private static final float NEXT_HIT = 10;
    private static final float FATAL_HIT = 40;
    private static final double ARM_MULTIPLIER = 0.7;
    private static final double TOLERANCE = 1e-6;
    private static final List<VanillaDamageFabric.DamageEvent> EVENTS = new ArrayList<>();
    private static boolean observerInstalled;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void vindicatorDestroyedArmSpreadsToEveryLivingPart(GameTestHelper context) {
        // 两次真实原版伤害连续命中同一手臂，脚部护甲不能再次抵扣接收剂量。
        try (var online = new DebugAddonGameTest.DebugTestPlayer(context)) {
            LivingEntity target = vindicator(context);
            verifyRepeatedArmHits(context, target, online.player);
            context.succeed();
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void playerDestroyedArmUsesTheSameTemplateRule(GameTestHelper context) {
        // 真实登录绑定玩家身体，等待登录免伤结束后走相同伤害入口。
        var online = new DebugAddonGameTest.DebugTestPlayer(context);
        context.runAfterDelay(LOGIN_TICKS, () -> {
            try (online) {
                online.player.setInvulnerable(false /* invulnerable */);
                online.player.getAbilities().invulnerable = false;
                verifyRepeatedArmHits(context, online.player, online.player);
                context.succeed();
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void spilloverDestroysHeadAndSynchronizesVanillaDeath(GameTestHelper context) {
        // 先通过可信事务留下少量头部 HP，再由黑臂蔓延触发原有死亡条件。
        LivingEntity target = vindicator(context);
        BodySnapshot initial = DebugBodyAccess.inspect(target).orElseThrow();
        hitArm(context, target, (float) hp(initial, ARM));
        weakenHead(target);
        var event = hitArm(context, target, FATAL_HIT);
        var result = event.result();
        BodySnapshot body = result.body();
        context.assertTrue(hp(body, "head") == 0, "蔓延没有耗尽头部");
        context.assertTrue(body.getVitality().getDead() && target.isDeadOrDying(), "身体与实体死亡未同步");
        context.assertTrue(result.health().getDamageResolution().getLethal(), "回执未保留致死结果");
        context.succeed();
    }

    private static LivingEntity vindicator(GameTestHelper context) {
        // 禁止 AI 与重力引入额外伤害，宿主仍由正式类人接线创建。
        var target = context.spawn(EntityType.VINDICATOR,
                SPAWN_COORDINATE, SPAWN_COORDINATE, SPAWN_COORDINATE);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        return target;
    }

    private static void verifyRepeatedArmHits(GameTestHelper context, LivingEntity target, ServerPlayer viewer) {
        // 首次恰好耗尽，第二次完整传递；接收者上限权重直接与提交前后快照核对。
        target.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
        BodySnapshot initial = DebugBodyAccess.inspect(target).orElseThrow();
        var first = hitArm(context, target, (float) hp(initial, ARM));
        var firstResult = first.result();
        context.assertTrue(hp(firstResult.body(), ARM) == 0, "首击没有耗尽手臂");
        context.assertTrue(!firstResult.health().getDamageResolution().hasSpillover(), "恰好耗尽发生蔓延");
        var second = hitArm(context, target, NEXT_HIT);
        assertWeightedLosses(context, second);
        String log = DamageLogFormatter.format(DebugFabric.damageLogEntry(viewer, second));
        context.assertTrue(log.contains("溢出 10.0 ×0.7") && log.contains("胸") && log.contains("头"),
                "伤害观察日志缺少系数或接收明细：" + log);
    }

    private static void assertWeightedLosses(GameTestHelper context, VanillaDamageFabric.DamageEvent event) {
        // 固定回执记录一次版本递增，全部接收部位在同一事务中扣减。
        var result = event.result();
        BodySnapshot before = result.before();
        BodySnapshot after = result.body();
        var spillover = result.health().getDamageResolution().getSpillover();
        context.assertTrue(after.getRevision() == before.getRevision() + 1, "伤害拆成了多次身体提交");
        context.assertTrue(Math.abs(spillover.getOverflowHp() - NEXT_HIT) < TOLERANCE, "黑臂没有传递完整剂量");
        double weight = before.getStructure().getPartsList().stream()
                .filter(part -> !part.getId().equals(ARM)).mapToDouble(part -> part.getMaxHp()).sum();
        for (var part : before.getStructure().getPartsList()) {
            if (part.getId().equals(ARM)) {
                continue;
            }
            double expected = NEXT_HIT * ARM_MULTIPLIER * part.getMaxHp() / weight;
            double actual = hp(before, part.getId()) - hp(after, part.getId());
            context.assertTrue(Math.abs(actual - expected) < TOLERANCE, "接收权重不符：" + part.getId());
        }
    }

    private static VanillaDamageFabric.DamageEvent hitArm(GameTestHelper context, LivingEntity target, float amount) {
        // 只控制服务端随机种子，命中选择、护甲、事务和观察回调都走真实入口。
        installObserver();
        BodySnapshot body = DebugBodyAccess.inspect(target).orElseThrow();
        target.invulnerableTime = 0;
        target.getRandom().setSeed(armSeed(body));
        int previous = EVENTS.size();
        context.assertTrue(target.hurt(target.damageSources().generic(), amount), "实体拒绝伤害");
        List<VanillaDamageFabric.DamageEvent> events = EVENTS.subList(previous, EVENTS.size()).stream()
                .filter(event -> event.target() == target).toList();
        context.assertTrue(events.size() == 1, "一次命中没有产生唯一已提交事件");
        var event = events.get(0);
        context.assertTrue(event.input().hit().orElseThrow().getPartId().equals(ARM), "随机种子未固定命中手臂");
        return event;
    }

    private static long armSeed(BodySnapshot body) {
        // 使用当前测试服配置寻找确定性种子，不修改运行配置或生产选点逻辑。
        var file = FabricLoader.getInstance().getConfigDir().resolve("meridian-vanilla-damage.toml");
        DamagePreferences preferences = DamagePreferences.load(file);
        for (long seed = 0; seed < MAX_SEED; seed++) {
            double roll = RandomSource.create(seed).nextDouble();
            var selected = preferences.select(body, "minecraft:unknown", roll).orElseThrow();
            if (selected.getPartId().equals(ARM)) {
                return seed;
            }
        }
        throw new IllegalStateException("没有找到手臂命中种子");
    }

    private static void weakenHead(LivingEntity target) {
        // 精确损伤只用于准备临界状态，仍经过宿主版本保护的健康命令。
        BodySnapshot body = DebugBodyAccess.inspect(target).orElseThrow();
        HealthTarget head = HealthTarget.newBuilder().setPartId("head").build();
        DamageBody damage = DamageBody.newBuilder()
                .setSourceId("test:spillover")
                .setDamageType("test:head")
                .setKind(DamageKind.CUSTOM)
                .setHit(head)
                .setDamageHp(hp(body, "head") - 1)
                .build();
        BodyCommand command = BodyCommand.newBuilder().setDamageBody(damage).build();
        DebugBodyAccess.submit(target, body, command).orElseThrow();
    }

    private static double hp(BodySnapshot body, String id) {
        // 断言读取实际部位状态，不从原版总血量反推。
        return body.getPartStatesList().stream().filter(part -> part.getId().equals(id))
                .mapToDouble(part -> part.getHp()).findFirst().orElseThrow();
    }

    private static void installObserver() {
        // 同一测试服务进程只注册一次，事件按目标实体隔离。
        if (!observerInstalled) {
            VanillaDamageFabric.registerDamageObserver(EVENTS::add);
            observerInstalled = true;
        }
    }
}
