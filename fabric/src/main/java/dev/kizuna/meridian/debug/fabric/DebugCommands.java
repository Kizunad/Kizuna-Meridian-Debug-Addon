package dev.kizuna.meridian.debug.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.debug.HealPlan;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collection;

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
        root.then(aggroCommand());
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

    enum HealMode {
        ALL,
        HEALTH,
        LIMBS,
        STAMINA,
        ZHENYUAN
    }
}
