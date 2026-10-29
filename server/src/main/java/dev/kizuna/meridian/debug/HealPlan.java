package dev.kizuna.meridian.debug;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueState;
import dev.kizuna.meridian.protocol.pb.HealthMessages.FatigueRelief;
import dev.kizuna.meridian.protocol.pb.HealthMessages.Healing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 从权威身体快照构造恢复意图；这里不修改身体，也不保存第二份状态。 */
public final class HealPlan {
    public enum HpScope {
        PARTS,
        ALL_MODULES
    }

    private HealPlan() {
        // 计划只由静态快照函数生成，不需要实例状态。
    }

    public static List<String> partIds(BodySnapshot body) {
        // 部位补全直接读取当前构型，改造身体不会继续暴露旧名单。
        return body.getStructure().getPartsList().stream().map(PartDefinition::getId).toList();
    }

    public static List<Healing> hpDoses(BodySnapshot body, HpScope scope) {
        // 总血量和部位血量都来自独立部位账户；全量模式再补齐组织和器官账户。
        var doses = new ArrayList<Healing>();
        addPartDoses(body, doses);
        if (scope == HpScope.ALL_MODULES) {
            addTissueDoses(body, doses);
            addOrganDoses(body, doses);
        }
        return List.copyOf(doses);
    }

    public static List<FatigueRelief> fatigueDoses(BodySnapshot body) {
        // 体力恢复清除组织、器官和经脉的现有疲劳，不为零值生成非法空剂量。
        var doses = new ArrayList<FatigueRelief>();
        for (TissueState state : body.getTissueStatesList()) {
            if (state.getFatigue() > 0) {
                FatigueRelief dose = FatigueRelief.newBuilder()
                        .setTissueId(state.getId())
                        .setAmount(state.getFatigue())
                        .build();
                doses.add(dose);
            }
        }
        for (OrganState state : body.getOrganStatesList()) {
            if (state.getFatigueJoules() > 0) {
                FatigueRelief dose = FatigueRelief.newBuilder()
                        .setOrganId(state.getId())
                        .setAmount(state.getFatigueJoules())
                        .build();
                doses.add(dose);
            }
        }
        for (var state : body.getMeridianStatesList()) {
            if (state.getFatigue() > 0) {
                FatigueRelief dose = FatigueRelief.newBuilder()
                        .setMeridianId(state.getId())
                        .setAmount(state.getFatigue())
                        .build();
                doses.add(dose);
            }
        }
        return List.copyOf(doses);
    }

    public static Optional<Healing> limbDose(BodySnapshot body, String partId, Double requestedAmount) {
        // 指定部位只生成一个部位账户剂量，数值省略时补到该部位上限。
        PartDefinition definition = body.getStructure().getPartsList().stream()
                .filter(part -> part.getId().equals(partId)).findFirst().orElse(null);
        if (definition == null) {
            return Optional.empty();
        }
        PartState state = body.getPartStatesList().stream()
                .filter(part -> part.getId().equals(partId)).findFirst().orElseThrow();
        double room = definition.getMaxHp() - state.getHp();
        double amount;
        if (requestedAmount == null) {
            amount = room;
        } else {
            amount = Math.min(room, requestedAmount);
        }
        if (amount <= 0) {
            return Optional.empty();
        }
        HealthTarget target = HealthTarget.newBuilder()
                .setPartId(partId)
                .build();
        Healing dose = Healing.newBuilder()
                .setTarget(target)
                .setHp(amount)
                .build();
        return Optional.of(dose);
    }

    private static void addPartDoses(BodySnapshot body, List<Healing> doses) {
        // 部位 HP 是总生命唯一来源，组织和器官不会重复计入总量。
        for (PartState state : body.getPartStatesList()) {
            PartDefinition definition = body.getStructure().getPartsList().stream()
                    .filter(part -> part.getId().equals(state.getId())).findFirst().orElseThrow();
            addDose(doses, partTarget(state.getId()), state.getHp(), definition.getMaxHp());
        }
    }

    private static void addTissueDoses(BodySnapshot body, List<Healing> doses) {
        // 全量恢复同时清理人体内部组织损伤，避免只抬高外部部位 HP。
        for (TissueState state : body.getTissueStatesList()) {
            TissueDefinition definition = body.getStructure().getTissuesList().stream()
                    .filter(tissue -> tissue.getId().equals(state.getId())).findFirst().orElseThrow();
            HealthTarget target = HealthTarget.newBuilder()
                    .setTissueId(state.getId())
                    .build();
            addDose(doses, target, state.getHp(), definition.getMaxHp());
        }
    }

    private static void addOrganDoses(BodySnapshot body, List<Healing> doses) {
        // 器官 HP 也是独立账户，按实际构型上限恢复。
        for (OrganState state : body.getOrganStatesList()) {
            OrganDefinition definition = body.getStructure().getOrgansList().stream()
                    .filter(organ -> organ.getId().equals(state.getId())).findFirst().orElseThrow();
            HealthTarget target = HealthTarget.newBuilder()
                    .setOrganId(state.getId())
                    .build();
            addDose(doses, target, state.getHp(), definition.getMaxHp());
        }
    }

    private static HealthTarget partTarget(String partId) {
        // 先构造目标消息，再交给外层剂量 builder，避免嵌套 builder。
        return HealthTarget.newBuilder()
                .setPartId(partId)
                .build();
    }

    private static void addDose(List<Healing> doses, HealthTarget target, double current, double maximum) {
        // 满值账户不进入请求，保持协议要求的正数剂量和最小请求体积。
        double room = maximum - current;
        if (room <= 0) {
            return;
        }
        Healing dose = Healing.newBuilder()
                .setTarget(target)
                .setHp(room)
                .build();
        doses.add(dose);
    }
}
