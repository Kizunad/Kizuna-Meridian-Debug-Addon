package dev.kizuna.meridian.debug.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.debug.DamageLogEntry;
import dev.kizuna.meridian.debug.DamageLogFormatter;
import dev.kizuna.meridian.debug.HealPlan;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.impl.networking.ChannelInfoHolder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 调试库在真实 Fabric 服务端上的身体读取、伤害观察和恢复回归。 */
public final class DebugAddonGameTest implements FabricGameTest {
    private static final int SPAWN_X = 2;
    private static final int SPAWN_Y = 2;
    private static final int SPAWN_Z = 2;
    private static final float DAMAGE_AMOUNT = 3;
    private static final int JOIN_INVULNERABILITY_TICKS = 60;
    private static final int COMMAND_PERMISSION_LEVEL = 4;
    private static final List<VanillaDamageFabric.DamageEvent> DAMAGE_EVENTS = new ArrayList<>();
    private static boolean observerInstalled;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void limbLensReadsHumanoidParts(TestContext context) {
        // 透视镜读取实际类人宿主快照，部位名单必须来自当前身体构型。
        LivingEntity villager = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(villager);
        BodySnapshot body = DebugBodyAccess.inspect(villager).orElseThrow();
        context.assertTrue(!body.getStructure().getPartsList().isEmpty(), "类人身体没有部位构型");
        context.assertTrue(HealPlan.partIds(body).equals(body.getStructure().getPartsList().stream()
                .map(definition -> definition.getId()).toList()), "部位补全没有读取当前构型");
        context.assertTrue(DebugSnapshotText.full(body).contains(body.getId()), "完整快照没有包含身体 ID");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void playerAndHumanoidDamageExposeBothDirections(TestContext context) {
        // 两个方向都走真实伤害链，观察事件的前后快照和固定命中部位必须同时存在。
        installObserver();
        DAMAGE_EVENTS.clear();
        var server = context.getWorld().getServer();
        boolean originalPvp = server.isPvpEnabled();
        server.setPvpEnabled(true /* pvpEnabled */);
        DebugTestPlayer online = new DebugTestPlayer(context);
        ServerPlayerEntity player = online.player;
        LivingEntity villager = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(villager);
        context.waitAndRun(JOIN_INVULNERABILITY_TICKS + 1, () -> {
            try (online) {
                BodySnapshot playerBefore = DebugBodyAccess.inspect(player).orElseThrow();
                BodySnapshot villagerBefore = DebugBodyAccess.inspect(villager).orElseThrow();
                villager.damage(villager.getDamageSources().playerAttack(player), DAMAGE_AMOUNT);
                player.setInvulnerable(false /* invulnerable */);
                player.getAbilities().invulnerable = false;
                player.timeUntilRegen = 0;
                boolean playerDamaged = player.damage(player.getDamageSources().mobAttack(villager), DAMAGE_AMOUNT);
                context.assertTrue(playerDamaged, "玩家实体拒绝了类人实体伤害");
                long playerAttackCount = damageCount(player, villager);
                long villagerAttackCount = damageCount(villager, player);
                context.assertTrue(playerAttackCount == 1 && villagerAttackCount == 1,
                        "玩家与类人实体没有各自产生一条伤害事实：玩家方向=" + playerAttackCount
                                + "，类人方向=" + villagerAttackCount);
                assertDamage(context, player, villager, playerBefore, villagerBefore);
                context.complete();
            } finally {
                server.setPvpEnabled(originalPvp);
            }
        });
    }

    private static long damageCount(LivingEntity attacker, LivingEntity target) {
        // 只统计本测试实体对之间的事件，隔离同一批次其他 GameTest 的伤害。
        return DAMAGE_EVENTS.stream()
                .filter(event -> event.attacker() == attacker && event.target() == target)
                .count();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void allHealRestoresHumanoidBody(TestContext context) {
        // all 同时恢复部位、内部组织、器官、疲劳和经脉内腔。
        // 结果重新读取权威快照确认。
        LivingEntity villager = context.spawnEntity(EntityType.VILLAGER, SPAWN_X, SPAWN_Y, SPAWN_Z);
        disableAi(villager);
        BodySnapshot before = DebugBodyAccess.inspect(villager).orElseThrow();
        villager.damage(villager.getDamageSources().generic(), DAMAGE_AMOUNT);
        ServerCommandSource source = context.getWorld().getServer().getCommandSource()
                .withWorld(context.getWorld())
                .withPosition(Vec3d.ofCenter(villager.getBlockPos()))
                .withLevel(COMMAND_PERMISSION_LEVEL);
        int healed = DebugFabric.heal(source, List.of(villager), DebugCommands.HealMode.ALL);
        BodySnapshot after = DebugBodyAccess.inspect(villager).orElseThrow();
        context.assertTrue(healed == 1, "类人实体 all 恢复没有成功");
        assertFullHealth(context, after);
        context.assertTrue(after.getRevision() > before.getRevision(), "恢复没有提交身体事务");
        context.complete();
    }

    private static void assertDamage(TestContext context, ServerPlayerEntity player, LivingEntity villager,
                                     BodySnapshot playerBefore, BodySnapshot villagerBefore) {
        // 观察结果按攻击者和受击者 UUID 匹配，避免依赖事件回调顺序。
        VanillaDamageFabric.DamageEvent playerHit = DAMAGE_EVENTS.stream()
                .filter(event -> event.attacker() == player && event.target() == villager)
                .findFirst().orElseThrow();
        VanillaDamageFabric.DamageEvent villagerHit = DAMAGE_EVENTS.stream()
                .filter(event -> event.attacker() == villager && event.target() == player)
                .findFirst().orElseThrow();
        assertCommittedDamage(context, playerHit, villagerBefore, player);
        assertCommittedDamage(context, villagerHit, playerBefore, player);
        String firstLog = DamageLogFormatter.format(DebugFabric.damageLogEntry(player, playerHit));
        String secondLog = DamageLogFormatter.format(DebugFabric.damageLogEntry(player, villagerHit));
        context.assertTrue(firstLog.contains("你 →"), "玩家攻击日志没有显示攻击者身份：" + firstLog);
        context.assertTrue(
                secondLog.contains("→ 你"),
                "类人攻击日志没有显示受击者身份：" + secondLog);
    }

    private static void assertCommittedDamage(TestContext context, VanillaDamageFabric.DamageEvent event,
                                              BodySnapshot before, ServerPlayerEntity viewer) {
        // 前后快照沿同一命中模块对比，确保日志事实来自身体提交而非原版血条猜测。
        context.assertTrue(event.result().before() != null && event.result().body() != null, "伤害事件缺少身体快照");
        context.assertTrue(event.input().hit().isPresent(), "身体伤害事件缺少命中部位");
        context.assertTrue(event.input().damageAfterEffects() > 0 && event.input().rawDamage() > 0, "伤害剂量不是正数");
        String partId = partId(event.result().body(), event.input().hit().orElseThrow());
        double oldHp = partHp(before, partId);
        double newHp = partHp(event.result().body(), partId);
        context.assertTrue(newHp < oldHp, "命中部位血量没有下降");
        DamageLogEntry logEntry = DebugFabric.damageLogEntry(viewer, event);
        context.assertTrue(logEntry.health().remainingHp() == newHp, "日志剩余血量没有读取提交后快照");
    }

    private static void assertFullHealth(TestContext context, BodySnapshot body) {
        // 每个生命模块都与结构上限相等，所有疲劳账户归零。
        for (var definition : body.getStructure().getPartsList()) {
            context.assertTrue(partHp(body, definition.getId()) == definition.getMaxHp(),
                    "部位未恢复满值：" + definition.getId());
        }
        for (var definition : body.getStructure().getTissuesList()) {
            var state = body.getTissueStatesList().stream()
                    .filter(item -> item.getId().equals(definition.getId())).findFirst().orElseThrow();
            context.assertTrue(state.getHp() == definition.getMaxHp() && state.getFatigue() == 0,
                    "组织未恢复满值：" + definition.getId());
        }
        for (var definition : body.getStructure().getOrgansList()) {
            var state = body.getOrganStatesList().stream()
                    .filter(item -> item.getId().equals(definition.getId())).findFirst().orElseThrow();
            context.assertTrue(state.getHp() == definition.getMaxHp() && state.getFatigueJoules() == 0,
                    "器官未恢复满值：" + definition.getId());
        }
        for (var state : body.getMeridianStatesList()) {
            context.assertTrue(state.getFatigue() == 0, "经脉疲劳未清除：" + state.getId());
        }
    }

    private static String partId(BodySnapshot body, HealthTarget target) {
        return switch (target.getModuleCase()) {
            case PART_ID -> target.getPartId();
            case TISSUE_ID -> body.getStructure().getTissuesList().stream()
                    .filter(definition -> definition.getId().equals(target.getTissueId()))
                    .map(definition -> definition.getPartId()).findFirst().orElseThrow();
            case ORGAN_ID -> body.getStructure().getOrgansList().stream()
                    .filter(definition -> definition.getId().equals(target.getOrganId()))
                    .map(definition -> definition.getPartId()).findFirst().orElseThrow();
            case MODULE_NOT_SET -> throw new IllegalStateException("身体命中缺少模块");
        };
    }

    private static double partHp(BodySnapshot body, String partId) {
        return body.getPartStatesList().stream()
                .filter(state -> state.getId().equals(partId))
                .mapToDouble(state -> state.getHp()).findFirst().orElseThrow();
    }

    private static void installObserver() {
        // Vanilla-Damage 观察器只注册一次，所有 GameTest 在同一个服务进程内共享收集器。
        if (!observerInstalled) {
            VanillaDamageFabric.registerDamageObserver(DAMAGE_EVENTS::add);
            observerInstalled = true;
        }
    }

    private static void disableAi(LivingEntity entity) {
        // 固定类人实体位置，避免 AI 在伤害前后改变准星或宿主查询对象。
        if (entity instanceof MobEntity mob) {
            mob.setAiDisabled(true /* aiDisabled */);
        }
    }

    static final class DebugTestPlayer implements AutoCloseable {
        private static final int NAME_ID_LENGTH = 8;
        private final RecordingConnection connection;
        final ServerPlayerEntity player;

        DebugTestPlayer(TestContext context) {
            // 通过真实登录事件绑定 Human 身体，补齐服务端推送所需的客户端频道。
            var world = context.getWorld();
            var server = world.getServer();
            UUID playerId = UUID.randomUUID();
            GameProfile profile = new GameProfile(playerId,
                    "debug_" + playerId.toString().substring(0, NAME_ID_LENGTH));
            player = new ServerPlayerEntity(server, world, profile);
            connection = new RecordingConnection();
            var channels = ((ChannelInfoHolder) (ClientConnection) connection).getPendingChannelsNames();
            channels.addAll(List.of(new Identifier("meridian", "body_view"),
                    new Identifier("meridian", "gathering_response"),
                    new Identifier("meridian", "opening_response"),
                    new Identifier("meridian_progression", "event"),
                    new Identifier("kizuna_players_animation", "play"),
                    new Identifier("kizuna_players_animation", "stop")));
            server.getPlayerManager().onPlayerConnect(connection, player);
            player.changeGameMode(GameMode.SURVIVAL);
        }

        List<String> chat() {
            // 只读视图，按发送顺序列出服务端发给该玩家的聊天栏文字。
            return List.copyOf(connection.chat);
        }

        @Override
        public void close() {
            // 走真实断线入口，让 Human 和观察状态清理玩家身份。
            player.networkHandler.onDisconnected(Text.literal("GameTest 完成"));
            connection.disconnect(Text.literal("GameTest 完成"));
        }
    }

    private static final class RecordingConnection extends ClientConnection {
        private final List<String> chat = new ArrayList<>();
        private boolean open = true;

        private RecordingConnection() {
            // 测试连接不建立真实 socket，但保留 Fabric 登录与包发送路径。
            super(NetworkSide.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
            // 其余网络包由宿主登录流程验证，这里只留下聊天栏文字供调试日志断言。
            record(packet);
        }

        @Override
        public void send(Packet<?> packet, PacketCallbacks callbacks) {
            // 立即成功回调，避免异步发送阻塞 GameTest 主线程。
            record(packet);
            if (callbacks != null) {
                callbacks.onSuccess();
            }
        }

        private void record(Packet<?> packet) {
            // 动作栏提示不进聊天栏，不计入调试日志行数。
            if (packet instanceof GameMessageS2CPacket message && !message.overlay()) {
                chat.add(message.content().getString());
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public SocketAddress getAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public void disconnect(Text reason) {
            open = false;
        }
    }
}
