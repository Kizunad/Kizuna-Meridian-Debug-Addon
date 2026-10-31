package dev.kizuna.meridian.debug.fabric;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 服务端内存中的仇恨覆盖；状态随服务端进程消失，不写入实体 NBT。 */
final class AggroLinkRegistry {
    private static final double MAX_DISTANCE = 32.0;
    private static final double MAX_DISTANCE_SQUARED = MAX_DISTANCE * MAX_DISTANCE;
    private final Map<UUID, AggroLink> links = new HashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, Integer> glowSelections = new HashMap<>();
    private final Map<UUID, Boolean> originalGlow = new HashMap<>();

    void tick(MinecraftServer server) {
        // 目标选择器可能在本 tick 改回其他目标，所以在世界 tick 后再次写入调试目标。
        Iterator<Map.Entry<UUID, AggroLink>> iterator = links.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, AggroLink> entry = iterator.next();
            Mob source = findMob(server, entry.getKey());
            Mob target = findMob(server, entry.getValue().targetId());
            if (!isValidLink(source, target)) {
                if (source != null) {
                    source.setTarget(null);
                }
                iterator.remove();
                continue;
            }
            source.setTarget(target);
        }
    }

    static boolean canAttack(Mob mob) {
        AttributeInstance attackDamage =
                mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamage == null) {
            return false;
        }
        return attackDamage.getValue() > 0;
    }

    Optional<Mob> selected(ServerPlayer player) {
        // 选中的生物已卸载或死亡时顺带清掉选择，只返回仍然有效的那只。
        Selection selection = selections.get(player.getUUID());
        if (selection == null) {
            return Optional.empty();
        }
        Mob mob = findMob(player.level().getServer(), selection.mobId());
        if (mob == null || !mob.isAlive()) {
            clearSelection(player);
            return Optional.empty();
        }
        return Optional.of(mob);
    }

    void select(ServerPlayer player, Mob mob) {
        clearSelection(player);
        UUID mobId = mob.getUUID();
        Integer selectedCount = glowSelections.get(mobId);
        int nextCount;
        if (selectedCount == null) {
            nextCount = 1;
        } else {
            nextCount = selectedCount + 1;
        }
        glowSelections.put(mobId, nextCount);
        if (nextCount == 1) {
            originalGlow.put(mobId, mob.isCurrentlyGlowing());
        }
        mob.setGlowingTag(true /* glowing */);
        selections.put(player.getUUID(), new Selection(mobId));
    }

    void link(MinecraftServer server, Mob source, Mob target, AggroLinkMode mode) {
        UUID sourceId = source.getUUID();
        UUID targetId = target.getUUID();
        Set<UUID> affected = Set.of(sourceId, targetId);
        clearLinksInvolving(server, affected);
        source.setTarget(target);
        links.put(sourceId, new AggroLink(targetId));
        if (mode == AggroLinkMode.MUTUAL) {
            target.setTarget(source);
            links.put(targetId, new AggroLink(sourceId));
        }
    }

    void clearSelection(ServerPlayer player) {
        // 同一生物被多人选中时按计数恢复，最后一人取消后才还原它原来的发光状态。
        Selection selection = selections.remove(player.getUUID());
        if (selection == null) {
            return;
        }
        UUID mobId = selection.mobId();
        Integer remaining = glowSelections.computeIfPresent(mobId, (ignored, count) -> count - 1);
        if (remaining != null && remaining <= 0) {
            glowSelections.remove(mobId);
            Boolean wasGlowing = originalGlow.remove(mobId);
            Mob mob = findMob(player.level().getServer(), mobId);
            if (mob != null && wasGlowing != null) {
                mob.setGlowingTag(wasGlowing /* glowing */);
            }
        }
    }

    int clearAll(MinecraftServer server) {
        int removed = 0;
        for (UUID sourceId : links.keySet()) {
            Mob source = findMob(server, sourceId);
            if (source != null) {
                source.setTarget(null);
            }
            removed++;
        }
        links.clear();
        return removed;
    }

    int clearTargets(MinecraftServer server, Collection<? extends Entity> targets) {
        Set<UUID> targetIds = new HashSet<>();
        for (Entity target : targets) {
            targetIds.add(target.getUUID());
        }
        return clearLinksInvolving(server, targetIds);
    }

    void clearPlayer(ServerPlayer player) {
        clearSelection(player);
    }

    void clearRuntimeState(MinecraftServer server) {
        clearAll(server);
        selections.clear();
        glowSelections.clear();
        originalGlow.clear();
    }

    private int clearLinksInvolving(MinecraftServer server, Set<UUID> entityIds) {
        int removed = 0;
        Iterator<Map.Entry<UUID, AggroLink>> iterator = links.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, AggroLink> entry = iterator.next();
            UUID sourceId = entry.getKey();
            UUID targetId = entry.getValue().targetId();
            if (!entityIds.contains(sourceId) && !entityIds.contains(targetId)) {
                continue;
            }
            Mob source = findMob(server, sourceId);
            if (source != null) {
                source.setTarget(null);
            }
            iterator.remove();
            removed++;
        }
        return removed;
    }

    private static boolean isValidLink(Mob source, Mob target) {
        if (source == null || target == null || source == target) {
            return false;
        }
        if (!source.isAlive() || !target.isAlive()) {
            return false;
        }
        if (source.level() != target.level()) {
            return false;
        }
        return source.distanceToSqr(target) <= MAX_DISTANCE_SQUARED;
    }

    private static Mob findMob(MinecraftServer server, UUID id) {
        for (ServerLevel world : server.getAllLevels()) {
            Entity entity = world.getEntity(id);
            if (entity instanceof Mob mob) {
                return mob;
            }
        }
        return null;
    }

    private record AggroLink(UUID targetId) {
        // 覆盖只保留目标身份，目标死亡或卸载后由 tick 清除。
    }

    private record Selection(UUID mobId) {
        // 选择状态只保留玩家和实体 UUID，不写入物品 NBT。
    }
}
