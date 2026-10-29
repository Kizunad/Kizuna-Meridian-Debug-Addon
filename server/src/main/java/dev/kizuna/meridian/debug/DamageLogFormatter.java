package dev.kizuna.meridian.debug;

import java.util.Locale;

/** 统一伤害日志的一行格式，聊天和单元测试共用。 */
public final class DamageLogFormatter {
    private DamageLogFormatter() {
        // 格式化不持有任何服务器状态。
    }

    public static String format(DamageLogEntry entry) {
        // 用固定 Locale 和统一的中文标点保证日志可复制、可比对。
        var participants = entry.participants();
        var hit = entry.hit();
        var health = entry.health();
        String part = hit.part();
        return String.format(Locale.ROOT,
                "[伤害] %s → %s(%s) %s −%.1f%s（护甲后 %.1f，原版 %.1f）来源：%s；%s剩余 %.1f/%.1f",
                participants.attacker(), participants.target(), participants.kind(),
                part, hit.appliedHp(), entry.spillover(), hit.damageAfterArmor(),
                hit.rawDamage(), hit.source(), part, health.remainingHp(), health.maximumHp());
    }
}
