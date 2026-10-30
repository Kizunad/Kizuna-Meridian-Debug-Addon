package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 身体摘要只展示快照派生值；详细 Protobuf 仍可另存文件。 */
final class DebugBodySummary {
    private static final int MAX_LIMB_LINES = 4;
    private static final int MAX_FATIGUE_LINES = 5;

    private DebugBodySummary() {
        // 摘要不保存派生状态。
    }

    static String summary(BodySnapshot message) {
        dev.kizuna.meridian.core.BodySnapshot body = BodyProtobuf.fromMessage(message);
        QiInventory lumen = lumenInventory(message);
        double lumenCapacity = lumenCapacity(message);
        var vitality = message.getVitality();
        StringBuilder text = new StringBuilder();
        text.append("身体 ").append(message.getId()).append('\n');
        text.append("境界：").append(message.getRealm().name()).append('\n');
        text.append(String.format(java.util.Locale.ROOT, "真元：%.4f / %.4f 元\n",
                lumen.getEntriesList().stream().mapToDouble(Qi::getAmount).sum(), lumenCapacity));
        text.append(String.format(java.util.Locale.ROOT, "寿元：年龄 %.2f / 寿限 %.2f 秒，已扣 %.2f 秒\n",
                vitality.getAgeSeconds(), vitality.getLifespanLimitSeconds(), vitality.getSpentLifespanSeconds()));
        text.append("死亡：").append(vitality.getDead()).append('\n');
        text.append("四肢有效力量：").append(limbStrengths(body, message)).append('\n');
        text.append("疲劳最高五块：").append(topFatigue(message));
        return text.toString();
    }

    private static double lumenCapacity(BodySnapshot message) {
        Map<String, dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianState> states = new HashMap<>();
        for (var state : message.getMeridianStatesList()) {
            states.put(state.getId(), state);
        }
        double capacity = 0;
        for (var definition : message.getStructure().getMeridiansList()) {
            var state = states.get(definition.getId());
            if (state != null) {
                capacity += QiDensity.displayedCapacity(definition.getLumenVolume(), state.getLumenQi());
            }
        }
        return capacity;
    }

    private static String limbStrengths(dev.kizuna.meridian.core.BodySnapshot body,
                                        dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot message) {
        Map<String, Double> strengths = new HashMap<>();
        for (TissueDefinition definition : message.getStructure().getTissuesList()) {
            Anatomy.TissueId id = new Anatomy.TissueId(definition.getId());
            double strength = TissueCapabilities.available(body, id).strengthNewtons();
            strengths.merge(definition.getPartId(), strength, Double::sum);
        }
        return message.getStructure().getPartsList().stream()
                .limit(MAX_LIMB_LINES)
                .map(part -> part.getId() + "=" + format(strengths.getOrDefault(part.getId(), 0.0)) + "N")
                .toList().toString();
    }

    private static String topFatigue(
            BodySnapshot message) {
        List<FatigueLine> lines = new ArrayList<>();
        for (TissueDefinition definition : message.getStructure().getTissuesList()) {
            TissueState state = message.getTissueStatesList().stream()
                    .filter(item -> item.getId().equals(definition.getId()))
                    .findFirst()
                    .orElseThrow();
            lines.add(new FatigueLine(definition.getId(), state.getFatigue(), definition.getFatigueCapacity()));
        }
        return lines.stream()
                .sorted(Comparator.comparingDouble(FatigueLine::fatigue).reversed())
                .limit(MAX_FATIGUE_LINES)
                .map(line -> line.id() + "=" + format(line.fatigue()) + "/" + format(line.capacity()))
                .toList().toString();
    }

    private static QiInventory lumenInventory(
            dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot message) {
        List<Qi> entries = new ArrayList<>();
        message.getMeridianStatesList().forEach(state -> entries.addAll(state.getLumenQi().getEntriesList()));
        return QiInventory.newBuilder()
                .addAllEntries(entries)
                .build();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private record FatigueLine(String id, double fatigue, double capacity) {
    }
}
