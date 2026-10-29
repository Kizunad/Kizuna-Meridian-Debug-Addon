package dev.kizuna.meridian.debug;

/** 一次已提交身体伤害的展示事实。 */
public record DamageLogEntry(Participants participants, Hit hit, Health health, String spillover) {
    public DamageLogEntry {
        // 伤害日志只接受有限的正数和非负剩余值，避免聊天栏出现不可读数字。
        if (!Double.isFinite(hit.damageAfterArmor()) || hit.damageAfterArmor() < 0
                || !Double.isFinite(hit.rawDamage()) || hit.rawDamage() < 0
                || !Double.isFinite(hit.appliedHp()) || hit.appliedHp() < 0
                || !Double.isFinite(health.remainingHp()) || health.remainingHp() < 0
                || !Double.isFinite(health.maximumHp()) || health.maximumHp() < 0) {
            throw new IllegalArgumentException("伤害日志数值无效");
        }
        if (participants.attacker() == null || participants.target() == null || participants.kind() == null
                || hit.part() == null || hit.source() == null || spillover == null) {
            throw new IllegalArgumentException("伤害日志文本不能为空");
        }
    }

    public record Participants(String attacker, String target, String kind) { }

    public record Hit(String part, double damageAfterArmor, double rawDamage, String source, double appliedHp) { }

    public record Health(double remainingHp, double maximumHp) { }
}
