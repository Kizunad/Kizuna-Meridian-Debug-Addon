package dev.kizuna.meridian.debug.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.debug.HealPlan;
import java.util.Collection;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/** 调试命令树；每个分支都在执行点再次检查权限和实体归属。 */
final class DebugCommands {
    private static final double MIN_HEAL_AMOUNT = 0.000001;

    private DebugCommands() {
        // 命令注册只构造 Brigadier 树，不保存服务器状态。
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 所有调试分支统一要求权限 2，参数保持 literal 或原版实体选择器。
        var root = Commands.literal("meridian_debug")
                .requires(source -> source.hasPermission(DebugFabric.PERMISSION_LEVEL));
        root.then(damageCommand());
        root.then(healCommand());
        root.then(reviveCommand());
        root.then(aggroCommand());
        root.then(fatigueCommand());
        root.then(techniqueCommand());
        root.then(leapCommand());
        root.then(bodyCommand());
        root.then(qiCommand());
        dispatcher.register(root);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> aggroCommand() {
        // A、B 使用原版单实体选择器，省略模式时默认互相设置目标。
        var target = Commands.argument("B", EntityArgument.entity())
                .executes(context -> setAggro(context, AggroLinkMode.MUTUAL))
                .then(Commands.literal("mutual")
                        .executes(context -> setAggro(context, AggroLinkMode.MUTUAL)))
                .then(Commands.literal("oneway")
                        .executes(context -> setAggro(context, AggroLinkMode.ONEWAY)));
        var source = Commands.argument("A", EntityArgument.entity()).then(target);
        var clear = Commands.literal("clear")
                .executes(context -> DebugFabric.clearAllAggro(context.getSource()))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> DebugFabric.clearAggro(context.getSource(), targets(context))));
        return Commands.literal("aggro").then(source).then(clear);
    }

    private static int setAggro(CommandContext<CommandSourceStack> context, AggroLinkMode mode)
            throws CommandSyntaxException {
        Entity source = EntityArgument.getEntity(context, "A");
        Entity target = EntityArgument.getEntity(context, "B");
        return DebugFabric.setAggro(context.getSource(), source, target, mode);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> damageCommand() {
        // watch 的目标选择器只保存 UUID，实体重载不会让观察状态持有旧对象。
        var watch = Commands.literal("watch");
        var on = Commands.literal("on")
                .executes(context -> DebugFabric.watchAll(context.getSource()))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> DebugFabric.watchTargets(context.getSource(), targets(context))));
        var off = Commands.literal("off")
                .executes(context -> DebugFabric.watchOff(context.getSource()))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> DebugFabric.watchOff(context.getSource())));
        watch.then(on).then(off);
        return Commands.literal("damage").then(watch);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> reviveCommand() {
        // 无参数指向自己；实体选择器沿用原版补全，权限由根节点统一检查。
        return Commands.literal("revive")
                .executes(context -> DebugFabric.revive(context.getSource(), List.of(self(context))))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> DebugFabric.revive(context.getSource(), targets(context))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> healCommand() {
        // 模式 literal 明确表达健康、部位、体力和真元四条服务端路径。
        var targets = Commands.argument("targets", EntityArgument.entities());
        targets.executes(context -> DebugFabric.healAll(context.getSource(), targets(context)));
        targets.then(mode("all", HealMode.ALL));
        targets.then(mode("health", HealMode.HEALTH));
        targets.then(mode("limbs", HealMode.LIMBS));
        targets.then(mode("stamina", HealMode.STAMINA));
        targets.then(mode("zhenyuan", HealMode.ZHENYUAN));
        targets.then(limbCommand());
        return Commands.literal("heal").then(targets);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> mode(
            String literal, HealMode mode) {
        // 每个 literal 绑定固定枚举，执行时不再解析用户字符串。
        return Commands.literal(literal)
                .executes(context -> DebugFabric.heal(context.getSource(), targets(context), mode));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> limbCommand() {
        // 部位 ID 的补全从当前选择器目标的身体结构动态读取。
        var part = Commands.argument("part", com.mojang.brigadier.arguments.StringArgumentType.word())
                .suggests(DebugCommands::suggestParts)
                .executes(context -> DebugFabric.healLimb(context.getSource(), targets(context),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "part"), null))
                .then(Commands.argument("amount",
                                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(MIN_HEAL_AMOUNT))
                        .executes(context -> DebugFabric.healLimb(context.getSource(), targets(context),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "part"),
                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "amount"))));
        return Commands.literal("limb").then(part);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> fatigueCommand() {
        var set = Commands.literal("set")
                .then(fatigueSelfSelection())
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(fatigueSelection()));
        var clear = Commands.literal("clear")
                .executes(context -> DebugFabric.setFatigue(context.getSource(),
                        java.util.List.of(self(context)), DebugFatigue.ALL, 0))
                .then(Commands.argument("selection", StringArgumentType.string())
                        .suggests(DebugCommands::suggestFatigueSelection)
                        .executes(context -> DebugFabric.setFatigue(context.getSource(),
                                java.util.List.of(self(context)),
                                StringArgumentType.getString(context, "selection"), 0)))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> DebugFabric.setFatigue(context.getSource(), targets(context),
                                DebugFatigue.ALL, 0))
                        .then(Commands.argument("selection", StringArgumentType.string())
                                .suggests(DebugCommands::suggestFatigueSelection)
                                .executes(context -> DebugFabric.setFatigue(context.getSource(), targets(context),
                                        StringArgumentType.getString(context, "selection"), 0))));
        return Commands.literal("fatigue")
                .then(set)
                .then(clear);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            fatigueSelfSelection() {
        var selection = Commands.argument("selection", StringArgumentType.string())
                .suggests(DebugCommands::suggestFatigueSelection);
        var ratio = Commands.argument("ratio", DoubleArgumentType.doubleArg(0, 1))
                .executes(context -> DebugFabric.setFatigue(context.getSource(),
                        java.util.List.of(self(context)),
                        StringArgumentType.getString(context, "selection"),
                        DoubleArgumentType.getDouble(context, "ratio")));
        return selection.then(ratio);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            fatigueSelection() {
        var selection = Commands.argument("selection", StringArgumentType.string())
                .suggests(DebugCommands::suggestFatigueSelection);
        var ratio = Commands.argument("ratio", DoubleArgumentType.doubleArg(0, 1))
                .executes(context -> DebugFabric.setFatigue(context.getSource(), targets(context),
                        StringArgumentType.getString(context, "selection"),
                        DoubleArgumentType.getDouble(context, "ratio")));
        return selection.then(ratio);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> techniqueCommand() {
        var learn = Commands.literal("learn")
                .then(techniqueSelfTarget(DebugFabric.TechniqueAction.LEARN))
                .then(techniqueTargets(DebugFabric.TechniqueAction.LEARN));
        var forget = Commands.literal("forget")
                .then(techniqueSelfTarget(DebugFabric.TechniqueAction.FORGET))
                .then(techniqueTargets(DebugFabric.TechniqueAction.FORGET));
        var cooldown = Commands.literal("cooldown")
                .then(Commands.literal("clear")
                        .executes(context -> DebugFabric.clearTechniqueCooldowns(
                                context.getSource(), java.util.List.of(self(context))))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> DebugFabric.clearTechniqueCooldowns(
                                        context.getSource(), targets(context)))));
        var status = Commands.literal("status")
                .executes(context -> DebugFabric.techniqueStatus(context.getSource(), self(context)))
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> DebugFabric.techniqueStatus(
                                context.getSource(), EntityArgument.getEntity(context, "target"))));
        return Commands.literal("technique")
                .then(learn)
                .then(forget)
                .then(cooldown)
                .then(status);
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> techniqueTargets(
            DebugFabric.TechniqueAction action) {
        var targets = Commands.argument("targets", EntityArgument.entities());
        var id = Commands.argument("id", ResourceLocationArgument.id())
                .suggests(DebugCommands::suggestTechniqueIds)
                .executes(context -> DebugFabric.mutateTechnique(context.getSource(), targets(context),
                        action, techniqueId(context)));
        return targets.then(id);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation>
            techniqueSelfTarget(DebugFabric.TechniqueAction action) {
        return Commands.argument("id", ResourceLocationArgument.id())
                .suggests(DebugCommands::suggestTechniqueIds)
                .executes(context -> DebugFabric.mutateTechnique(context.getSource(),
                        java.util.List.of(self(context)), action, techniqueId(context)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> leapCommand() {
        var on = Commands.literal("on")
                .executes(context -> DebugFabric.watchLeaps(context.getSource()))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> DebugFabric.watchLeapTargets(context.getSource(), targets(context))));
        var off = Commands.literal("off")
                .executes(context -> DebugFabric.stopLeapWatch(context.getSource()))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> DebugFabric.stopLeapWatch(context.getSource())));
        return Commands.literal("leap").then(Commands.literal("watch").then(on).then(off));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> bodyCommand() {
        return Commands.literal("body")
                .executes(context -> DebugFabric.bodySummary(context.getSource(), self(context)))
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> DebugFabric.bodySummary(context.getSource(),
                                EntityArgument.getEntity(context, "target"))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> qiCommand() {
        return Commands.literal("qi")
                .then(Commands.literal("set")
                        .then(qiSelfAmount())
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(qiTargetAmount())));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double>
            qiSelfAmount() {
        return Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                .executes(context -> DebugFabric.setQi(context.getSource(),
                        java.util.List.of(self(context)), DoubleArgumentType.getDouble(context, "amount")));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double>
            qiTargetAmount() {
        return Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                .executes(context -> DebugFabric.setQi(context.getSource(), targets(context),
                        DoubleArgumentType.getDouble(context, "amount")));
    }

    private static String techniqueId(CommandContext<CommandSourceStack> context) {
        // 功法、卷册和书都用带命名空间的 ID，按原版标识符参数读取冒号。
        return ResourceLocationArgument.getId(context, "id").toString();
    }

    private static Entity self(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return context.getSource().getEntityOrException();
    }

    private static Collection<? extends net.minecraft.world.entity.Entity> targets(
            CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        // 同一选择器只解析一次，避免执行阶段重复读取世界实体。
        return EntityArgument.getEntities(context, "targets");
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestParts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // 选择器目标可能包含多个身体，合并实际存在的部位 ID 并去重。
        try {
            Collection<? extends net.minecraft.world.entity.Entity> selected = targets(context);
            for (net.minecraft.world.entity.Entity entity : selected) {
                if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                    DebugBodyAccess.inspect(living).ifPresent(body -> HealPlan.partIds(body).stream()
                            .filter(id -> id.startsWith(builder.getRemaining()))
                            .forEach(builder::suggest));
                }
            }
        } catch (CommandSyntaxException ignored) {
            // 选择器尚未完整输入时没有可读取的目标，保留 Brigadier 默认结果。
        }
        return builder.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestFatigueSelection(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Collection<? extends net.minecraft.world.entity.Entity> selected = fatigueSuggestionTargets(context);
        for (net.minecraft.world.entity.Entity entity : selected) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                DebugBodyAccess.inspect(living).ifPresent(body -> DebugFatigue.completionValues(body)
                        .forEach(value -> suggestSelection(builder, value)));
            }
        }
        return builder.buildFuture();
    }

    private static void suggestSelection(SuggestionsBuilder builder, String value) {
        // 标签含冒号，补全给出带引号的可解析文本；输入未加引号时也按原值前缀匹配。
        String escaped = StringArgumentType.escapeIfRequired(value);
        String remaining = builder.getRemaining();
        if (value.startsWith(remaining) || escaped.startsWith(remaining)) {
            builder.suggest(escaped);
        }
    }

    private static Collection<? extends net.minecraft.world.entity.Entity> fatigueSuggestionTargets(
            CommandContext<CommandSourceStack> context) {
        try {
            return targets(context);
        } catch (CommandSyntaxException ignored) {
            try {
                return List.of(self(context));
            } catch (CommandSyntaxException noExecutor) {
                return List.of();
            }
        }
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestTechniqueIds(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
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
        LIMBS,
        STAMINA,
        ZHENYUAN
    }
}
