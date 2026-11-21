package dev.kizuna.meridian.debug.verification;

import com.google.protobuf.Any;
import com.google.protobuf.BytesValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import dev.kizuna.meridian.debug.DamageLogEntry;
import dev.kizuna.meridian.debug.DamageLogFormatter;
import dev.kizuna.meridian.debug.AddonProgressDisplay;
import dev.kizuna.meridian.debug.HealPlan;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 调试库纯 Java 计划和文本的回归。 */
public final class DebugAddonUnitTest {
    @Test
    public void formatsDamageLogWithAllRequiredFacts() {
        // 格式化结果必须保留来源、部位、原始剂量和提交后剩余 HP。
        var participants = new DamageLogEntry.Participants("你", "卫道士", "真炁");
        var hit = new DamageLogEntry.Hit("左臂", 2.8, 4.0, "door_breaker:cleave", 2.8);
        var health = new DamageLogEntry.Health(76.5, 100.0);
        DamageLogEntry entry = new DamageLogEntry(participants, hit, health, "");

        String text = DamageLogFormatter.format(entry);

        assertTrue(text.contains("你 → 卫道士(真炁) 左臂"));
        assertTrue(text.contains("护甲后 2.8"));
        assertTrue(text.contains("原版 4.0"));
        assertTrue(text.contains("来源：door_breaker:cleave"));
        assertTrue(text.contains("剩余 76.5/100.0"));
    }

    @Test
    public void partCompletionsComeFromBodyStructure() {
        // 补全列表只来自快照构型，不依赖硬编码的人体部位名称。
        BodyMessages.BodySnapshot body = snapshot();

        List<String> parts = HealPlan.partIds(body);

        assertEquals(List.of("left_arm", "head"), parts);
    }

    @Test
    public void healBranchesBuildExpectedDoses() {
        // health/limbs 只恢复部位，all 额外覆盖组织和器官，stamina 单独恢复疲劳。
        BodyMessages.BodySnapshot body = snapshot();

        List<HealthMessages.Healing> health = HealPlan.hpDoses(body, HealPlan.HpScope.PARTS);
        List<HealthMessages.Healing> all = HealPlan.hpDoses(body, HealPlan.HpScope.ALL_MODULES);
        List<HealthMessages.FatigueRelief> stamina = HealPlan.fatigueDoses(body);

        assertEquals(2, health.size());
        assertEquals(4, all.size());
        assertEquals(3, stamina.size());
        assertEquals("left_arm", health.get(0).getTarget().getPartId());
        assertEquals("left_arm_muscle", all.get(2).getTarget().getTissueId());
        assertEquals("left_arm_muscle", stamina.get(0).getTissueId());
    }

    @Test
    public void addonProgressDisplaysSimpleValuesInKeyOrder() {
        Map<String, Any> progress = Map.of(
                "sample:text", Any.pack(StringValue.of("ready")),
                "sample:count", Any.pack(Int64Value.of(7)));

        List<AddonProgressDisplay> entries = AddonProgressDisplay.from(progress);

        assertEquals(List.of("sample:count", "sample:text"), entries.stream()
                .map(AddonProgressDisplay::key).toList());
        assertEquals("lens.addon.sample.count", entries.get(0).translationKey());
        assertEquals("7", entries.get(0).value());
        assertEquals("ready", entries.get(1).value());
    }

    @Test
    public void addonProgressSkipsInvalidAndUnsupportedValues() {
        Map<String, Any> progress = Map.of(
                "invalid", Any.pack(StringValue.of("skip")),
                "sample:unsupported", Any.pack(BytesValue.of(ByteString.copyFrom(new byte[]{1}))),
                "sample:valid", Any.pack(StringValue.of("keep")));

        List<AddonProgressDisplay> entries = AddonProgressDisplay.from(progress);

        assertEquals(List.of("sample:valid"), entries.stream()
                .map(AddonProgressDisplay::key).toList());
    }

    private static BodyMessages.BodySnapshot snapshot() {
        // 测试快照只保留计划所需字段，数值仍按真实消息字段表达。
        BodyMessages.PartDefinition armDefinition = BodyMessages.PartDefinition.newBuilder()
                .setId("left_arm")
                .setDisplayName("左臂")
                .setMaxHp(100)
                .build();
        BodyMessages.PartDefinition headDefinition = BodyMessages.PartDefinition.newBuilder()
                .setId("head")
                .setDisplayName("头部")
                .setMaxHp(80)
                .build();
        BodyMessages.TissueDefinition tissueDefinition = BodyMessages.TissueDefinition.newBuilder()
                .setId("left_arm_muscle")
                .setDisplayName("左臂肌肉")
                .setMaxHp(20)
                .build();
        BodyMessages.OrganDefinition organDefinition = BodyMessages.OrganDefinition.newBuilder()
                .setId("left_lung")
                .setDisplayName("左肺")
                .setMaxHp(30)
                .build();
        BodyMessages.BodyStructure structure = BodyMessages.BodyStructure.newBuilder()
                .addParts(armDefinition)
                .addParts(headDefinition)
                .addTissues(tissueDefinition)
                .addOrgans(organDefinition)
                .build();
        BodyMessages.PartState armState = BodyMessages.PartState.newBuilder()
                .setId("left_arm")
                .setHp(60)
                .build();
        BodyMessages.PartState headState = BodyMessages.PartState.newBuilder()
                .setId("head")
                .setHp(70)
                .build();
        BodyMessages.TissueState tissueState = BodyMessages.TissueState.newBuilder()
                .setId("left_arm_muscle")
                .setHp(10)
                .setFatigue(4)
                .build();
        BodyMessages.OrganState organState = BodyMessages.OrganState.newBuilder()
                .setId("left_lung")
                .setHp(15)
                .setFatigueJoules(3)
                .build();
        BodyMessages.MeridianState meridianState = BodyMessages.MeridianState.newBuilder()
                .setId("left_arm_meridian")
                .setFatigue(2)
                .build();
        return BodyMessages.BodySnapshot.newBuilder()
                .setStructure(structure)
                .addPartStates(armState)
                .addPartStates(headState)
                .addTissueStates(tissueState)
                .addOrganStates(organState)
                .addMeridianStates(meridianState)
                .build();
    }
}
