package dev.kizuna.meridian.debug.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.debug.HealPlan;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.List;

/** 调试命令树；每个分支都在执行点再次检查权限和实体归属。 */
final class DebugCommands {
    private static final double MIN_HEAL_AMOUNT = 0.000001;

    private DebugCommands() {
        // 命令注册只构造 Brigadier 树，不保存服务器状态。
    }

    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // 所有调试分支统一要求权限 2，参数保持 literal 或原版实体选择器。
        var root = CommandManager.literal("meridian_debug")
                .requires(source -> source.hasPermissionLevel(DebugFabric.PERMISSION_LEVEL));
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

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> aggroCommand() {
        // A、B 使用原版单实体选择器，省略模式时默认互相设置目标。
        var target = CommandManager.argument("B", EntityArgumentType.entity())
                .executes(context -> setAggro(context, AggroLinkMode.MUTUAL))
                .then(CommandManager.literal("mutual")
                        .executes(context -> setAggro(context, AggroLinkMode.MUTUAL)))
                .then(CommandManager.literal("oneway")
                        .executes(context -> setAggro(context, AggroLinkMode.ONEWAY)));
        var source = CommandManager.argument("A", EntityArgumentType.entity()).then(target);
        var clear = CommandManager.literal("clear")
                .executes(context -> DebugFabric.clearAllAggro(context.getSource()))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .executes(context -> DebugFabric.clearAggro(context.getSource(), targets(context))));
        return CommandManager.literal("aggro").then(source).then(clear);
    }

    private static int setAggro(CommandContext<ServerCommandSource> context, AggroLinkMode mode)
            throws CommandSyntaxException {
        Entity source = EntityArgumentType.getEntity(context, "A");
        Entity target = EntityArgumentType.getEntity(context, "B");
        return DebugFabric.setAggro(context.getSource(), source, target, mode);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> damageCommand() {
        // watch 的目标选择器只保存 UUID，实体重载不会让观察状态持有旧对象。
        var watch = CommandManager.literal("watch");
        var on = CommandManager.literal("on")
                .executes(context -> DebugFabric.watchAll(context.getSource()))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .executes(context -> DebugFabric.watchTargets(context.getSource(), targets(context))));
        var off = CommandManager.literal("off")
                .executes(context -> DebugFabric.watchOff(context.getSource()))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .executes(context -> DebugFabric.watchOff(context.getSource())));
        watch.then(on).then(off);
        return CommandManager.literal("damage").then(watch);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> reviveCommand() {
        // 无参数指向自己；实体选择器沿用原版补全，权限由根节点统一检查。
        return CommandManager.literal("revive")
                .executes(context -> DebugFabric.revive(context.getSource(), List.of(self(context))))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .executes(context -> DebugFabric.revive(context.getSource(), targets(context))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> healCommand() {
        // 模式 literal 明确表达健康、部位、体力和真元四条服务端路径。
        var targets = CommandManager.argument("targets", EntityArgumentType.entities());
        targets.executes(context -> DebugFabric.healAll(context.getSource(), targets(context)));
        targets.then(mode("all", HealMode.ALL));
        targets.then(mode("health", HealMode.HEALTH));
        targets.then(mode("limbs", HealMode.LIMBS));
        targets.then(mode("stamina", HealMode.STAMINA));
        targets.then(mode("zhenyuan", HealMode.ZHENYUAN));
        targets.then(limbCommand());
        return CommandManager.literal("heal").then(targets);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> mode(
            String literal, HealMode mode) {
        // 每个 literal 绑定固定枚举，执行时不再解析用户字符串。
        return CommandManager.literal(literal)
                .executes(context -> DebugFabric.heal(context.getSource(), targets(context), mode));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> limbCommand() {
        // 部位 ID 的补全从当前选择器目标的身体结构动态读取。
        var part = CommandManager.argument("part", com.mojang.brigadier.arguments.StringArgumentType.word())
                .suggests(DebugCommands::suggestParts)
                .executes(context -> DebugFabric.healLimb(context.getSource(), targets(context),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "part"), null))
                .then(CommandManager.argument("amount",
                                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(MIN_HEAL_AMOUNT))
                        .executes(context -> DebugFabric.healLimb(context.getSource(), targets(context),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "part"),
                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "amount"))));
        return CommandManager.literal("limb").then(part);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> fatigueCommand() {
        var set = CommandManager.literal("set")
                .then(fatigueSelfSelection())
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .then(fatigueSelection()));
        var clear = CommandManager.literal("clear")
                .executes(context -> DebugFabric.setFatigue(context.getSource(),
                        java.util.List.of(self(context)), DebugFatigue.ALL, 0))
                .then(CommandManager.argument("selection", StringArgumentType.string())
                        .suggests(DebugCommands::suggestFatigueSelection)
                        .executes(context -> DebugFabric.setFatigue(context.getSource(),
                                java.util.List.of(self(context)),
                                StringArgumentType.getString(context, "selection"), 0)))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .executes(context -> DebugFabric.setFatigue(context.getSource(), targets(context),
                                DebugFatigue.ALL, 0))
                        .then(CommandManager.argument("selection", StringArgumentType.string())
                                .suggests(DebugCommands::suggestFatigueSelection)
                                .executes(context -> DebugFabric.setFatigue(context.getSource(), targets(context),
                                        StringArgumentType.getString(context, "selection"), 0))));
        return CommandManager.literal("fatigue")
                .then(set)
                .then(clear);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String>
            fatigueSelfSelection() {
        var selection = CommandManager.argument("selection", StringArgumentType.string())
                .suggests(DebugCommands::suggestFatigueSelection);
        var ratio = CommandManager.argument("ratio", DoubleArgumentType.doubleArg(0, 1))
                .executes(context -> DebugFabric.setFatigue(context.getSource(),
                        java.util.List.of(self(context)),
                        StringArgumentType.getString(context, "selection"),
                        DoubleArgumentType.getDouble(context, "ratio")));
        return selection.then(ratio);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String>
            fatigueSelection() {
        var selection = CommandManager.argument("selection", StringArgumentType.string())
                .suggests(DebugCommands::suggestFatigueSelection);
        var ratio = CommandManager.argument("ratio", DoubleArgumentType.doubleArg(0, 1))
                .executes(context -> DebugFabric.setFatigue(context.getSource(), targets(context),
                        StringArgumentType.getString(context, "selection"),
                        DoubleArgumentType.getDouble(context, "ratio")));
        return selection.then(ratio);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> techniqueCommand() {
        var learn = CommandManager.literal("learn")
                .then(techniqueSelfTarget(DebugFabric.TechniqueAction.LEARN))
                .then(techniqueTargets(DebugFabric.TechniqueAction.LEARN));
        var forget = CommandManager.literal("forget")
                .then(techniqueSelfTarget(DebugFabric.TechniqueAction.FORGET))
                .then(techniqueTargets(DebugFabric.TechniqueAction.FORGET));
        var cooldown = CommandManager.literal("cooldown")
                .then(CommandManager.literal("clear")
                        .executes(context -> DebugFabric.clearTechniqueCooldowns(
                                context.getSource(), java.util.List.of(self(context))))
                        .then(CommandManager.argument("targets", EntityArgumentType.entities())
                                .executes(context -> DebugFabric.clearTechniqueCooldowns(
                                        context.getSource(), targets(context)))));
        var status = CommandManager.literal("status")
                .executes(context -> DebugFabric.techniqueStatus(context.getSource(), self(context)))
                .then(CommandManager.argument("target", EntityArgumentType.entity())
                        .executes(context -> DebugFabric.techniqueStatus(
                                context.getSource(), EntityArgumentType.getEntity(context, "target"))));
        return CommandManager.literal("technique")
                .then(learn)
                .then(forget)
                .then(cooldown)
                .then(status);
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<ServerCommandSource, ?> techniqueTargets(
            DebugFabric.TechniqueAction action) {
        var targets = CommandManager.argument("targets", EntityArgumentType.entities());
        var id = CommandManager.argument("id", IdentifierArgumentType.identifier())
                .suggests(DebugCommands::suggestTechniqueIds)
                .executes(context -> DebugFabric.mutateTechnique(context.getSource(), targets(context),
                        action, techniqueId(context)));
        return targets.then(id);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Identifier>
            techniqueSelfTarget(DebugFabric.TechniqueAction action) {
        return CommandManager.argument("id", IdentifierArgumentType.identifier())
                .suggests(DebugCommands::suggestTechniqueIds)
                .executes(context -> DebugFabric.mutateTechnique(context.getSource(),
                        java.util.List.of(self(context)), action, techniqueId(context)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> leapCommand() {
        var on = CommandManager.literal("on")
                .executes(context -> DebugFabric.watchLeaps(context.getSource()))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .executes(context -> DebugFabric.watchLeapTargets(context.getSource(), targets(context))));
        var off = CommandManager.literal("off")
                .executes(context -> DebugFabric.stopLeapWatch(context.getSource()))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .executes(context -> DebugFabric.stopLeapWatch(context.getSource())));
        return CommandManager.literal("leap").then(CommandManager.literal("watch").then(on).then(off));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> bodyCommand() {
        return CommandManager.literal("body")
                .executes(context -> DebugFabric.bodySummary(context.getSource(), self(context)))
                .then(CommandManager.argument("target", EntityArgumentType.entity())
                        .executes(context -> DebugFabric.bodySummary(context.getSource(),
                                EntityArgumentType.getEntity(context, "target"))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> qiCommand() {
        return CommandManager.literal("qi")
                .then(CommandManager.literal("set")
                        .then(qiSelfAmount())
                        .then(CommandManager.argument("targets", EntityArgumentType.entities())
                                .then(qiTargetAmount())));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            qiSelfAmount() {
        return CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                .executes(context -> DebugFabric.setQi(context.getSource(),
                        java.util.List.of(self(context)), DoubleArgumentType.getDouble(context, "amount")));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double>
            qiTargetAmount() {
        return CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                .executes(context -> DebugFabric.setQi(context.getSource(), targets(context),
                        DoubleArgumentType.getDouble(context, "amount")));
    }

    private static String techniqueId(CommandContext<ServerCommandSource> context) {
        // 功法、卷册和书都用带命名空间的 ID，按原版标识符参数读取冒号。
        return IdentifierArgumentType.getIdentifier(context, "id").toString();
    }

    private static Entity self(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return context.getSource().getEntityOrThrow();
    }

    private static Collection<? extends net.minecraft.entity.Entity> targets(
            CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        // 同一选择器只解析一次，避免执行阶段重复读取世界实体。
        return EntityArgumentType.getEntities(context, "targets");
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestParts(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        // 选择器目标可能包含多个身体，合并实际存在的部位 ID 并去重。
        try {
            Collection<? extends net.minecraft.entity.Entity> selected = targets(context);
            for (net.minecraft.entity.Entity entity : selected) {
                if (entity instanceof net.minecraft.entity.LivingEntity living) {
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
    suggestFatigueSelection(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        Collection<? extends net.minecraft.entity.Entity> selected = fatigueSuggestionTargets(context);
        for (net.minecraft.entity.Entity entity : selected) {
            if (entity instanceof net.minecraft.entity.LivingEntity living) {
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

    private static Collection<? extends net.minecraft.entity.Entity> fatigueSuggestionTargets(
            CommandContext<ServerCommandSource> context) {
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
    suggestTechniqueIds(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
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
