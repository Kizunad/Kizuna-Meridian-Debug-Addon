package dev.kizuna.meridian.debug.fabric;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** 在线管理员的跃步观察状态；默认只输出观察者自身。 */
final class LeapWatchRegistry {
    private final Map<UUID, Set<UUID>> targets = new HashMap<>();

    void watchAll(ServerPlayer player) {
        targets.put(player.getUUID(), Set.of());
    }

    void watchTargets(ServerPlayer player, Set<UUID> targetIds) {
        targets.put(player.getUUID(), Set.copyOf(targetIds));
    }

    void stop(ServerPlayer player) {
        targets.remove(player.getUUID());
    }

    void clear(UUID playerId) {
        targets.remove(playerId);
    }

    boolean accepts(ServerPlayer viewer, LivingEntity actor) {
        Set<UUID> selected = targets.get(viewer.getUUID());
        if (selected == null) {
            return false;
        }
        if (selected.isEmpty()) {
            return actor.getUUID().equals(viewer.getUUID());
        }
        return selected.contains(actor.getUUID());
    }
}
