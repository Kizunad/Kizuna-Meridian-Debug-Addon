package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueHostDirectory;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;
import java.util.UUID;

/** 功法调试只改写 TechniqueState，宿主仍负责身体版本和持久化。 */
final class DebugTechniqueAccess {
    private static final String REQUEST_PREFIX = "meridian_debug_technique_";

    private DebugTechniqueAccess() {
        // 不缓存宿主或进度，避免实体卸载后保留旧身体引用。
    }

    static TechniqueHost host(UUID entityId) {
        List<TechniqueHostEntrypoint> entrypoints = FabricLoader.getInstance()
                .getEntrypoints(TechniqueHostEntrypoint.ENTRYPOINT_ID, TechniqueHostEntrypoint.class);
        TechniqueHostDirectory directory = new TechniqueHostDirectory(entrypoints);
        return directory.resolve(entityId)
                .orElseThrow(() -> new IllegalArgumentException("目标没有可用的功法宿主"));
    }

    static TechniqueProgress progress(TechniqueHost host) {
        BodySnapshot body = host.inspect().body();
        return TechniqueState.read(body);
    }

    static void replaceProgress(UUID entityId, TechniqueHost host, BodySnapshot body,
                                TechniqueProgress progress) {
        BodyActivity activity = BodyActivity.newBuilder()
                .putProgress(TechniqueState.KEY, TechniqueState.pack(progress))
                .build();
        String requestId = REQUEST_PREFIX + entityId + "_" + body.revision();
        TechniqueHost.Submission submission = new TechniqueHost.Submission(
                requestId, body.revision(), activity);
        TechniqueHost.Commit commit = host.submit(submission);
        if (commit.revision() != body.revision() + 1 || !commit.activity().equals(activity)) {
            throw new IllegalStateException("功法宿主回执与调试进度活动不一致");
        }
    }

    static void forget(UUID entityId, String techniqueId) {
        TechniqueHost host = host(entityId);
        BodySnapshot body = host.inspect().body();
        TechniqueProgress progress = TechniqueState.read(body);
        List<KnownTechnique> remaining = progress.getKnownList().stream()
                .filter(known -> !known.getTechnique().getId().equals(techniqueId))
                .toList();
        if (remaining.size() == progress.getKnownCount()) {
            return;
        }
        TechniqueProgress updated = progress.toBuilder()
                .clearKnown()
                .addAllKnown(remaining)
                .build();
        replaceProgress(entityId, host, body, updated);
    }

    static void clearCooldowns(UUID entityId) {
        TechniqueHost host = host(entityId);
        BodySnapshot body = host.inspect().body();
        TechniqueProgress progress = TechniqueState.read(body);
        if (progress.getCooldownsCount() == 0 && progress.getCurveEndsCount() == 0) {
            return;
        }
        TechniqueProgress updated = progress.toBuilder()
                .clearCooldowns()
                .clearCurveEnds()
                .build();
        replaceProgress(entityId, host, body, updated);
    }
}
