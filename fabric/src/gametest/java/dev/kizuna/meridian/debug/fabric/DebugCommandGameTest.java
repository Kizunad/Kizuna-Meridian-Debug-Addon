package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueDefinition;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 调试命令走真实命令树和宿主：功法遗忘、冷却、状态、跃步观察、身体明细、疲劳、真元与补全。 */
public final class DebugCommandGameTest implements FabricGameTest {
    private static final int OP_LEVEL = 4;
    private static final int JOIN_TICKS = 2;
    private static final int SETTLE_MARGIN_TICKS = 5;
    private static final long MILLIS_PER_TICK = 50;
    private static final int PLAYER_X = 1;
    private static final int PLAYER_Y = 2;
    private static final int PLAYER_Z = 1;
    private static final int SPAWN_X = 2;
    private static final int SPAWN_Y = 2;
    private static final int SPAWN_Z = 2;
    private static final int VINDICATOR_SAMPLES = 4;
    private static final double QI_SHARE = 0.5;
    private static final double SET_DENSITY = 0.05;
    private static final double FATIGUE_RATIO = 0.25;
    private static final double TOLERANCE = 0.000001;
    private static final String VOLUME = "meridian_basic_techniques:light_step_volume";
    private static final String TECHNIQUE = "meridian_basic_techniques:light_step";
    private static final int TECHNIQUE_VERSION = 1;
    private static final String LEG_MUSCLE = "human:leg_muscle";
    private static final String QUOTED_LEG_MUSCLE = "\"" + LEG_MUSCLE + "\"";
    private static final String LEAP_PREFIX = "跃步";
    private static final String LEAP_LINE = "跃步 LEAP";
    private static final String EFFORT_MARK = "做功=";

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void techniqueCommandsDriveRealCastsLeapsAndForgetting(GameTestHelper context) {
        // 学会后可施放；冷却中被拒且状态给出阶段与失败码；清冷却后立即可再施放；一次跃步一行日志；忘记后按未学拒绝。
        var online = new DebugAddonGameTest.DebugTestPlayer(context);
        context.runAfterDelay(JOIN_TICKS, () -> guarded(online, () -> learnAndCast(context, online)));
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void bodyAndFatigueCommandsMatchTheHostBody(GameTestHelper context) {
        // 摘要给出宿主快照里的境界与真元；按带冒号的标签设置疲劳后每块腿肌恰为该比例，清除后归零。
        var online = new DebugAddonGameTest.DebugTestPlayer(context);
        context.runAfterDelay(JOIN_TICKS, () -> {
            try (online) {
                var source = source(online);
                assertBody(context, online, source);
                assertCommand(context, source, "meridian debug fatigue set @s " + QUOTED_LEG_MUSCLE + " "
                        + FATIGUE_RATIO);
                assertLegFatigue(context, online.player, FATIGUE_RATIO);
                assertCommand(context, source, "meridian debug fatigue clear @s " + QUOTED_LEG_MUSCLE);
                assertLegFatigue(context, online.player, 0);
            }
            context.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void eachDebugCommandCompletesItsNextArgument(GameTestHelper context) {
        // 每条调试指令至少一处补全：疲劳标签带引号、功法 ID 带命名空间、部位与经脉取自实际身体、字面量与实体选择器。
        var online = new DebugAddonGameTest.DebugTestPlayer(context);
        context.runAfterDelay(JOIN_TICKS, () -> {
            try (online) {
                var source = source(online);
                BodySnapshot body = DebugBodyAccess.inspect(online.player).orElseThrow();
                String part = body.getStructure().getParts(0).getId();
                String meridian = body.getStructure().getMeridians(0).getId();
                assertSuggests(context, source, "meridian debug fatigue set @s ", QUOTED_LEG_MUSCLE);
                assertSuggests(context, source, "meridian debug fatigue clear ", QUOTED_LEG_MUSCLE);
                assertSuggests(context, source, "meridian technique forget @s ", TECHNIQUE);
                assertSuggests(context, source, "meridian technique cooldown ", "clear");
                assertSuggests(context, source, "meridian technique status @", "@s");
                assertSuggests(context, source, "meridian watch ", "damage");
                assertSuggests(context, source, "meridian watch leap ", "on");
                assertSuggests(context, source, "meridian body status @s ", "full");
                assertSuggests(context, source, "meridian body heal ", "stamina");
                assertSuggests(context, source, "meridian body heal @s limb ", part);
                assertSuggests(context, source, "meridian qi fill ", meridian);
                assertSuggests(context, source, "meridian qi set @", "@s");
                assertSuggests(context, source, "meridian qi set @s 1 ", "all");
                assertSuggests(context, source, "meridian humanoid aggro ", "clear");
            }
            context.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void qiFillAndSetTakeMeridianAccountAndDensity(GameTestHelper context) {
        // 由 Human 移交的带参形式：按经脉补到指定量或补满，按经脉或全身覆盖内腔、外壁的数量与密度。
        var online = new DebugAddonGameTest.DebugTestPlayer(context);
        context.runAfterDelay(JOIN_TICKS, () -> {
            try (online) {
                assertQiParameters(context, online.player, source(online));
            }
            context.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void qiCommandsAcceptFreshTrueQiVindicators(GameTestHelper context) {
        // 命令生成的真炁卫道士带着生成器残余与高密度主元；qi set 覆盖到半满、qi fill 补满都不越过内腔。
        var source = context.getLevel().getServer().createCommandSourceStack()
                .withLevel(context.getLevel())
                .withPermission(OP_LEVEL);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(SPAWN_X, SPAWN_Y, SPAWN_Z)));
        String coordinates = String.format(Locale.ROOT, "%.2f %.2f %.2f", position.x, position.y, position.z);
        for (int index = 0; index < VINDICATOR_SAMPLES; index++) {
            assertCommand(context, source, "meridian humanoid spawn vindicator true_qi " + coordinates);
            Vindicator vindicator = summoned(context, position);
            try {
                assertQiCommands(context, source, vindicator);
            } finally {
                vindicator.discard();
            }
        }
        context.succeed();
    }

    private static void learnAndCast(GameTestHelper context, DebugAddonGameTest.DebugTestPlayer online) {
        // 开脉、补元和学卷都走命令；首次施放进入起手，状态显示 WARMUP。
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(PLAYER_X, PLAYER_Y, PLAYER_Z)));
        online.player.moveTo(position.x, position.y, position.z, 0, 0);
        var source = source(online);
        assertCommand(context, source, "meridian opening complete @s all");
        assertCommand(context, source, "meridian qi fill @s");
        assertCommand(context, source, "meridian technique learn @s " + VOLUME);
        assertStarted(context, cast(online), "学会后无法施放");
        assertStatus(context, online, source, CastPhase.WARMUP.name());
        context.runAfterDelay(settleTicks(), () -> guarded(online, () -> clearCooldown(context, online)));
    }

    private static void clearCooldown(GameTestHelper context, DebugAddonGameTest.DebugTestPlayer online) {
        // 收势后仍在冷却：施放被拒、状态给出失败码；清冷却后同一刻再次施放成功。
        var source = source(online);
        CastOutcome cooling = cast(online);
        context.assertTrue(cooling.getFailure() == TechniqueFailure.COOLING_DOWN, "冷却中仍可施放：" + cooling);
        assertStatus(context, online, source, TechniqueFailure.COOLING_DOWN.name());
        assertCommand(context, source, "meridian technique cooldown clear @s");
        assertStarted(context, cast(online), "清除冷却后无法立即施放");
        context.runAfterDelay(settleTicks(), () -> guarded(online, () -> leapAndForget(context, online)));
    }

    private static void leapAndForget(GameTestHelper context, DebugAddonGameTest.DebugTestPlayer online) {
        // 上一次施放留下轻身状态；一次跃步恰好一行观察日志；忘记后施放按未学拒绝。
        var source = source(online);
        assertCommand(context, source, "meridian watch leap on");
        context.assertTrue(TechniqueStatusBenefits.active(online.player).isPresent(), "施放没有留下轻身状态");
        online.player.setOnGround(true /* onGround */);
        int before = online.chat().size();
        context.assertTrue(TechniqueLeaps.jump(online.player) == TechniqueLeaps.Jump.LEAP, "轻身状态下没有跃步");
        List<String> leaps = newLines(online, before).stream()
                .filter(line -> line.startsWith(LEAP_PREFIX))
                .toList();
        context.assertTrue(leaps.size() == 1, "一次跃步没有恰好一行观察日志：" + leaps);
        String leap = leaps.get(0);
        context.assertTrue(leap.startsWith(LEAP_LINE) && leap.contains(EFFORT_MARK), "跃步日志缺少落点或做功：" + leap);
        assertCommand(context, source, "meridian technique forget @s " + TECHNIQUE);
        CastOutcome forgotten = cast(online);
        context.assertTrue(forgotten.getFailure() == TechniqueFailure.NOT_KNOWN, "忘记后没有按未学拒绝：" + forgotten);
        online.close();
        context.succeed();
    }

    private static void guarded(DebugAddonGameTest.DebugTestPlayer online, Runnable stage) {
        // 断言失败时先断开测试玩家，免得它留在后续用例的玩家列表里。
        try {
            stage.run();
        } catch (RuntimeException failure) {
            online.close();
            throw failure;
        }
    }

    private static int settleTicks() {
        // 起手、发力与收势都结束后再留余量，让调度器收尾这次施放。
        var phases = TechniqueFabric.service().registry().findLatest(TECHNIQUE).orElseThrow().getPhases();
        long duration = phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis();
        return Math.toIntExact(duration / MILLIS_PER_TICK) + SETTLE_MARGIN_TICKS;
    }

    private static CastOutcome cast(DebugAddonGameTest.DebugTestPlayer online) {
        // 只表达施放意图，学会、冷却与做功都由正式功法服务裁定。
        var reference = TechniqueReference.newBuilder()
                .setId(TECHNIQUE)
                .setVersion(TECHNIQUE_VERSION)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(online.player.getStringUUID())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
        return TechniqueFabric.service().cast(online.player.getUUID(), request);
    }

    private static void assertStarted(GameTestHelper context, CastOutcome outcome, String message) {
        // 成功施放没有失败码，并停在起手阶段。
        boolean started = outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED
                && outcome.getPhase() == CastPhase.WARMUP;
        context.assertTrue(started, message + "：" + outcome);
    }

    private static void assertStatus(GameTestHelper context, DebugAddonGameTest.DebugTestPlayer online,
                                     CommandSourceStack source, String expected) {
        // 状态命令的回显就是管理员在聊天栏看到的文字。
        int before = online.chat().size();
        assertCommand(context, source, "meridian technique status");
        String status = String.join("\n", newLines(online, before));
        context.assertTrue(status.contains(expected), "功法状态缺少 " + expected + "：" + status);
    }

    private static void assertBody(GameTestHelper context, DebugAddonGameTest.DebugTestPlayer online,
                                   CommandSourceStack source) {
        // 摘要的境界取宿主快照的枚举名，真元行同时给出余额与容量。
        BodySnapshot body = DebugBodyAccess.inspect(online.player).orElseThrow();
        int before = online.chat().size();
        assertCommand(context, source, "meridian body status full");
        String summary = String.join("\n", newLines(online, before));
        boolean complete = summary.contains("境界：" + body.getRealm().name()) && summary.contains("真元：");
        context.assertTrue(complete, "身体摘要缺少境界或真元：" + summary);
    }

    private static void assertLegFatigue(GameTestHelper context, LivingEntity entity, double ratio) {
        // 逐块腿肌读取宿主快照，疲劳与容量之比必须等于命令给出的比例。
        BodySnapshot body = DebugBodyAccess.inspect(entity).orElseThrow();
        List<TissueDefinition> legs = DebugFatigue.select(body, LEG_MUSCLE);
        context.assertTrue(!legs.isEmpty(), "身体没有腿肌标签：" + LEG_MUSCLE);
        Map<String, Double> fatigue = body.getTissueStatesList().stream()
                .collect(Collectors.toMap(state -> state.getId(), state -> state.getFatigue()));
        for (TissueDefinition leg : legs) {
            double actual = fatigue.get(leg.getId()) / leg.getFatigueCapacity();
            context.assertTrue(Math.abs(actual - ratio) < TOLERANCE, "腿肌疲劳比例不符：" + leg.getId() + "=" + actual);
        }
    }

    private static void assertSuggests(GameTestHelper context, CommandSourceStack source, String input,
                                       String expected) {
        // 与客户端 Tab 相同，先按命令源解析再取原版补全。
        var dispatcher = source.getServer().getCommands().getDispatcher();
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join();
        boolean offered = suggestions.getList().stream().anyMatch(item -> item.getText().equals(expected));
        context.assertTrue(offered, "「" + input + "」没有补全 " + expected + "：" + suggestions.getList());
    }

    private static Vindicator summoned(GameTestHelper context, Vec3 position) {
        // 生成点只放这一只卫道士；上一只已在本轮 finally 中移除。
        AABB spot = new AABB(BlockPos.containing(position));
        List<Vindicator> found = context.getLevel().getEntitiesOfClass(Vindicator.class, spot,
                entity -> !entity.isRemoved());
        context.assertTrue(found.size() == 1, "summon 没有在指定位置恰好生成一只卫道士：" + found.size());
        return found.get(0);
    }

    private static void assertQiCommands(GameTestHelper context, CommandSourceStack source,
                                         Vindicator vindicator) {
        // 覆盖量取全身内腔按游离密度折算的一半；补满由卫道士自己执行省略目标的 qi fill，逐条经脉不越过定义上限。
        BodySnapshot initial = DebugBodyAccess.inspect(vindicator).orElseThrow();
        double totalVolume = initial.getStructure().getMeridiansList().stream()
                .mapToDouble(MeridianDefinition::getLumenVolume)
                .sum();
        String amount = String.format(Locale.ROOT, "%.6f", totalVolume * QiDensity.FREE * QI_SHARE);
        String uuid = vindicator.getStringUUID();
        assertCommand(context, source, "meridian qi set " + uuid + " " + amount);
        double total = lumenTotal(DebugBodyAccess.inspect(vindicator).orElseThrow());
        context.assertTrue(Math.abs(total - Double.parseDouble(amount)) < TOLERANCE, "qi set 余额不符：" + total);
        assertCommand(context, vindicator.createCommandSourceStack().withPermission(OP_LEVEL), "meridian qi fill");
        assertWithinLumens(context, DebugBodyAccess.inspect(vindicator).orElseThrow());
    }

    private static void assertQiParameters(GameTestHelper context, LivingEntity player, CommandSourceStack source) {
        // 先把全身内腔清零，之后每一步只看本步指定的经脉或账户。
        MeridianDefinition meridian = DebugBodyAccess.inspect(player).orElseThrow().getStructure().getMeridians(0);
        String id = meridian.getId();
        assertCommand(context, source, "meridian qi set 0 all lumen " + QiDensity.FREE);
        assertQi(context, lumenTotal(DebugBodyAccess.inspect(player).orElseThrow()), 0, "全身内腔没有清零");

        // 省略目标、带经脉与数量：该脉补到这一总量，其他经脉仍为空。
        double partial = rounded(meridian.getLumenVolume() * QiDensity.FREE * QI_SHARE);
        assertCommand(context, source, "meridian qi fill " + id + " " + decimal(partial));
        BodySnapshot afterPartial = DebugBodyAccess.inspect(player).orElseThrow();
        assertQi(context, lumen(afterPartial, id), partial, "单脉没有补到指定量");
        assertQi(context, lumenTotal(afterPartial), partial, "其他经脉被一并补入");

        // 指定目标、只带经脉：补满该脉，仍不越过内腔上限。
        assertCommand(context, source, "meridian qi fill @s " + id);
        BodySnapshot afterFull = DebugBodyAccess.inspect(player).orElseThrow();
        context.assertTrue(lumen(afterFull, id) > partial + TOLERANCE, "单脉没有补满：" + lumen(afterFull, id));
        assertQi(context, lumenTotal(afterFull), lumen(afterFull, id), "补满单脉时其他经脉被一并补入");
        assertWithinLumens(context, afterFull);

        // 带参数的 qi set：一条经脉的内腔按给定密度覆盖，再把全身外壁按给定密度覆盖。
        double lumenAmount = rounded(meridian.getLumenVolume() * SET_DENSITY * QI_SHARE);
        assertCommand(context, source, "meridian qi set @s " + decimal(lumenAmount) + " " + id + " lumen "
                + SET_DENSITY);
        BodySnapshot afterLumen = DebugBodyAccess.inspect(player).orElseThrow();
        assertQi(context, lumen(afterLumen, id), lumenAmount, "单脉内腔没有覆盖为指定量");
        boolean dense = state(afterLumen, id).getLumenQi().getEntriesList().stream()
                .allMatch(entry -> Math.abs(entry.getDensity() - SET_DENSITY) < TOLERANCE);
        context.assertTrue(dense, "单脉内腔密度不是指定值：" + state(afterLumen, id).getLumenQi());
        double wallVolume = afterLumen.getStructure().getMeridiansList().stream()
                .mapToDouble(MeridianDefinition::getWallVolume)
                .sum();
        double wallAmount = rounded(wallVolume * SET_DENSITY * QI_SHARE);
        assertCommand(context, source, "meridian qi set " + decimal(wallAmount) + " all wall " + SET_DENSITY);
        assertQi(context, wallTotal(DebugBodyAccess.inspect(player).orElseThrow()), wallAmount, "全身外壁没有覆盖为指定量");
    }

    private static void assertQi(GameTestHelper context, double actual, double expected, String message) {
        // 真元按浮点合计，比较时留同一份容差。
        context.assertTrue(Math.abs(actual - expected) < TOLERANCE, message + "：" + actual + " ≠ " + expected);
    }

    private static double rounded(double amount) {
        // 期望值与指令参数取同一份六位小数，断言不受格式化舍入影响。
        return Double.parseDouble(decimal(amount));
    }

    private static String decimal(double amount) {
        // 指令参数按固定小数位写出，避免科学计数法无法解析。
        return String.format(Locale.ROOT, "%.6f", amount);
    }

    private static MeridianState state(BodySnapshot body, String id) {
        // 按经脉 ID 取状态；测试只查询构型里第一条经脉，必然存在。
        return body.getMeridianStatesList().stream()
                .filter(state -> state.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static double lumen(BodySnapshot body, String id) {
        // 单条经脉内腔的各密度条目合计。
        return state(body, id).getLumenQi().getEntriesList().stream()
                .mapToDouble(entry -> entry.getAmount())
                .sum();
    }

    private static double wallTotal(BodySnapshot body) {
        // 全部经脉外壁的各密度条目合计。
        return body.getMeridianStatesList().stream()
                .flatMap(state -> state.getWallQi().getEntriesList().stream())
                .mapToDouble(entry -> entry.getAmount())
                .sum();
    }

    private static double lumenTotal(BodySnapshot body) {
        // 全部经脉内腔的各密度条目合计。
        return body.getMeridianStatesList().stream()
                .flatMap(state -> state.getLumenQi().getEntriesList().stream())
                .mapToDouble(entry -> entry.getAmount())
                .sum();
    }

    private static void assertWithinLumens(GameTestHelper context, BodySnapshot message) {
        // 用 Framework 领域对象折算占用体积，逐条经脉与定义上限比较。
        var body = BodyProtobuf.fromMessage(message);
        Map<Anatomy.MeridianId, Double> limits = body.structure().meridians().stream()
                .collect(Collectors.toMap(Anatomy.MeridianDefinition::id, Anatomy.MeridianDefinition::lumenVolume));
        for (var state : body.meridianStates()) {
            context.assertTrue(state.lumenQi().volume() <= limits.get(state.id()),
                    "补满后内腔越过上限：" + state.id().value());
        }
    }

    private static List<String> newLines(DebugAddonGameTest.DebugTestPlayer online, int before) {
        // 只取某次操作之后新到的聊天栏文字。
        List<String> lines = online.chat();
        return lines.subList(before, lines.size());
    }

    private static CommandSourceStack source(DebugAddonGameTest.DebugTestPlayer online) {
        // 玩家自身作命令源，回显进入测试连接记录的聊天栏。
        return online.player.createCommandSourceStack().withPermission(OP_LEVEL);
    }

    private static void assertCommand(GameTestHelper context, CommandSourceStack source, String command) {
        // 与聊天命令相同的执行入口；调试命令按成功目标数返回，这里每次只有一个目标。
        int result = source.getServer().getCommands().performPrefixedCommand(source, command);
        context.assertTrue(result == 1, "命令没有成功：" + command + " -> " + result);
    }
}
