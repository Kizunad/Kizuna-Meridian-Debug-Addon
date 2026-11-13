package dev.kizuna.meridian.debug.fabric;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.kizuna.meridian.debug.HealPlan;
import dev.kizuna.meridian.debug.QiDose;
import dev.kizuna.meridian.debug.QiMix;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.fabric.MeridianCommands.Branch;
import dev.kizuna.meridian.fabric.MeridianCommands.Targets;
import dev.kizuna.meridian.human.fabric.HumanBodyStatus;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** 调试指令在 /meridian 各二级节点下的登记；权限声明在三级节点上，每个分支在执行点再检查实体归属。 */
final class DebugCommands {
    /** DebugQi 的经脉 ID 为空表示全身经脉。 */
    static final String WHOLE_BODY = "";
    private static final String ALL_MERIDIANS = "all";
    private static final double MIN_HEAL_AMOUNT = 0.000001;
    private static final double MIN_FILL_AMOUNT = 0.000001;
    private static final double MIN_DENSITY = 0.01;
    private static final String PHASE = "phase";
    private static final String ATTRIBUTE = "attribute";
    private static final String MIX = "mix";
    private static final String ATTRIBUTE_ID = "attribute_id";
    private static final String MIX_SHARES = "shares";

    private DebugCommands() {
        // 命令注册只构造 Brigadier 树，不保存服务器状态。
    }

    static void register() {
        // 调试指令按领域分散到 body、qi、technique、humanoid、debug 五个二级节点，全部是调试性质，三级节点统一权限 2。
        int permission = MeridianCommands.GAMEMASTER;
        MeridianCommands.register(Branch.BODY, "heal", permission, (node, context) -> heal(node));
        MeridianCommands.register(Branch.BODY, "revive", permission, (node, context) -> revive(node));
        MeridianCommands.register(Branch.QI, "fill", permission, (node, context) -> qiFill(node));
        MeridianCommands.register(Branch.QI, "set", permission, (node, context) -> qiSet(node));
        MeridianCommands.register(Branch.TECHNIQUE, "forget", permission, (node, context) -> forget(node));
        MeridianCommands.register(Branch.TECHNIQUE, "cooldown", permission, (node, context) -> cooldown(node));
        MeridianCommands.register(Branch.TECHNIQUE, "status", permission, (node, context) -> status(node));
        MeridianCommands.register(Branch.HUMANOID, "aggro", permission, (node, context) -> aggro(node));
        MeridianCommands.register(Branch.DEBUG, "fatigue", permission, (node, context) -> fatigue(node));

        // 观察频道由注册点生成 on|off [<targets>]；完整身体明细经 Human 的公开入口挂在 body status 之后。
        MeridianCommands.registerWatchChannel("damage", permission, DebugFabric::watchDamage);
        MeridianCommands.registerWatchChannel("leap", permission, DebugFabric::watchLeaps);
        HumanBodyStatus.registerDetail(DebugFabric::bodySummary);
    }

