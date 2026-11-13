package dev.kizuna.meridian.debug;

import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;

import java.util.List;
import java.util.Locale;

/** 一次调试补元或覆盖的密度与属性比例；比例为空即全为无属性。 */
public record QiDose(double density, List<QiMix.Share> shares) {
    /** 省略 phase 与属性时的默认：无属性游离态。 */
    public static final QiDose FREE = new QiDose(QiDensity.FREE, List.of());
    /** phase 可选的形态，顺序即补全顺序。 */
    public static final List<QiPhase> PHASES = List.of(QiPhase.FREE, QiPhase.GAS, QiPhase.LIQUID, QiPhase.SOLID,
            QiPhase.ORDERED, QiPhase.COHERENT);

    public QiDose {
        // 比例列表冻结，指令执行期间不会被改动。
        shares = List.copyOf(shares);
    }

    public static String phaseName(QiPhase phase) {
        // 指令里写小写英文形态名，与 Framework 的相态枚举一一对应。
        return phase.name().toLowerCase(Locale.ROOT);
    }

    public static double baseline(QiPhase phase) {
        // phase 按 Framework 的相态基准取密度，与内容生成用同一份数。
        return QiDensity.baseline(phase);
    }
}
