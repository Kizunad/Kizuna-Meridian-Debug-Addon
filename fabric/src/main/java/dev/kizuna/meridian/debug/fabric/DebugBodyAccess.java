package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyHealthHost;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.LivingEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 通过已安装身体宿主读取和提交快照，不接触 Human 或 Threatening 的内部类。 */
final class DebugBodyAccess {
    private static List<BodyHealthHost> hosts = List.of();

    private DebugBodyAccess() {
        // 身体宿主由 Fabric 入口绑定，查询不缓存任何身体数据。
    }

    static void bindHosts() {
        // 必装 Human 与类人宿主同时注册，缺失时由 Vanilla-Damage 的装配错误直接暴露。
        hosts = List.copyOf(FabricLoader.getInstance().getEntrypoints("meridian_health_host", BodyHealthHost.class));
    }

    static Optional<BodySnapshot> inspect(LivingEntity entity) {
        // 未被任何宿主认领的实体返回空值，已认领宿主的拒绝不能换用另一份身体。
        String subjectId = entity.getUuidAsString();
        String requestId = UUID.randomUUID().toString();
        HealthHostRequest request = HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setSubjectId(subjectId)
                .setInspect(true)
                .build();
        return exchangeInspect(request);
    }

    static Optional<BodySnapshot> submit(LivingEntity entity, BodySnapshot body, BodyCommand command) {
        // 健康命令保留读取时的身体 ID 与版本，提交结果只能来自同一宿主事务。
        String requestId = UUID.randomUUID().toString();
        BodyCommand boundCommand = command.toBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .build();
        RequestEnvelope envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId)
                .setBodyCommand(boundCommand)
                .build();
        HealthHostRequest request = HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setSubjectId(entity.getUuidAsString())
                .setEffect(envelope)
                .build();
        for (BodyHealthHost host : hosts) {
            HealthHostResponse response = exchange(host, request);
            if (response.hasUnbound()) {
                continue;
            }
            return Optional.of(response.getBody());
        }
        return Optional.empty();
    }

    private static Optional<BodySnapshot> exchangeInspect(HealthHostRequest request) {
        BodySnapshot found = null;
        for (BodyHealthHost host : hosts) {
            HealthHostResponse response = exchange(host, request);
            if (response.hasUnbound()) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("身体宿主重复认领：" + request.getSubjectId());
            }
            found = response.getBody();
        }
        return Optional.ofNullable(found);
    }

    private static HealthHostResponse exchange(BodyHealthHost host, HealthHostRequest request) {
        // 协议解码同时校验响应快照和失败边界；失败不转成无身体。
        HealthHostResponse response = HealthHostProtobuf.decodeResponse(
                host.exchange(HealthHostProtobuf.encodeRequest(request)));
        if (!response.getRequestId().equals(request.getRequestId())) {
            throw new IllegalStateException("身体宿主响应身份不匹配");
        }
        if (response.hasFailure()) {
            throw new DebugHostFailure(response.getFailure().getCode());
        }
        if (!response.hasBody() && !response.hasUnbound()) {
            throw new IllegalStateException("身体宿主响应缺少结果");
        }
        return response;
    }

    static final class DebugHostFailure extends RuntimeException {
        private final dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode code;

        DebugHostFailure(dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode code) {
            // 保留 Framework 失败码，命令层只把它转换为简短提示。
            this.code = code;
        }

        dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode code() {
            // 调试命令需要区分版本冲突、身体死亡等业务拒绝。
            return code;
        }
    }
}
