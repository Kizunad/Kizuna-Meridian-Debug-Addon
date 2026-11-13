package dev.kizuna.meridian.debug;

import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 调试补元前后的逐条经脉变化：每种属性、形态各增减多少元。 */
public final class QiChangeReport {
    private static final double VISIBLE_CHANGE = 1e-12;
    private static final MathContext AMOUNT_DIGITS = new MathContext(6);
    private static final Map<String, String> ATTRIBUTE_LABELS = Map.of(
            "meridian:none", "无属性",
            "meridian:metal", "金",
            "meridian:wood", "木",
            "meridian:water", "水",
            "meridian:fire", "火",
            "meridian:earth", "土");
    private static final Map<QiPhase, String> PHASE_LABELS = Map.of(
            QiPhase.FREE, "游离态",
            QiPhase.GAS, "气态",
            QiPhase.LIQUID, "液态",
            QiPhase.SOLID, "固态",
            QiPhase.ORDERED, "有序态",
            QiPhase.COHERENT, "相干态");

    private QiChangeReport() {
        // 只比较两份快照，不读宿主。
    }

    public static List<String> lines(Optional<BodySnapshot> before, BodySnapshot after, String meridianId,
                                     boolean wall) {
        // 按构型顺序列出范围内有变化的经脉；经脉 ID 为空表示全身。提交前读不到身体时按空账户比较。
        List<String> lines = new ArrayList<>();
        for (MeridianState state : after.getMeridianStatesList()) {
            if (!meridianId.isEmpty() && !state.getId().equals(meridianId)) {
                continue;
            }
            QiInventory previous = before.flatMap(body -> state(body, state.getId()))
                    .map(old -> account(old, wall))
                    .orElse(QiInventory.getDefaultInstance());
            String changes = changes(previous, account(state, wall));
            if (!changes.isEmpty()) {
                lines.add(state.getId() + "：" + changes);
            }
        }
        return lines;
    }

    private static String changes(QiInventory previous, QiInventory current) {
        // 同属性同形态合并成一项，先列现有的再列被清掉的；增减太小的视为没变。
        Map<Kind, Double> delta = new LinkedHashMap<>();
        current.getEntriesList().forEach(qi -> delta.merge(kind(qi), qi.getAmount(), Double::sum));
        previous.getEntriesList().forEach(qi -> delta.merge(kind(qi), -qi.getAmount(), Double::sum));
        return delta.entrySet().stream()
                .filter(entry -> Math.abs(entry.getValue()) > VISIBLE_CHANGE)
                .map(entry -> signed(entry.getValue()) + " 元 " + attributeLabel(entry.getKey().attribute())
                        + "·" + PHASE_LABELS.get(entry.getKey().phase()))
                .collect(Collectors.joining("，"));
    }

    private static Optional<MeridianState> state(BodySnapshot body, String id) {
        // 提交前后的经脉按 ID 对应，构型不变。
        return body.getMeridianStatesList().stream().filter(state -> state.getId().equals(id)).findFirst();
    }

    private static QiInventory account(MeridianState state, boolean wall) {
        // 外壁与内腔是同一经脉上两个独立账户。
        return wall ? state.getWallQi() : state.getLumenQi();
    }

    private static Kind kind(Qi qi) {
        // 形态由密度派生，与 Framework 的相态边界一致。
        return new Kind(qi.getAttribute(), QiDensity.phase(qi.getDensity()));
    }

    private static String attributeLabel(String id) {
        // 内置属性写中文名，附属属性写完整 ID。
        return ATTRIBUTE_LABELS.getOrDefault(id, id);
    }

    private static String signed(double amount) {
        // 六位有效数字、去掉多余的零，带正负号。
        String digits = new BigDecimal(Math.abs(amount)).round(AMOUNT_DIGITS).stripTrailingZeros().toPlainString();
        return (amount > 0 ? "+" : "−") + digits;
    }

    private record Kind(String attribute, QiPhase phase) { }
}
