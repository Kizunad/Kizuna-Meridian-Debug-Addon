package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

/** 聊天栏中的完整快照文本。 */
final class DebugSnapshotText {
    private DebugSnapshotText() {
        // 快照文本直接来自已校验 Protobuf，不缓存派生字符串。
    }

    static String full(BodySnapshot snapshot) {
        // Protobuf 的完整字段文本适合复制，且不会漏掉未来新增的调试字段。
        return "身体快照 " + snapshot.getId() + "\n" + snapshot;
    }
}
