package dev.kizuna.meridian.debug.verification;

import dev.kizuna.meridian.debug.QiChangeReport;
import dev.kizuna.meridian.debug.QiMix;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 调试补元的属性写法、补全与逐条经脉回显。 */
public final class QiMixTest {
    private static final List<String> REGISTERED = List.of("meridian:none", "meridian:metal", "meridian:wood",
            "meridian:water", "meridian:fire", "meridian:earth", "qi_variants:wither", "qi_variants:withering");

    @Test
    public void mixAcceptsShortNamesFullIdsAndCommasOrSpaces() {
        // 内置写短名、附属写全 ID 或唯一路径；逗号与空格都能分隔，余下的比例不写出来。
        List<QiMix.Share> shares = QiMix.parse("metal=0.2, fire=0.3 withering=0.1", REGISTERED);

        assertEquals(List.of(new QiMix.Share("meridian:metal", 0.2), new QiMix.Share("meridian:fire", 0.3),
                new QiMix.Share("qi_variants:withering", 0.1)), shares);
        assertEquals(List.of(new QiMix.Share("qi_variants:wither", 1)), QiMix.single("qi_variants:wither", REGISTERED));
        assertEquals(List.of(new QiMix.Share("meridian:none", 0.5), new QiMix.Share("meridian:wood", 0.5)),
                QiMix.parse("none=0.5,wood=0.5", REGISTERED));
    }

    @Test
    public void mixRejectsUnknownDuplicateMalformedAndOverfullShares() {
        // 未登记属性、写两次、缺等号、比例越界或读不成数、合计超过 1、attribute 写多个都报错。
        assertMessage("未登记", () -> QiMix.parse("lava=0.2", REGISTERED));
        assertMessage("未登记", () -> QiMix.single("other:fire", REGISTERED));
        assertMessage("写了两次", () -> QiMix.parse("fire=0.2,meridian:fire=0.2", REGISTERED));
        assertMessage("属性=比例", () -> QiMix.parse("fire", REGISTERED));
        assertMessage("不超过 1", () -> QiMix.parse("fire=0", REGISTERED));
        assertMessage("不超过 1", () -> QiMix.parse("fire=1.5", REGISTERED));
        assertMessage("不超过 1", () -> QiMix.parse("fire=abc", REGISTERED));
        assertMessage("合计 1.1 超过 1", () -> QiMix.parse("fire=0.6,water=0.5", REGISTERED));
        assertMessage("至少写一项", () -> QiMix.parse(" , ", REGISTERED));
        assertMessage("只写一个属性", () -> QiMix.single("fire water", REGISTERED));
    }

    @Test
    public void completionsOfferRegisteredNamesAndTheRemainingShare() {
        // attribute 列出全部已登记属性；mix 只补最后一项，先补「属性=」并排除已写过的，写了等号补剩余比例。
        assertEquals(List.of("none", "metal", "wood", "water", "fire", "earth", "qi_variants:wither",
                "qi_variants:withering"), QiMix.completeAttribute("", REGISTERED));
        assertEquals(List.of("qi_variants:wither", "qi_variants:withering"),
                QiMix.completeAttribute("wi", REGISTERED));

        QiMix.Completion names = QiMix.completeMix("fire=0.3,w", REGISTERED);
        assertEquals(9, names.offset());
        assertEquals(List.of("wood=", "water=", "qi_variants:wither=", "qi_variants:withering="), names.candidates());
        QiMix.Completion remaining = QiMix.completeMix("metal=0.2 fire=0.3 water=", REGISTERED);
        assertEquals(List.of("water=0.5"), remaining.candidates());
        assertTrue(QiMix.completeMix("fire=0.6 metal=0.4 water=", REGISTERED).candidates().isEmpty());
        assertTrue(QiMix.completeMix("fire=0.3 f", REGISTERED).candidates().isEmpty());
    }

    @Test
    public void reportListsEachChangedMeridianByAttributeAndPhase() {
        // 只写有变化的经脉；同一经脉按属性、形态逐项写增减，内置属性写中文名，附属属性写全 ID。
        var before = body(meridian("a", qi("meridian:none", 0.02, 0.01)), meridian("b"));
        var after = body(meridian("a", qi("meridian:fire", 0.3, 0.1), qi("qi_variants:withering", 0.12, 0.1)),
                meridian("b"));

        List<String> lines = QiChangeReport.lines(Optional.of(before), after, "", false);

        assertEquals(List.of("a：+0.3 元 火·气态，+0.12 元 qi_variants:withering·气态，−0.02 元 无属性·游离态"), lines);
        assertTrue(QiChangeReport.lines(Optional.of(after), after, "", false).isEmpty());
        assertTrue(QiChangeReport.lines(Optional.of(before), after, "b", false).isEmpty());
    }

    private static void assertMessage(String expected, org.junit.function.ThrowingRunnable action) {
        // 报错文字要能直接告诉玩家哪里写错了。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage(), failure.getMessage().contains(expected));
    }

    private static BodyMessages.BodySnapshot body(BodyMessages.MeridianState... states) {
        // 回显只读经脉状态，其余字段留默认值。
        return BodyMessages.BodySnapshot.newBuilder().addAllMeridianStates(List.of(states)).build();
    }

    private static BodyMessages.MeridianState meridian(String id, BodyMessages.Qi... lumen) {
        // 只填内腔；外壁留空。
        return BodyMessages.MeridianState.newBuilder()
                .setId(id)
                .setLumenQi(BodyMessages.QiInventory.newBuilder().addAllEntries(List.of(lumen)))
                .build();
    }

    private static BodyMessages.Qi qi(String attribute, double amount, double density) {
        // 一条明细：属性、元数与密度。
        return BodyMessages.Qi.newBuilder().setAttribute(attribute).setAmount(amount).setDensity(density).build();
    }
}
