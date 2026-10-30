package dev.kizuna.meridian.debug.fabric;

import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 在线管理员的跃步观察状态；默认只输出观察者自身。 */
final class LeapWatchRegistry {
    private final Map<UUID, Set<UUID>> targets = new HashMap<>();

    void watchAll(ServerPlayerEntity player) {
        targets.put(player.getUuid(), Set.of());
    }

    void watchTargets(ServerPlayerEntity player, Set<UUID> targetIds) {
        targets.put(player.getUuid(), Set.copyOf(targetIds));
    }

    void stop(ServerPlayerEntity player) {
        targets.remove(player.getUuid());
    }

    void clear(UUID playerId) {
        targets.remove(playerId);
    }

    boolean accepts(ServerPlayerEntity viewer, LivingEntity actor) {
        Set<UUID> selected = targets.get(viewer.getUuid());
        if (selected == null) {
            return false;
        }
        if (selected.isEmpty()) {
            return actor.getUuid().equals(viewer.getUuid());
        }
        return selected.contains(actor.getUuid());
    }
}
