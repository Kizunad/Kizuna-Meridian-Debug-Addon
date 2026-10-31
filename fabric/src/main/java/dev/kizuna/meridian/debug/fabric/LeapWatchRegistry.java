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

    void watchTargets(ServerPlayer player, Set<UUID> targetIds) {
        targets.put(player.getUUID(), Set.copyOf(targetIds));
    }

    int unwatch(ServerPlayer player, Set<UUID> targetIds) {
        // 按目标关闭：只删去这些目标，删空后不再保留观察状态。返回剩余目标数。
        Set<UUID> selected = targets.get(player.getUUID());
        if (selected == null) {
            return 0;
        }
        Set<UUID> remaining = new HashSet<>(selected);
        remaining.removeAll(targetIds);
        if (remaining.isEmpty()) {
            targets.remove(player.getUUID());
            return 0;
        }
        targets.put(player.getUUID(), Set.copyOf(remaining));
        return remaining.size();
    }

    void clear(UUID playerId) {
        targets.remove(playerId);
    }

    boolean accepts(ServerPlayer viewer, LivingEntity actor) {
        // 只输出目标集合里的实体；省略目标开启时集合只有观察者本人。
        Set<UUID> selected = targets.get(viewer.getUUID());
        if (selected == null) {
            return false;
        }
        return selected.contains(actor.getUUID());
    }
}