    private static void heal(LiteralArgumentBuilder<CommandSourceStack> heal) {
        // 省略模式时执行 all；模式 literal 绑定固定枚举，limb 只恢复一个部位。
        MeridianCommands.optionalEntities(heal, (parent, targets) -> parent
                .executes(context -> DebugFabric.healAll(context.getSource(), targets.resolve(context)))
                .then(healMode("all", HealMode.ALL, targets))
                .then(healMode("health", HealMode.HEALTH, targets))
                .then(healMode("stamina", HealMode.STAMINA, targets))
                .then(limb(targets)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> healMode(
            String literal, HealMode mode, Targets<Collection<? extends Entity>> targets) {
        // 每个 literal 绑定固定枚举，执行时不再解析用户字符串。
        return Commands.literal(literal)
                .executes(context -> DebugFabric.heal(context.getSource(), targets.resolve(context), mode));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> limb(Targets<Collection<? extends Entity>> targets) {
        // 部位 ID 的补全从目标身体的实际结构读取；省略数值时补满该部位。
        var part = Commands.argument("part", StringArgumentType.word())
                .suggests((context, builder) -> suggestFromBodies(context, builder, targets, HealPlan::partIds))
                .executes(context -> DebugFabric.healLimb(context.getSource(), targets.resolve(context),
                        StringArgumentType.getString(context, "part"), null))
                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(MIN_HEAL_AMOUNT))
                        .executes(context -> DebugFabric.healLimb(context.getSource(), targets.resolve(context),
                                StringArgumentType.getString(context, "part"),
                                DoubleArgumentType.getDouble(context, "amount"))));
        return Commands.literal("limb").then(part);
    }

    private static void revive(LiteralArgumentBuilder<CommandSourceStack> revive) {
        // 省略目标时复活自己的死态身体。
        MeridianCommands.optionalEntities(revive, (parent, targets) -> parent
                .executes(context -> DebugFabric.revive(context.getSource(), targets.resolve(context))));
    }

    private static void qiFill(LiteralArgumentBuilder<CommandSourceStack> fill) {
        // 省略经脉时补满全身内腔；指定经脉时补满该脉，再带数量时补到该脉的这一总量。三处之后都能接形态与属性。
        // 省略目标那一支先注册，Brigadier 会把「qi fill <词>」都交给它，所以这一支要先把词当在线玩家名认。
        MeridianCommands.optionalEntities(fill, (parent, targets) -> {
            WordFill wordFill;
            if (parent == fill) {
                wordFill = DebugCommands::fillNamedPlayerOrOwnMeridian;
            } else {
                wordFill = DebugCommands::fillListedMeridian;
            }
            DoseRun wholeBody = (context, dose) -> DebugFabric.fillQi(context.getSource(), targets.resolve(context),
                    WHOLE_BODY, OptionalDouble.empty(), dose);
            DoseRun listed = (context, dose) -> wordFill.run(context, targets, OptionalDouble.empty(), dose);
            DoseRun amount = (context, dose) -> wordFill.run(context, targets,
                    OptionalDouble.of(DoubleArgumentType.getDouble(context, "amount")), dose);

            var amountNode = Commands.argument("amount", DoubleArgumentType.doubleArg(MIN_FILL_AMOUNT))
                    .executes(context -> amount.run(context, QiDose.FREE));
            doseOptions(amountNode, amount);
            var meridianNode = meridian(targets, DebugCommands::meridianIds)
                    .executes(context -> listed.run(context, QiDose.FREE))
                    .then(amountNode);
            doseOptions(meridianNode, listed);
            parent.executes(context -> wholeBody.run(context, QiDose.FREE)).then(meridianNode);
            doseOptions(parent, wholeBody);
        });
    }

    /** qi fill 在目标之后那个词的执行方式；省略目标与指定目标两支各取一种。 */
    @FunctionalInterface
    private interface WordFill {
        int run(CommandContext<CommandSourceStack> context, Targets<Collection<? extends Entity>> targets,
                OptionalDouble amount, QiDose dose) throws CommandSyntaxException;
    }

    /** qi fill / set 选好范围与数量后的执行点，dose 带上可选的形态与属性比例。 */
    @FunctionalInterface
    private interface DoseRun {
        int run(CommandContext<CommandSourceStack> context, QiDose dose) throws CommandSyntaxException;
    }

    private static int fillListedMeridian(CommandContext<CommandSourceStack> context,
                                          Targets<Collection<? extends Entity>> targets, OptionalDouble amount,
                                          QiDose dose) throws CommandSyntaxException {
        // 目标已经写明，这个词只能是经脉 ID。
        return DebugFabric.fillQi(context.getSource(), targets.resolve(context),
                StringArgumentType.getString(context, "meridian"), amount, dose);
    }

    private static int fillNamedPlayerOrOwnMeridian(CommandContext<CommandSourceStack> context,
                                                    Targets<Collection<? extends Entity>> targets,
                                                    OptionalDouble amount, QiDose dose)
            throws CommandSyntaxException {
        // 目标优先：这个词是在线玩家名，就补满这名玩家全身；否则才当执行者自己的经脉 ID。
        String word = StringArgumentType.getString(context, "meridian");
        CommandSourceStack source = context.getSource();
        ServerPlayer named = source.getServer().getPlayerList().getPlayerByName(word);
        if (named != null && amount.isPresent()) {
            source.sendFailure(Component.literal(word + " 是在线玩家；给这名玩家补一条经脉要写 /meridian qi fill "
                    + word + " <经脉> [数量]"));
            return 0;
        }
        if (named != null) {
            return DebugFabric.fillQi(source, List.of(named), WHOLE_BODY, OptionalDouble.empty(), dose);
        }
        if (source.getEntity() == null) {
            source.sendFailure(Component.literal("没有名叫 " + word + " 的在线玩家；控制台和命令方块执行要写玩家名或选择器"));
            return 0;
        }
        return DebugFabric.fillQi(source, targets.resolve(context), word, amount, dose);
    }

    private static void qiSet(LiteralArgumentBuilder<CommandSourceStack> set) {
        // 只给数量时覆盖全身内腔；带参数形式另指定经脉或 all、内腔或外壁，再写密度或形态。三处之后都能接属性。
        MeridianCommands.optionalEntities(set, (parent, targets) -> {
            var meridian = meridian(targets, DebugCommands::meridianIdsAndAll);
            for (QiAccount account : QiAccount.values()) {
                DoseRun scoped = (context, dose) -> DebugFabric.setQiAccount(context.getSource(),
                        targets.resolve(context), DoubleArgumentType.getDouble(context, "amount"),
                        meridianScope(context), account, dose);
                var densityNode = Commands.argument("density", DoubleArgumentType.doubleArg(MIN_DENSITY))
                        .executes(context -> scoped.run(context, new QiDose(density(context), List.of())));
                attributeOptions(densityNode, DebugCommands::density, scoped);
                var accountNode = Commands.literal(account.literal()).then(densityNode);
                doseOptions(accountNode, scoped);
                meridian.then(accountNode);
            }
            DoseRun wholeBody = (context, dose) -> DebugFabric.setQiAccount(context.getSource(),
                    targets.resolve(context), DoubleArgumentType.getDouble(context, "amount"), WHOLE_BODY,
                    QiAccount.LUMEN, dose);
            var amount = Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                    .executes(context -> wholeBody.run(context, QiDose.FREE))
                    .then(meridian);
            doseOptions(amount, wholeBody);
            parent.then(amount);
        });
    }

    private static double density(CommandContext<CommandSourceStack> context) {
        // 带参数 qi set 直接写出的密度。
        return DoubleArgumentType.getDouble(context, "density");
    }

    private static void doseOptions(ArgumentBuilder<CommandSourceStack, ?> anchor, DoseRun run) {
        // 范围与数量之后可写 phase <形态>，再写 attribute <属性> 或 mix <比例>；都省略时是无属性游离态。
        var phase = Commands.literal(PHASE);
        for (QiPhase value : QiDose.PHASES) {
            double baseline = QiDose.baseline(value);
            var literal = Commands.literal(QiDose.phaseName(value))
                    .executes(context -> run.run(context, new QiDose(baseline, List.of())));
            attributeOptions(literal, context -> baseline, run);
            phase.then(literal);
        }
        anchor.then(phase);
        attributeOptions(anchor, context -> QiDensity.FREE, run);
    }

    private static void attributeOptions(ArgumentBuilder<CommandSourceStack, ?> node,
                                         ToDoubleFunction<CommandContext<CommandSourceStack>> density, DoseRun run) {
        // attribute 只写一个属性，mix 写「属性=比例」列表；两者都取到行尾，属性 ID 里的冒号不用加引号。
        var attribute = Commands.argument(ATTRIBUTE_ID, StringArgumentType.greedyString())
                .suggests((context, builder) -> suggestAttribute(builder))
                .executes(context -> run.run(context, parsedDose(context, density, ATTRIBUTE_ID, QiMix::single)));
        var mix = Commands.argument(MIX_SHARES, StringArgumentType.greedyString())
                .suggests((context, builder) -> suggestMix(builder))
                .executes(context -> run.run(context, parsedDose(context, density, MIX_SHARES, QiMix::parse)));
        node.then(Commands.literal(ATTRIBUTE).then(attribute)).then(Commands.literal(MIX).then(mix));
    }

    private static QiDose parsedDose(CommandContext<CommandSourceStack> context,
                                     ToDoubleFunction<CommandContext<CommandSourceStack>> density, String argument,
                                     BiFunction<String, Collection<String>, List<QiMix.Share>> parser)
            throws CommandSyntaxException {
        // 属性必须在已登记名单里，比例写错时整条指令报错，不改任何身体。
        String text = StringArgumentType.getString(context, argument);
        try {
            return new QiDose(density.applyAsDouble(context), parser.apply(text, DebugQiAttributes.registered()));
        } catch (IllegalArgumentException invalid) {
            throw new SimpleCommandExceptionType(Component.literal(invalid.getMessage())).create();
        }
    }

    private static CompletableFuture<Suggestions> suggestAttribute(SuggestionsBuilder builder) {
        // 列出全部已登记属性：内置写短名，附属写全 ID。
        QiMix.completeAttribute(builder.getRemaining(), DebugQiAttributes.registered()).forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestMix(SuggestionsBuilder builder) {
        // 只替换正在输入的最后一项：先补「属性=」，写了等号再补剩下的比例。
        QiMix.Completion completion = QiMix.completeMix(builder.getRemaining(), DebugQiAttributes.registered());
        SuggestionsBuilder last = builder.createOffset(builder.getStart() + completion.offset());
        completion.candidates().forEach(last::suggest);
        return last.buildFuture();
    }

    private static String meridianScope(CommandContext<CommandSourceStack> context) {
        // all 表示全身经脉，按 DebugQi 的约定写成空经脉 ID。
        String meridian = StringArgumentType.getString(context, "meridian");
        if (meridian.equals(ALL_MERIDIANS)) {
            return WHOLE_BODY;
        }
        return meridian;
    }

    private static void forget(LiteralArgumentBuilder<CommandSourceStack> forget) {
        // 功法 ID 按原版标识符读取，补全列出目录里的书、卷与式。
        MeridianCommands.optionalEntities(forget, (parent, targets) -> parent
                .then(Commands.argument("id", IdentifierArgument.id())
                        .suggests(DebugCommands::suggestTechniqueIds)
                        .executes(context -> DebugFabric.forgetTechnique(context.getSource(),
                                targets.resolve(context), techniqueId(context)))));
    }

    private static void cooldown(LiteralArgumentBuilder<CommandSourceStack> cooldown) {
        // cooldown 下只有 clear 一个动作，可选目标紧跟在 clear 之后。
        var clear = Commands.literal("clear");
        MeridianCommands.optionalEntities(clear, (parent, targets) -> parent
                .executes(context -> DebugFabric.clearTechniqueCooldowns(context.getSource(),
                        targets.resolve(context))));
        cooldown.then(clear);
    }

    private static void status(LiteralArgumentBuilder<CommandSourceStack> status) {
        // 状态只看一个实体：省略时看自己。
        MeridianCommands.optionalEntity(status, (parent, target) -> parent
                .executes(context -> DebugFabric.techniqueStatus(context.getSource(), target.resolve(context))));
    }

    private static void aggro(LiteralArgumentBuilder<CommandSourceStack> aggro) {
        // A、B 使用原版单实体选择器，省略模式时默认互相设置目标。
        var target = Commands.argument("B", EntityArgument.entity())
                .executes(context -> setAggro(context, AggroLinkMode.MUTUAL))
                .then(Commands.literal("mutual")
                        .executes(context -> setAggro(context, AggroLinkMode.MUTUAL)))
                .then(Commands.literal("oneway")
                        .executes(context -> setAggro(context, AggroLinkMode.ONEWAY)));

        // clear 省略目标时沿用原有语义清除全部覆盖：执行者是玩家，本身不会出现在仇恨覆盖里。
        var clear = Commands.literal("clear")
                .executes(context -> DebugFabric.clearAllAggro(context.getSource()))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> DebugFabric.clearAggro(context.getSource(),
                                EntityArgument.getEntities(context, "targets"))));
        aggro.then(Commands.argument("A", EntityArgument.entity()).then(target)).then(clear);
    }

    private static int setAggro(CommandContext<CommandSourceStack> context, AggroLinkMode mode)
            throws CommandSyntaxException {
        // 两个选择器各自解析成单个实体，合法性由 DebugFabric 检查。
        Entity source = EntityArgument.getEntity(context, "A");
        Entity target = EntityArgument.getEntity(context, "B");
        return DebugFabric.setAggro(context.getSource(), source, target, mode);
    }

    private static void fatigue(LiteralArgumentBuilder<CommandSourceStack> fatigue) {
        // 组织选择必填，可写组织 ID、带冒号的标签或 all；清除即把比例设为零。
        var set = Commands.literal("set");
        MeridianCommands.optionalEntities(set, (parent, targets) -> parent
                .then(fatigueSelection(targets)
                        .then(Commands.argument("ratio", DoubleArgumentType.doubleArg(0, 1))
                                .executes(context -> DebugFabric.setFatigue(context.getSource(),
                                        targets.resolve(context), StringArgumentType.getString(context, "selection"),
                                        DoubleArgumentType.getDouble(context, "ratio"))))));
        var clear = Commands.literal("clear");
        MeridianCommands.optionalEntities(clear, (parent, targets) -> parent
                .then(fatigueSelection(targets)
                        .executes(context -> DebugFabric.setFatigue(context.getSource(), targets.resolve(context),
                                StringArgumentType.getString(context, "selection"), 0))));
        fatigue.then(set).then(clear);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> fatigueSelection(
            Targets<Collection<? extends Entity>> targets) {
        // 标签含冒号，用可加引号的字符串参数；补全取目标身体里的组织 ID 与标签。
        return Commands.argument("selection", StringArgumentType.string())
                .suggests((context, builder) -> suggestFromBodies(context, builder, targets,
                        DebugFatigue::completionValues));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> meridian(
            Targets<Collection<? extends Entity>> targets, Function<BodySnapshot, Collection<String>> values) {
        // 经脉 ID 从目标身体的实际结构补全，不复制人体经脉名单。
        return Commands.argument("meridian", StringArgumentType.word())
                .suggests((context, builder) -> suggestFromBodies(context, builder, targets, values));
    }

    private static Collection<String> meridianIds(BodySnapshot body) {
        // 只列当前构型里的经脉，改造后的身体不会继续暴露旧名单。
        return body.getStructure().getMeridiansList().stream()
                .map(MeridianDefinition::getId)
                .toList();
    }

    private static Collection<String> meridianIdsAndAll(BodySnapshot body) {
        // qi set 的带参数形式另接受 all 表示全身经脉。
        List<String> values = new ArrayList<>();
        values.add(ALL_MERIDIANS);
        values.addAll(meridianIds(body));
        return values;
    }

    private static String techniqueId(CommandContext<CommandSourceStack> context) {
        // 功法、卷册和书都用带命名空间的 ID，按原版标识符参数读取冒号。
        return IdentifierArgument.getId(context, "id").toString();
    }

    private static CompletableFuture<Suggestions> suggestFromBodies(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            Targets<Collection<? extends Entity>> targets, Function<BodySnapshot, Collection<String>> values) {
        // 选择器可能包含多个身体，合并它们实际存在的值；目标还解析不出来时不给建议。
        Collection<? extends Entity> selected;
        try {
            selected = targets.resolve(context);
        } catch (CommandSyntaxException unresolved) {
            return builder.buildFuture();
        }
        for (Entity entity : selected) {
            if (entity instanceof LivingEntity living) {
                DebugBodyAccess.inspect(living).ifPresent(body -> values.apply(body)
                        .forEach(value -> suggestValue(builder, value)));
            }
        }
        return builder.buildFuture();
    }

    private static void suggestValue(SuggestionsBuilder builder, String value) {
        // 标签含冒号，补全给出带引号的可解析文本；输入未加引号时也按原值前缀匹配。普通 ID 转义后不变。
        String escaped = StringArgumentType.escapeIfRequired(value);
        String remaining = builder.getRemaining();
        if (value.startsWith(remaining) || escaped.startsWith(remaining)) {
            builder.suggest(escaped);
        }
    }

    private static CompletableFuture<Suggestions> suggestTechniqueIds(CommandContext<CommandSourceStack> context,
                                                                      SuggestionsBuilder builder) {
        // 补全读取当前已加载的功法目录。
        try {
            DebugFabric.techniqueIds().stream()
                    .filter(id -> id.startsWith(builder.getRemaining()))
                    .forEach(builder::suggest);
        } catch (RuntimeException ignored) {
            // 服务器目录尚未启动时保留空补全，执行点会返回明确错误。
        }
        return builder.buildFuture();
    }

    enum HealMode {
        ALL,
        HEALTH,
        STAMINA
    }

    /** qi set 带参数形式的账户：字面量就是玩家输入的 lumen 或 wall。 */
    enum QiAccount {
        LUMEN("lumen", "内腔"),
        WALL("wall", "外壁");

        private final String literal;
        private final String label;

        QiAccount(String literal, String label) {
            // 枚举顺序就是补全顺序；label 用于指令反馈。
            this.literal = literal;
            this.label = label;
        }

        String literal() {
            // 建树时作为经脉之后的字面量。
            return literal;
        }

        String label() {
            // 反馈里写中文账户名。
            return label;
        }
    }
}
