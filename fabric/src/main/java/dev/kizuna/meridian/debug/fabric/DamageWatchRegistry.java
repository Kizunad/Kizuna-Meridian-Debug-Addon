package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** 在线管理员的伤害观察状态；断线时由宿主显式清理。 */
final class DamageWatchRegistry {
    private final Map<UUID, Set<UUID>> targets = new HashMap<>();

    void watchTargets(ServerPlayer player, Set<UUID> targetIds) {
        // 复制选择器结果，后续实体集合变化不会修改已提交的观察条件。
        targets.put(player.getUUID(), Set.copyOf(targetIds));
    }

    int unwatch(ServerPlayer player, Set<UUID> targetIds) {
        // 按目标关闭：只删去这些目标，删空后不再保留观察状态，也不把观察权限保存进世界。返回剩余目标数。
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
        // 连接结束后按 UUID 清理，避免保留 ServerPlayer 引用。
        targets.remove(playerId);
    }

    boolean accepts(ServerPlayer viewer, VanillaDamageFabric.DamageEvent event) {
        // 只有攻击方或受击方是观察者本人时才输出；目标集合再做一次交集过滤，集合只有本人时等于不限制。
        Set<UUID> selected = targets.get(viewer.getUUID());
        if (selected == null || !participates(viewer, event)) {
            return false;
        }
        return selected.contains(event.target().getUUID())
                || (event.attacker() != null && selected.contains(event.attacker().getUUID()));
    }

    private static boolean participates(ServerPlayer viewer, VanillaDamageFabric.DamageEvent event) {
        // 环境伤害没有攻击者，但只要玩家是受击方仍然属于自己的伤害记录。
        if (event.target().getUUID().equals(viewer.getUUID())) {
            return true;
        }
        return event.attacker() != null && event.attacker().getUUID().equals(viewer.getUUID());
    }
}
