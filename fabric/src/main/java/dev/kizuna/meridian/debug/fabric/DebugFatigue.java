package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueState;
import dev.kizuna.meridian.protocol.pb.HealthMessages.FatigueRelief;
import dev.kizuna.meridian.protocol.pb.HealthMessages.RestoreFatigue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissues;
import net.minecraft.entity.LivingEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 疲劳命令的选择、恢复和做功计划；最终数值仍由 Framework 计算。 */
final class DebugFatigue {
    static final String ALL = "all";
    private static final String SOURCE_ID = "meridian_debug:command";
    private static final double RATIO_MINIMUM = 0;
    private static final double RATIO_MAXIMUM = 1;
    private static final double WORK_SAFETY_RATIO = 0.999999999;

    private DebugFatigue() {
        // 不缓存身体构型，补全和执行都读取当前快照。
    }

    static List<TissueDefinition> select(dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot body,
                                         String selector) {
        List<TissueDefinition> definitions = body.getStructure().getTissuesList();
        if (ALL.equals(selector)) {
            return definitions;
        }
        List<TissueDefinition> ids = definitions.stream()
                .filter(definition -> definition.getId().equals(selector))
                .toList();
        if (!ids.isEmpty()) {
            return ids;
        }
        return definitions.stream()
                .filter(definition -> definition.getTagsList().contains(selector))
                .toList();
    }

    static Set<String> completionValues(dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot body) {
        Set<String> values = new LinkedHashSet<>();
        values.add(ALL);
        for (TissueDefinition definition : body.getStructure().getTissuesList()) {
            values.add(definition.getId());
            values.addAll(definition.getTagsList());
        }
        return values;
    }

    static dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot set(
            LivingEntity entity,
            dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot body,
            List<TissueDefinition> selected,
            double ratio) {
        validateRatio(ratio);
        dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot restored = restoreSelected(
                entity, body, selected);
        if (ratio == RATIO_MINIMUM) {
            return restored;
        }
        List<ExertTissue> loads = loadsFor(restored, selected, ratio);
        if (loads.isEmpty()) {
            return restored;
        }
        ExertTissues exertions = ExertTissues.newBuilder()
                .addAllLoads(loads)
                .build();
        BodyCommand command = BodyCommand.newBuilder()
                .setExertTissues(exertions)
                .build();
        return DebugBodyAccess.submitExertion(entity, restored, command)
                .orElseThrow(() -> new IllegalStateException("目标身体在疲劳提交时失去宿主"));
    }

    private static dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot restoreSelected(
            LivingEntity entity,
            dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot body,
            List<TissueDefinition> selected) {
        List<FatigueRelief> doses = new ArrayList<>();
        for (TissueDefinition definition : selected) {
            TissueState state = state(body, definition.getId());
            if (state.getFatigue() > 0) {
                FatigueRelief dose = FatigueRelief.newBuilder()
                        .setTissueId(definition.getId())
                        .setAmount(state.getFatigue())
                        .build();
                doses.add(dose);
            }
        }
        if (doses.isEmpty()) {
            return body;
        }
        RestoreFatigue restore = RestoreFatigue.newBuilder()
                .setSourceId(SOURCE_ID)
                .addAllDoses(doses)
                .build();
        BodyCommand command = BodyCommand.newBuilder()
                .setRestoreFatigue(restore)
                .build();
        return DebugBodyAccess.submit(entity, body, command)
                .orElseThrow(() -> new IllegalStateException("目标身体在疲劳清零时失去宿主"));
    }

    private static List<ExertTissue> loadsFor(
            dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot body,
            List<TissueDefinition> selected,
            double ratio) {
        BodySnapshot coreBody = BodyProtobuf.fromMessage(body);
        List<ExertTissue> loads = new ArrayList<>();
        for (TissueDefinition definition : selected) {
            TissueState state = state(body, definition.getId());
            double targetFatigue = definition.getFatigueCapacity() * ratio;
            double remainingCapacity = definition.getFatigueCapacity() - state.getFatigue();
            double fatigueIncrease = targetFatigue - state.getFatigue();
            if (fatigueIncrease <= 0 || remainingCapacity <= 0) {
                continue;
            }
            Anatomy.TissueId tissueId = new Anatomy.TissueId(definition.getId());
            Anatomy.TissueCapacity available = TissueCapabilities.available(coreBody, tissueId);
            double workRatio = fatigueIncrease / remainingCapacity;
            double enduranceJoules = available.enduranceJoules();
            double work = enduranceJoules * workRatio;
            double safeWork = Math.min(work, enduranceJoules * WORK_SAFETY_RATIO);
            double power = available.burstWatts();
            if (!Double.isFinite(power) || power <= 0 || !Double.isFinite(safeWork) || safeWork <= 0) {
                throw new IllegalArgumentException("组织没有可用做功能力：" + definition.getId());
            }
            double duration = safeWork / power;
            ExertTissue load = ExertTissue.newBuilder()
                    .setTissueId(definition.getId())
                    .setForceNewtons(0)
                    .setPowerWatts(power)
                    .setDurationSeconds(duration)
                    .build();
            loads.add(load);
        }
        return loads;
    }

    private static TissueState state(
            dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot body,
            String id) {
        return body.getTissueStatesList().stream()
                .filter(item -> item.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("组织状态缺失：" + id));
    }

    private static void validateRatio(double ratio) {
        if (!Double.isFinite(ratio) || ratio < RATIO_MINIMUM || ratio > RATIO_MAXIMUM) {
            throw new IllegalArgumentException("疲劳比例必须在 0 到 1 之间");
        }
    }
}
