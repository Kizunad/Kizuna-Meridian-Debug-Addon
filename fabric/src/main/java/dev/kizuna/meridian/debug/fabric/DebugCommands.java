package dev.kizuna.meridian.debug.fabric;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.kizuna.meridian.debug.HealPlan;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.fabric.MeridianCommands.Branch;
import dev.kizuna.meridian.fabric.MeridianCommands.Targets;
import dev.kizuna.meridian.human.fabric.HumanBodyStatus;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
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
        // 省略经脉时补满全身内腔；指定经脉时补满该脉，再带数量时补到该脉的这一总量。
        MeridianCommands.optionalEntities(fill, (parent, targets) -> parent
                .executes(context -> DebugFabric.fillQi(context.getSource(), targets.resolve(context),
                        WHOLE_BODY, OptionalDouble.empty()))
                .then(meridian(targets, DebugCommands::meridianIds)
                        .executes(context -> DebugFabric.fillQi(context.getSource(), targets.resolve(context),
                                StringArgumentType.getString(context, "meridian"), OptionalDouble.empty()))
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(MIN_FILL_AMOUNT))
                                .executes(context -> DebugFabric.fillQi(context.getSource(),
                                        targets.resolve(context), StringArgumentType.getString(context, "meridian"),
                                        OptionalDouble.of(DoubleArgumentType.getDouble(context, "amount")))))));
    }

    private static void qiSet(LiteralArgumentBuilder<CommandSourceStack> set) {
        // 只给数量时把全身内腔覆盖为游离态真元；带参数形式另指定经脉或 all、内腔或外壁以及密度。
        MeridianCommands.optionalEntities(set, (parent, targets) -> {
            var meridian = meridian(targets, DebugCommands::meridianIdsAndAll);
            for (QiAccount account : QiAccount.values()) {
                meridian.then(Commands.literal(account.literal())
                        .then(Commands.argument("density", DoubleArgumentType.doubleArg(MIN_DENSITY))
                                .executes(context -> DebugFabric.setQiAccount(context.getSource(),
                                        targets.resolve(context), DoubleArgumentType.getDouble(context, "amount"),
                                        meridianScope(context), account,
                                        DoubleArgumentType.getDouble(context, "density")))));
            }
            parent.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                    .executes(context -> DebugFabric.setQi(context.getSource(), targets.resolve(context),
                            DoubleArgumentType.getDouble(context, "amount")))
                    .then(meridian));
        });
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
                .then(Commands.argument("id", ResourceLocationArgument.id())
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
        return ResourceLocationArgument.getId(context, "id").toString();
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
