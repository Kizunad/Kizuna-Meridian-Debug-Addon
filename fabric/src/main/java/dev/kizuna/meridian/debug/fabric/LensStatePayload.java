package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/**
 * 肢体透视镜状态包。通道 ID、目标名限长和编解码只在这里定义一份，服务端发送与客户端接收共用；
 * 线格式与 1.20.1 相同：有无目标、目标名、有无身体、身体快照字节。
 */
public record LensStatePayload(boolean hasTarget, String targetName, boolean hasBody, byte[] body)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<LensStatePayload> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("meridian_debug", "lens_state"));
    static final StreamCodec<FriendlyByteBuf, LensStatePayload> CODEC = StreamCodec.of(
            (buffer, payload) -> payload.write(buffer), LensStatePayload::read);
    private static final int MAX_TARGET_NAME_LENGTH = 64;

    static LensStatePayload empty() {
        // 没有准星目标或权限不足时只发「无目标」，客户端据此清空旧快照。
        return new LensStatePayload(false /* hasTarget */, "", false /* hasBody */, new byte[0]);
    }

    static LensStatePayload of(String targetName, Optional<BodySnapshot> snapshot) {
        // 一次只带当前目标和一份完整快照；目标无身体时不带快照字节。
        byte[] body = snapshot.map(BodySnapshot::toByteArray).orElseGet(() -> new byte[0]);
        return new LensStatePayload(true /* hasTarget */, targetName, snapshot.isPresent(), body);
    }

    private void write(FriendlyByteBuf buffer) {
        // 无目标时只写一个布尔值，其余字段按顺序跟在后面。
        buffer.writeBoolean(hasTarget);
        if (!hasTarget) {
            return;
        }
        buffer.writeUtf(targetName, MAX_TARGET_NAME_LENGTH);
        buffer.writeBoolean(hasBody);
        if (hasBody) {
            buffer.writeByteArray(body);
        }
    }

    private static LensStatePayload read(FriendlyByteBuf buffer) {
        // 读取顺序与写出一致；目标名按同一限长读取。
        if (!buffer.readBoolean()) {
            return empty();
        }
        String targetName = buffer.readUtf(MAX_TARGET_NAME_LENGTH);
        if (!buffer.readBoolean()) {
            return new LensStatePayload(true /* hasTarget */, targetName, false /* hasBody */, new byte[0]);
        }
        return new LensStatePayload(true /* hasTarget */, targetName, true /* hasBody */, buffer.readByteArray());
    }

    @Override
    public Type<LensStatePayload> type() {
        // 收发两端都按这个类型查找通道。
        return TYPE;
    }
}
