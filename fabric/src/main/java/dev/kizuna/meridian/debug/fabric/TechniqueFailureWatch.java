package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 保存每个实体最近一次功法失败，供调试状态查询，不写入身体进度。 */
final class TechniqueFailureWatch {
    private final Map<UUID, Failure> failures = new HashMap<>();

    void record(UUID entityId, CastOutcome outcome) {
        if (!failed(outcome)) {
            return;
        }
        failures.put(entityId, new Failure(
                outcome.getFailure(), outcome.getFrameworkFailure(), outcome.getReason()));
    }

    Failure latest(UUID entityId) {
        return failures.get(entityId);
    }

    void clear(UUID entityId) {
        failures.remove(entityId);
    }

    void clearAll() {
        failures.clear();
    }

    private static boolean failed(CastOutcome outcome) {
        return outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED
                || outcome.getFrameworkFailure() != FailureCode.FAILURE_CODE_UNSPECIFIED;
    }

    record Failure(TechniqueFailure technique, FailureCode framework, String reason) {
        String display() {
            String code;
            if (technique == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
                code = framework.name();
            } else {
                code = technique.name();
            }
            String detail = reason.isBlank() ? "无原因" : reason;
            return code + "，" + detail;
        }
    }
}
