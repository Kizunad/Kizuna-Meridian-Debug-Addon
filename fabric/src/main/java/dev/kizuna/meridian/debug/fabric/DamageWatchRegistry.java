package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 在线管理员的伤害观察状态；断线时由宿主显式清理。 */
final class DamageWatchRegistry {
    private final Map<UUID, Set<UUID>> targets = new HashMap<>();

    void watchAll(ServerPlayerEntity player) {
        // 空集合表示只按观察者自身过滤，不限制额外目标。
        targets.put(player.getUuid(), Set.of());
    }

    void watchTargets(ServerPlayerEntity player, Set<UUID> targetIds) {
        // 复制选择器结果，后续实体集合变化不会修改已提交的观察条件。
        targets.put(player.getUuid(), Set.copyOf(targetIds));
    }

    void stop(ServerPlayerEntity player) {
        // 下线和显式关闭都删除状态，不把观察权限保存进世界。
        targets.remove(player.getUuid());
    }

    void clear(UUID playerId) {
        // 连接结束后按 UUID 清理，避免保留 ServerPlayerEntity 引用。
        targets.remove(playerId);
    }

    boolean accepts(ServerPlayerEntity viewer, VanillaDamageFabric.DamageEvent event) {
        // 只有攻击方或受击方是观察者本人时才输出；额外目标再做一次交集过滤。
        Set<UUID> selected = targets.get(viewer.getUuid());
        if (selected == null || !participates(viewer, event)) {
            return false;
        }
        if (selected.isEmpty()) {
            return true;
        }
        return selected.contains(event.target().getUuid())
                || (event.attacker() != null && selected.contains(event.attacker().getUuid()));
    }

    private static boolean participates(ServerPlayerEntity viewer, VanillaDamageFabric.DamageEvent event) {
        // 环境伤害没有攻击者，但只要玩家是受击方仍然属于自己的伤害记录。
        if (event.target().getUuid().equals(viewer.getUuid())) {
            return true;
        }
        return event.attacker() != null && event.attacker().getUuid().equals(viewer.getUuid());
    }
}
