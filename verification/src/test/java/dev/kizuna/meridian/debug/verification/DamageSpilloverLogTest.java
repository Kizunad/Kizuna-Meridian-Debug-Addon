package dev.kizuna.meridian.debug.verification;

import dev.kizuna.meridian.debug.DamageSpilloverLog;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartDefinition;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageSpillover;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HpLoss;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 回执事实与构型显示名的日志回归。 */
public final class DamageSpilloverLogTest {
    private static final double OVERFLOW = 10;
    private static final double MULTIPLIER = 0.7;
    private static final double RECEIVED = 2;
    private static final double UNABSORBED = 5;

    @Test
    public void formatsRecordedDistributionEvenWhenCurrentHpDiffers() {
        // 展示固定回执而非从当前 HP 反推，保命恢复后仍能解释原本的损失。
        HealthTarget source = HealthTarget.newBuilder().setPartId("arm").build();
        HealthTarget target = HealthTarget.newBuilder().setPartId("chest").build();
        HpLoss loss = HpLoss.newBuilder().setTarget(target).setHp(RECEIVED).build();
        DamageSpillover spillover = DamageSpillover.newBuilder()
                .setSource(source)
                .setOverflowHp(OVERFLOW)
                .setMultiplier(MULTIPLIER)
                .addLosses(loss)
                .setUnabsorbedHp(UNABSORBED)
                .build();
        DamageResolution resolution = DamageResolution.newBuilder().setSpillover(spillover).build();
        PartDefinition chest = PartDefinition.newBuilder().setId("chest").setDisplayName("改造胸部").build();
        BodyStructure structure = BodyStructure.newBuilder().addParts(chest).build();
        BodySnapshot body = BodySnapshot.newBuilder().setStructure(structure).build();

        assertEquals("（溢出 10.0 ×0.7 → 改造胸部 2.0；无承受部位，舍弃 5.0）",
                DamageSpilloverLog.format(resolution, body));
    }

    @Test
    public void ordinaryDamageHasNoSpilloverSuffix() {
        // 无余量时不向原有日志添加空箭头或空括号。
        assertEquals("", DamageSpilloverLog.format(
                DamageResolution.getDefaultInstance(), BodySnapshot.getDefaultInstance()));
    }
}
