package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.debug.DamageLogEntry;
import dev.kizuna.meridian.debug.DamageLogFormatter;
import dev.kizuna.meridian.debug.DamageSpilloverLog;
import dev.kizuna.meridian.debug.HealPlan;
import dev.kizuna.meridian.humanoid.fabric.HumanoidHealthHost;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.HealthMessages.FatigueRelief;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HealBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.Healing;
import dev.kizuna.meridian.protocol.pb.HealthMessages.RestoreFatigue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 调试库 Fabric 入口；命令、物品和伤害日志都沿服务端权威接口运行。 */
public final class DebugFabric implements ModInitializer {
    public static final Identifier LENS_STATE = new Identifier("meridian_debug", "lens_state");
    private static final Logger LOGGER = Logger.getLogger("meridian_debug");
    static final int PERMISSION_LEVEL = 2;
    private static final int LENS_REFRESH_TICKS = 10;
    private static final int MAX_TARGET_NAME_LENGTH = 64;
    private static final String HEALTH_SOURCE = "meridian_debug:command";
    private static final String ZHENYUAN_COMMAND = "meridian zhenyuan fill";
    private final DamageWatchRegistry watches = new DamageWatchRegistry();
    private final AggroLinkRegistry aggroLinks = new AggroLinkRegistry();
    private int tickCounter;

    @Override
    public void onInitialize() {
        // 入口先绑定注册表和身体宿主，再安装命令、伤害观察和周期推送。
        INSTANCE = this;
        DebugItems.register();
        DebugBodyAccess.bindHosts();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                DebugCommands.register(dispatcher));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            watches.clear(handler.player.getUuid());
            aggroLinks.clearPlayer(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(aggroLinks::clearRuntimeState);
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        VanillaDamageFabric.registerDamageObserver(this::recordDamage);
    }

    static int watchAll(ServerCommandSource source) throws CommandSyntaxException {
        // 开启全量观察只保存观察者身份，不把选择器实体复制进世界。
        ServerPlayerEntity player = source.getPlayerOrThrow();
        DebugFabric.INSTANCE.watches.watchAll(player);
        source.sendFeedback(() -> Text.literal("伤害日志已开启"), false /* broadcastToOps */);
        return 1;
    }

    static int watchTargets(ServerCommandSource source, Collection<? extends Entity> selected)
            throws CommandSyntaxException {
        // 指定目标只保存 UUID，当前选择器结果本身不跨 tick 保留。
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Set<UUID> targetIds = selected.stream().map(Entity::getUuid).collect(java.util.stream.Collectors.toSet());
        DebugFabric.INSTANCE.watches.watchTargets(player, targetIds);
        source.sendFeedback(() -> Text.literal("伤害日志已开启，已限制目标"), false /* broadcastToOps */);
        return 1;
    }

    static int watchOff(ServerCommandSource source) throws CommandSyntaxException {
        // 关闭时同时删除指定目标过滤，下一次开启从空状态开始。
        ServerPlayerEntity player = source.getPlayerOrThrow();
        DebugFabric.INSTANCE.watches.stop(player);
        source.sendFeedback(() -> Text.literal("伤害日志已关闭"), false /* broadcastToOps */);
        return 1;
    }

    static int healAll(ServerCommandSource source, Collection<? extends Entity> selected) {
        // 省略模式与 all literal 共用同一恢复编排。
        return heal(source, selected, DebugCommands.HealMode.ALL);
    }

    static int heal(ServerCommandSource source, Collection<? extends Entity> selected,
                    DebugCommands.HealMode mode) {
        // 每个目标独立提交；单个宿主拒绝不会把其他实体误报为成功。
        int successCount = 0;
        for (Entity entity : selected) {
            if (!(entity instanceof LivingEntity living)) {
                source.sendError(Text.literal("目标不是活体：" + entity.getName().getString()));
                continue;
            }
            if (healOne(source, living, mode)) {
                successCount++;
            }
        }
        return successCount;
    }

    static int healLimb(ServerCommandSource source, Collection<? extends Entity> selected,
                        String partId, Double amount) {
        // 部位恢复只允许实际构型中的部位 ID，数值由 Framework 再按上限裁剪。
        int successCount = 0;
        for (Entity entity : selected) {
            if (entity instanceof LivingEntity living && healOneLimb(source, living, partId, amount)) {
                successCount++;
            }
        }
        return successCount;
    }

