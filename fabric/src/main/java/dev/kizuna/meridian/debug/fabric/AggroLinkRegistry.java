package dev.kizuna.meridian.debug.fabric;

import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

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
            MobEntity source = findMob(server, entry.getKey());
            MobEntity target = findMob(server, entry.getValue().targetId());
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

    static boolean canAttack(MobEntity mob) {
        EntityAttributeInstance attackDamage =
                mob.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (attackDamage == null) {
            return false;
        }
        return attackDamage.getValue() > 0;
    }

    Optional<MobEntity> selected(ServerPlayerEntity player) {
        Selection selection = selections.get(player.getUuid());
        if (selection == null) {
            return Optional.empty();
        }
        MobEntity mob = findMob(player.getServer(), selection.mobId());
        if (mob == null || !mob.isAlive()) {
            clearSelection(player);
            return Optional.empty();
        }
        return Optional.of(mob);
    }

    void select(ServerPlayerEntity player, MobEntity mob) {
        clearSelection(player);
        UUID mobId = mob.getUuid();
        Integer selectedCount = glowSelections.get(mobId);
        int nextCount;
        if (selectedCount == null) {
            nextCount = 1;
        } else {
            nextCount = selectedCount + 1;
        }
        glowSelections.put(mobId, nextCount);
        if (nextCount == 1) {
            originalGlow.put(mobId, mob.isGlowing());
        }
        mob.setGlowing(true /* glowing */);
        selections.put(player.getUuid(), new Selection(mobId));
    }

    void link(MinecraftServer server, MobEntity source, MobEntity target, AggroLinkMode mode) {
        UUID sourceId = source.getUuid();
        UUID targetId = target.getUuid();
        Set<UUID> affected = Set.of(sourceId, targetId);
        clearLinksInvolving(server, affected);
        source.setTarget(target);
        links.put(sourceId, new AggroLink(targetId));
        if (mode == AggroLinkMode.MUTUAL) {
            target.setTarget(source);
            links.put(targetId, new AggroLink(sourceId));
        }
    }

    void clearSelection(ServerPlayerEntity player) {
        Selection selection = selections.remove(player.getUuid());
        if (selection == null) {
            return;
        }
        UUID mobId = selection.mobId();
        Integer remaining = glowSelections.computeIfPresent(mobId, (ignored, count) -> count - 1);
        if (remaining != null && remaining <= 0) {
            glowSelections.remove(mobId);
            Boolean wasGlowing = originalGlow.remove(mobId);
            MobEntity mob = findMob(player.getServer(), mobId);
            if (mob != null && wasGlowing != null) {
                mob.setGlowing(wasGlowing /* glowing */);
            }
        }
    }

    int clearAll(MinecraftServer server) {
        int removed = 0;
        for (UUID sourceId : links.keySet()) {
            MobEntity source = findMob(server, sourceId);
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
            targetIds.add(target.getUuid());
        }
        return clearLinksInvolving(server, targetIds);
    }

    void clearPlayer(ServerPlayerEntity player) {
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
            MobEntity source = findMob(server, sourceId);
            if (source != null) {
                source.setTarget(null);
            }
            iterator.remove();
            removed++;
        }
        return removed;
    }

    private static boolean isValidLink(MobEntity source, MobEntity target) {
        if (source == null || target == null || source == target) {
            return false;
        }
        if (!source.isAlive() || !target.isAlive()) {
            return false;
        }
        if (source.getWorld() != target.getWorld()) {
            return false;
        }
        return source.squaredDistanceTo(target) <= MAX_DISTANCE_SQUARED;
    }

    private static MobEntity findMob(MinecraftServer server, UUID id) {
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(id);
            if (entity instanceof MobEntity mob) {
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
