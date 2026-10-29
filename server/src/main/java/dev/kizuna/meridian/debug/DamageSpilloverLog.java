package dev.kizuna.meridian.debug;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;

import java.util.Locale;
import java.util.stream.Collectors;

/** 蔓延说明只读取提交回执，不用提交后血量反推系数或重新计算伤害。 */
public final class DamageSpilloverLog {
    private DamageSpilloverLog() {
        // 展示转换不接触宿主或权威 Store。
    }

    public static String format(DamageResolution resolution, BodySnapshot body) {
        // 图腾可能恢复血量，因此仍使用原子回执里的固定分配事实。
        if (!resolution.hasSpillover()) {
            return "";
        }
        var spillover = resolution.getSpillover();
        String targets = spillover.getLossesList().stream()
                .map(loss -> String.format(Locale.ROOT, "%s %.1f",
                        partName(body, loss.getTarget().getPartId()), loss.getHp()))
                .collect(Collectors.joining("、"));
        String unavailable = "";
        if (spillover.getUnabsorbedHp() > 0) {
            unavailable = String.format(Locale.ROOT, "；无承受部位，舍弃 %.1f", spillover.getUnabsorbedHp());
        }
        return String.format(Locale.ROOT, "（溢出 %.1f ×%s → %s%s）",
                spillover.getOverflowHp(), spillover.getMultiplier(), targets, unavailable);
    }

    private static String partName(BodySnapshot body, String id) {
        // 名称来自本次实际构型，支持人体改造后的部位显示。
        return body.getStructure().getPartsList().stream()
                .filter(part -> part.getId().equals(id))
                .map(part -> part.getDisplayName())
                .findFirst().orElse(id);
    }
}