    static void printSnapshot(ServerPlayerEntity viewer, LivingEntity target) {
        // 右键只读已加载实体；未绑定实体明确显示无身体，不创建临时快照。
        if (!viewer.hasPermissionLevel(PERMISSION_LEVEL)) {
            viewer.sendMessage(Text.literal("肢体透视镜需要权限等级 2"), true /* overlay */);
            return;
        }
        try {
            Optional<BodySnapshot> snapshot = DebugBodyAccess.inspect(target);
            if (snapshot.isEmpty()) {
                viewer.sendMessage(Text.literal("目标无身体"), false /* overlay */);
                return;
            }
            viewer.sendMessage(Text.literal(DebugSnapshotText.full(snapshot.get())), false /* overlay */);
        } catch (RuntimeException failure) {
            report(viewer, failure);
        }
    }

    private static DebugFabric INSTANCE;

    private void tick(MinecraftServer server) {
        // 只在手持透视镜且达到刷新边界时读取目标，避免每 tick 触碰身体宿主。
        tickCounter++;
        aggroLinks.tick(server);
        if (tickCounter % LENS_REFRESH_TICKS != 0) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (isHoldingLens(player)) {
                sendLensState(player);
            }
        }
    }

    private void sendLensState(ServerPlayerEntity player) {
        // 权限不足时清空旧显示并提示一次，避免客户端继续看到上一个目标。
        if (!player.hasPermissionLevel(PERMISSION_LEVEL)) {
            sendEmptyLensState(player);
            player.sendMessage(Text.literal("肢体透视镜需要权限等级 2"), true /* overlay */);
            return;
        }
        LivingEntity target = DebugTargeting.entity(player);
        if (target == null) {
            sendEmptyLensState(player);
            return;
        }
        try {
            Optional<BodySnapshot> snapshot = DebugBodyAccess.inspect(target);
            sendLensState(player, target, snapshot);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "透视镜读取失败 subject=" + target.getUuidAsString(), failure);
            sendLensState(player, target, Optional.empty());
        }
    }

    private static void sendLensState(ServerPlayerEntity player, LivingEntity target,
                                      Optional<BodySnapshot> snapshot) {
        // 包只包含当前目标和一次完整快照，客户端不参与身体选择或数值计算。
        var buffer = PacketByteBufs.create();
        buffer.writeBoolean(true /* hasTarget */);
        buffer.writeString(target.getDisplayName().getString(), MAX_TARGET_NAME_LENGTH);
        buffer.writeBoolean(snapshot.isPresent());
        snapshot.ifPresent(body -> buffer.writeByteArray(body.toByteArray()));
        ServerPlayNetworking.send(player, LENS_STATE, buffer);
    }

    private static void sendEmptyLensState(ServerPlayerEntity player) {
        // 没有准星目标时让客户端清空旧快照。
        var buffer = PacketByteBufs.create();
        buffer.writeBoolean(false /* hasTarget */);
        ServerPlayNetworking.send(player, LENS_STATE, buffer);
    }

    private static boolean isHoldingLens(ServerPlayerEntity player) {
        // 主手或副手均视为手持，物品注册表对象保持唯一。
        return player.getMainHandStack().isOf(DebugItems.LIMB_LENS)
                || player.getOffHandStack().isOf(DebugItems.LIMB_LENS);
    }

    static void useAggroTarget(ServerPlayerEntity player, MobEntity target) {
        // 第一个合法生物进入选择状态，第二个生物完成仇恨覆盖。
        AggroLinkRegistry registry = DebugFabric.INSTANCE.aggroLinks;
        Optional<MobEntity> selected = registry.selected(player);
        if (selected.isEmpty()) {
            registry.select(player, target);
            sendAggroMessage(player, "已选中 " + target.getName().getString() + "，请再选择目标");
            return;
        }
        MobEntity source = selected.get();
        if (source == target) {
            registry.clearSelection(player);
            sendAggroMessage(player, "已取消选择");
            return;
        }
        AggroLinkMode mode = player.isSneaking() ? AggroLinkMode.ONEWAY : AggroLinkMode.MUTUAL;
        registry.link(player.getServer(), source, target, mode);
        registry.clearSelection(player);
        if (mode == AggroLinkMode.MUTUAL) {
            sendAggroMessage(player, source.getName().getString() + " ⇄ "
                    + target.getName().getString() + " 互相仇恨");
            return;
        }
        sendAggroMessage(player, source.getName().getString() + " → "
                + target.getName().getString() + " 单向仇恨");
    }

    static void cancelAggroSelection(ServerPlayerEntity player) {
        // 空挥取消只在确有选择时提示，普通使用不会刷屏。
        Optional<MobEntity> selected = DebugFabric.INSTANCE.aggroLinks.selected(player);
        if (selected.isEmpty()) {
            return;
        }
        DebugFabric.INSTANCE.aggroLinks.clearSelection(player);
        sendAggroMessage(player, "已取消选择");
    }

    static boolean hasAggroSelection(ServerPlayerEntity player) {
        return DebugFabric.INSTANCE.aggroLinks.selected(player).isPresent();
    }

    static int setAggro(ServerCommandSource source, Entity sourceEntity, Entity targetEntity,
                        AggroLinkMode mode) {
        if (!(sourceEntity instanceof MobEntity sourceMob)) {
            source.sendError(Text.literal("A 不是可攻击生物"));
            return 0;
        }
        if (!(targetEntity instanceof MobEntity targetMob)) {
            source.sendError(Text.literal("B 不是可攻击生物"));
            return 0;
        }
        if (sourceMob == targetMob) {
            source.sendError(Text.literal("A 和 B 不能是同一个生物"));
            return 0;
        }
        if (!AggroLinkRegistry.canAttack(sourceMob)) {
            source.sendError(Text.literal("A 该生物无法攻击"));
            return 0;
        }
        if (!AggroLinkRegistry.canAttack(targetMob)) {
            source.sendError(Text.literal("B 该生物无法攻击"));
            return 0;
        }
        DebugFabric.INSTANCE.aggroLinks.link(source.getServer(), sourceMob, targetMob, mode);
        String relation = mode == AggroLinkMode.MUTUAL ? " ⇄ " : " → ";
        String suffix = mode == AggroLinkMode.MUTUAL ? " 互相仇恨" : " 单向仇恨";
        source.sendFeedback(() -> Text.literal(sourceMob.getName().getString() + relation
                + targetMob.getName().getString() + suffix), false /* broadcastToOps */);
        return 1;
    }

    static int clearAggro(ServerCommandSource source, Collection<? extends Entity> targets) {
        int removed = DebugFabric.INSTANCE.aggroLinks.clearTargets(source.getServer(), targets);
        source.sendFeedback(() -> Text.literal("已清除 " + removed + " 条仇恨覆盖"),
                false /* broadcastToOps */);
        return removed;
    }

    static int clearAllAggro(ServerCommandSource source) {
        int removed = DebugFabric.INSTANCE.aggroLinks.clearAll(source.getServer());
        source.sendFeedback(() -> Text.literal("已清除 " + removed + " 条仇恨覆盖"),
                false /* broadcastToOps */);
        return removed;
    }

    private static void sendAggroMessage(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), true /* overlay */);
    }

    private static boolean healOne(ServerCommandSource source, LivingEntity target, DebugCommands.HealMode mode) {
        // 先读取一次身体，再按模式顺序提交独立的 HP、疲劳和真元事务。
        try {
            BodySnapshot initial = requireBody(target);
            switch (mode) {
                case ALL -> healAllState(source, target, initial);
                case HEALTH, LIMBS -> submitHp(target, initial, HealPlan.HpScope.PARTS);
                case STAMINA -> submitFatigue(target, initial);
                case ZHENYUAN -> fillZhenyuan(source, target);
            }
            source.sendFeedback(
                    () -> Text.literal("恢复完成：" + target.getName().getString()),
                    false /* broadcastToOps */);
            return true;
        } catch (RuntimeException failure) {
            report(source, target, failure);
            return false;
        }
    }

    private static void healAllState(ServerCommandSource source, LivingEntity target, BodySnapshot initial) {
        // all 先恢复全部 HP，再清疲劳，最后复用 Human 已有真元 fill 命令。
        BodySnapshot afterHp = submitHp(target, initial, HealPlan.HpScope.ALL_MODULES);
        BodySnapshot afterFatigue = submitFatigue(target, afterHp);
        fillZhenyuan(source, target);
        if (afterFatigue.getRevision() < afterHp.getRevision()) {
            throw new IllegalStateException("恢复事务版本未递增");
        }
    }

    private static BodySnapshot submitHp(LivingEntity target, BodySnapshot body, HealPlan.HpScope scope) {
        // 空剂量表示已经满值，不提交违反协议最小剂量的空命令。
        List<Healing> doses = HealPlan.hpDoses(body, scope);
        if (doses.isEmpty()) {
            return body;
        }
        HealBody request = HealBody.newBuilder()
                .setSourceId(HEALTH_SOURCE)
                .addAllDoses(doses)
                .build();
        BodyCommand command = BodyCommand.newBuilder()
                .setHealBody(request)
                .build();
        return DebugBodyAccess.submit(target, body, command).orElseThrow(
                () -> new IllegalStateException("目标身体在提交时失去宿主"));
    }

    private static BodySnapshot submitFatigue(LivingEntity target, BodySnapshot body) {
        // 体力恢复使用同一身体版本，避免以旧快照覆盖刚完成的 HP 恢复。
        List<FatigueRelief> doses = HealPlan.fatigueDoses(body);
        if (doses.isEmpty()) {
            return body;
        }
        RestoreFatigue request = RestoreFatigue.newBuilder()
                .setSourceId(HEALTH_SOURCE)
                .addAllDoses(doses)
                .build();
        BodyCommand command = BodyCommand.newBuilder()
                .setRestoreFatigue(request)
                .build();
        return DebugBodyAccess.submit(target, body, command).orElseThrow(
                () -> new IllegalStateException("目标身体在提交时失去宿主"));
    }

    private static void fillZhenyuan(ServerCommandSource source, LivingEntity target) {
        // 玩家复用 Human 命令，类人实体复用其宿主的受限调试事务入口。
        if (target instanceof ServerPlayerEntity player) {
            ServerCommandSource delegated = source.withEntity(player);
            int result = player.getServer().getCommandManager().executeWithPrefix(delegated, ZHENYUAN_COMMAND);
            if (result == 0) {
                throw new IllegalStateException("真元恢复被宿主拒绝");
            }
            return;
        }
        if (!HumanoidHealthHost.fillZhenyuan(target)) {
            throw new IllegalStateException("类人实体真元恢复被宿主拒绝");
        }
    }

    private static boolean healOneLimb(ServerCommandSource source, LivingEntity target,
                                       String partId, Double amount) {
        // 部位命令按目标当前构型校验并提交单个 HP 账户。
        try {
            BodySnapshot body = requireBody(target);
            Optional<Healing> dose = HealPlan.limbDose(body, partId, amount);
            if (dose.isEmpty()) {
                throw new IllegalArgumentException("部位不存在或已经满值：" + partId);
            }
            Healing healing = dose.get();
            HealBody request = HealBody.newBuilder()
                    .setSourceId(HEALTH_SOURCE)
                    .addDoses(healing)
                    .build();
            BodyCommand command = BodyCommand.newBuilder()
                    .setHealBody(request)
                    .build();
            DebugBodyAccess.submit(target, body, command).orElseThrow(
                    () -> new IllegalStateException("目标身体在提交时失去宿主"));
            source.sendFeedback(() -> Text.literal("部位恢复完成：" + target.getName().getString()
                    + " / " + partId), false /* broadcastToOps */);
            return true;
        } catch (RuntimeException failure) {
            report(source, target, failure);
            return false;
        }
    }

    private static BodySnapshot requireBody(LivingEntity target) {
        // 命令目标必须已被身体宿主认领，不为无身体实体创建调试账本。
        return DebugBodyAccess.inspect(target)
                .orElseThrow(() -> new IllegalArgumentException("目标无身体：" + target.getName().getString()));
    }

    private void recordDamage(VanillaDamageFabric.DamageEvent event) {
        // 原版结算提交后再向所有在线观察者发送，日志不会影响伤害结果。
        for (ServerPlayerEntity viewer : event.target().getServer().getPlayerManager().getPlayerList()) {
            if (!watches.accepts(viewer, event)) {
                continue;
            }
            try {
                DamageLogEntry entry = damageLogEntry(viewer, event);
                viewer.sendMessage(Text.literal(DamageLogFormatter.format(entry)), false /* overlay */);
            } catch (RuntimeException failure) {
                LOGGER.log(Level.WARNING, "伤害日志格式化失败", failure);
            }
        }
    }

    static DamageLogEntry damageLogEntry(ServerPlayerEntity viewer,
                                         VanillaDamageFabric.DamageEvent event) {
        // 命中模块先映射到所属部位，再从提交后快照读取剩余 HP 与上限。
        var result = event.result();
        BodySnapshot body = result.body();
        var input = event.input();
        HitPart hit = hitPart(body, input.hit());
        double remainingHp = remainingHp(body, hit.id());
        double maximumHp = maximumHp(body, hit.id());
        String attacker = label(event.attacker(), viewer);
        String target = label(event.target(), viewer);
        String kind = kindLabel(input.kind());
        String source = event.source().getTypeRegistryEntry().getKey()
                .map(key -> key.getValue().toString()).orElse("minecraft:unknown");
        var resolution = result.health().getDamageResolution();
        double requestedHp = resolution.getLossesList().stream()
                .filter(loss -> loss.getTarget().hasPartId() && loss.getTarget().getPartId().equals(hit.id()))
                .mapToDouble(loss -> loss.getHp()).sum();
        double availableHp = remainingHp(result.before(), hit.id());
        double appliedHp = Math.min(requestedHp, availableHp);
        var participants = new DamageLogEntry.Participants(attacker, target, kind);
        var dose = new DamageLogEntry.Hit(hit.name(), input.damageAfterEffects(), input.rawDamage(), source, appliedHp);
        var health = new DamageLogEntry.Health(remainingHp, maximumHp);
        return new DamageLogEntry(participants, dose, health, DamageSpilloverLog.format(resolution, body));
    }

    private static String label(LivingEntity entity, ServerPlayerEntity viewer) {
        // 观察者本人统一显示“你”，其他实体使用当前显示名。
        if (entity == null) {
            return "环境";
        }
        if (entity.getUuid().equals(viewer.getUuid())) {
            return "你";
        }
        return entity.getDisplayName().getString();
    }

    private static HitPart hitPart(BodySnapshot body, Optional<HealthTarget> target) {
        // HumanDamage 通常命中部位；组织或器官命中时沿构型反查所属部位。
        if (target.isEmpty()) {
            return new HitPart("", "未定部位");
        }
        HealthTarget selected = target.get();
        String partId = switch (selected.getModuleCase()) {
            case PART_ID -> selected.getPartId();
            case TISSUE_ID -> body.getStructure().getTissuesList().stream()
                    .filter(definition -> definition.getId().equals(selected.getTissueId()))
                    .map(dev.kizuna.meridian.protocol.pb.BodyMessages.TissueDefinition::getPartId)
                    .findFirst().orElse("");
            case ORGAN_ID -> body.getStructure().getOrgansList().stream()
                    .filter(definition -> definition.getId().equals(selected.getOrganId()))
                    .map(dev.kizuna.meridian.protocol.pb.BodyMessages.OrganDefinition::getPartId)
                    .findFirst().orElse("");
            case MODULE_NOT_SET -> "";
        };
        String name = body.getStructure().getPartsList().stream()
                .filter(definition -> definition.getId().equals(partId))
                .map(dev.kizuna.meridian.protocol.pb.BodyMessages.PartDefinition::getDisplayName)
                .findFirst().orElse(partId.isBlank() ? "未定部位" : partId);
        return new HitPart(partId, name);
    }

    private static double remainingHp(BodySnapshot body, String partId) {
        // 命中部位不存在时用零值表达环境或未定命中，不读取原版血条。
        return body.getPartStatesList().stream()
                .filter(state -> state.getId().equals(partId))
                .mapToDouble(state -> state.getHp()).findFirst().orElse(0);
    }

    private static double maximumHp(BodySnapshot body, String partId) {
        // 部位上限必须来自提交后实际结构，避免改造后沿用旧模板值。
        return body.getStructure().getPartsList().stream()
                .filter(definition -> definition.getId().equals(partId))
                .mapToDouble(definition -> definition.getMaxHp()).findFirst().orElse(0);
    }

    private static String kindLabel(DamageKind kind) {
        // 日志保留伤害机制的中文短名，具体来源 ID 另行输出。
        return switch (kind) {
            case BLUNT -> "钝击";
            case CUTTING -> "切割";
            case PIERCING -> "穿刺";
            case HEAT -> "灼热";
            case COLD -> "寒冷";
            case ELECTRIC -> "电击";
            case CORROSIVE -> "腐蚀";
            case TOXIC -> "毒素";
            case CUSTOM -> "自定义";
            default -> "未知";
        };
    }

    private static void report(ServerPlayerEntity player, RuntimeException failure) {
        // 观察失败保留服务端日志，同时给管理员一个不泄露堆栈的短提示。
        LOGGER.log(Level.WARNING, "调试操作失败 player=" + player.getUuidAsString(), failure);
        player.sendMessage(Text.literal("调试操作失败：" + failure.getMessage()), false /* overlay */);
    }

    private static void report(ServerCommandSource source, LivingEntity target, RuntimeException failure) {
        // 命令失败不回滚其他目标，只报告当前实体的固定错误文本。
        LOGGER.log(Level.WARNING, "调试恢复失败 subject=" + target.getUuidAsString(), failure);
        source.sendError(Text.literal("恢复失败：" + failure.getMessage()));
    }

    private record HitPart(String id, String name) {
        // 命中 ID 与显示名必须来自同一份身体构型。
    }
}
