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

    void watchAll(ServerPlayer player) {
        // 空集合表示只按观察者自身过滤，不限制额外目标。
        targets.put(player.getUUID(), Set.of());
    }

    void watchTargets(ServerPlayer player, Set<UUID> targetIds) {
        // 复制选择器结果，后续实体集合变化不会修改已提交的观察条件。
        targets.put(player.getUUID(), Set.copyOf(targetIds));
    }

    void stop(ServerPlayer player) {
        // 下线和显式关闭都删除状态，不把观察权限保存进世界。
        targets.remove(player.getUUID());
    }

    void clear(UUID playerId) {
        // 连接结束后按 UUID 清理，避免保留 ServerPlayer 引用。
        targets.remove(playerId);
    }

    boolean accepts(ServerPlayer viewer, VanillaDamageFabric.DamageEvent event) {
        // 只有攻击方或受击方是观察者本人时才输出；额外目标再做一次交集过滤。
        Set<UUID> selected = targets.get(viewer.getUUID());
        if (selected == null || !participates(viewer, event)) {
            return false;
        }
        if (selected.isEmpty()) {
            return true;
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
