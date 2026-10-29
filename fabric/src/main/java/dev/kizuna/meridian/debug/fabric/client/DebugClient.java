package dev.kizuna.meridian.debug.fabric.client;

import dev.kizuna.meridian.debug.fabric.DebugFabric;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianCondition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;

/** 肢体透视镜 HUD；所有数值来自服务端快照包。 */
public final class DebugClient implements ClientModInitializer {
    private static final int PANEL_WIDTH = 190;
    private static final int PANEL_X_MARGIN = 8;
    private static final int PANEL_Y = 20;
    private static final int BAR_WIDTH = 82;
    private static final int BAR_HEIGHT = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int TARGET_NAME_LENGTH = 64;
    private static final int PANEL_TEXT_X_OFFSET = 5;
    private static final int HEADER_TEXT_Y_OFFSET = 4;
    private static final int NO_BODY_TEXT_Y_OFFSET = 16;
    private static final int NO_BODY_PANEL_HEIGHT = 34;
    private static final int BASE_PANEL_HEIGHT = 58;
    private static final int PART_LINES_PER_PART = 2;
    private static final int PANEL_BACKGROUND = 0x99000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int WARNING_TEXT_COLOR = 0xFFFF8080;
    private static final int STATUS_TEXT_COLOR = 0xFFFFD080;
    private static final int BAR_BACKGROUND = 0xFF4A4A4A;
    private static final int BAR_FOREGROUND = 0xFF55CC55;
    private static volatile LensState state = LensState.NONE;

    @Override
    public void onInitializeClient() {
        // 网络线程只解析字节，状态切换回客户端线程后再交给 HUD 读取。
        ClientPlayNetworking.registerGlobalReceiver(DebugFabric.LENS_STATE,
                (client, handler, buffer, responseSender) -> {
                    boolean hasTarget = buffer.readBoolean();
                    if (!hasTarget) {
                        client.execute(() -> state = LensState.NONE);
                        return;
                    }
                    String targetName = buffer.readString(TARGET_NAME_LENGTH);
                    boolean hasBody = buffer.readBoolean();
                    byte[] bodyBytes;
                    if (hasBody) {
                        bodyBytes = buffer.readByteArray();
                    } else {
                        bodyBytes = new byte[0];
                    }
                    client.execute(() -> state = decode(targetName, bodyBytes));
                });
        HudRenderCallback.EVENT.register((context, tickDelta) -> render(context));
    }

    private static LensState decode(String targetName, byte[] bodyBytes) {
        // 服务端已限制目标和包大小；解析失败时清空面板而不是显示旧快照。
        if (bodyBytes.length == 0) {
            return new LensState(targetName, null);
        }
        try {
            return new LensState(targetName, BodySnapshot.parseFrom(bodyBytes));
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            return LensState.NONE;
        }
    }

    private void render(DrawContext context) {
        // 不手持透视镜时不渲染旧状态；服务端也不会继续发送新包。
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !isHoldingLens(client)) {
            return;
        }
        LensState current = state;
        if (current == LensState.NONE) {
            return;
        }
        int x = client.getWindow().getScaledWidth() - PANEL_WIDTH - PANEL_X_MARGIN;
        int y = PANEL_Y;
        BodySnapshot body = current.body();
        int height = panelHeight(body);
        context.fill(x, y, x + PANEL_WIDTH, y + height, PANEL_BACKGROUND);
        context.drawTextWithShadow(client.textRenderer, Text.literal("透视：" + current.targetName()),
                x + PANEL_TEXT_X_OFFSET, y + HEADER_TEXT_Y_OFFSET, TEXT_COLOR);
        if (body == null) {
            context.drawTextWithShadow(client.textRenderer, Text.literal("无身体"),
                    x + PANEL_TEXT_X_OFFSET, y + NO_BODY_TEXT_Y_OFFSET, WARNING_TEXT_COLOR);
            return;
        }
        renderBody(context, client, body, x + PANEL_TEXT_X_OFFSET, y + NO_BODY_TEXT_Y_OFFSET);
    }

    private static int panelHeight(BodySnapshot body) {
        // 高度按实际部位数量增长，保证每个部位都有自己的条目。
        if (body == null) {
            return NO_BODY_PANEL_HEIGHT;
        }
        int partLines = body.getStructure().getPartsCount() * PART_LINES_PER_PART;
        return BASE_PANEL_HEIGHT + partLines * LINE_HEIGHT;
    }

    private static void renderBody(DrawContext context, MinecraftClient client, BodySnapshot body, int x, int y) {
        // 部位、总量、体力和真元均从同一份服务端快照计算。
        var structure = body.getStructure();
        var partStates = body.getPartStatesList();
        Map<String, PartState> states = new HashMap<>();
        partStates.forEach(part -> states.put(part.getId(), part));
        int line = y;
        for (PartDefinition definition : structure.getPartsList()) {
            PartState part = states.get(definition.getId());
            double current;
            if (part == null) {
                current = 0;
            } else {
                current = part.getHp();
            }
            line = drawBar(context, client, definition.getDisplayName(), current, definition.getMaxHp(), x, line);
        }
        double currentHp = partStates.stream().mapToDouble(PartState::getHp).sum();
        double maximumHp = structure.getPartsList().stream().mapToDouble(PartDefinition::getMaxHp).sum();
        int totalLine = drawValue(context, client, "总血量", currentHp, maximumHp, x, line);
        double[] stamina = stamina(body);
        int staminaLine = drawValue(context, client, "体力", stamina[0], stamina[1], x, totalLine);
        double qi = qi(body);
        int qiLine = drawValue(context, client, "真元", qi, qi, x, staminaLine);
        String status = status(body);
        context.drawTextWithShadow(client.textRenderer, Text.literal("状态：" + status), x, qiLine,
                STATUS_TEXT_COLOR);
    }

    private static int drawBar(DrawContext context, MinecraftClient client, String label,
                               double current, double maximum, int x, int y) {
        // 先画标签和数字，再按比例填充条形图，负值和超上限由 clamp 处理。
        String text = String.format(java.util.Locale.ROOT, "%s %.1f/%.1f", label, current, maximum);
        context.drawTextWithShadow(client.textRenderer, Text.literal(text), x, y, TEXT_COLOR);
        int barY = y + LINE_HEIGHT;
        context.fill(x, barY, x + BAR_WIDTH, barY + BAR_HEIGHT, BAR_BACKGROUND);
        double ratio;
        if (maximum > 0) {
            ratio = current / maximum;
        } else {
            ratio = 0;
        }
        int filled = MathHelper.clamp((int) Math.round(ratio * BAR_WIDTH), 0, BAR_WIDTH);
        context.fill(x, barY, x + filled, barY + BAR_HEIGHT, BAR_FOREGROUND);
        return barY + BAR_HEIGHT + LINE_HEIGHT;
    }

    private static int drawValue(DrawContext context, MinecraftClient client, String label,
                                 double current, double maximum, int x, int y) {
        // 汇总值使用和部位相同的数字精度，方便与聊天快照对照。
        String text = String.format(java.util.Locale.ROOT, "%s %.1f/%.1f", label, current, maximum);
        context.drawTextWithShadow(client.textRenderer, Text.literal(text), x, y, TEXT_COLOR);
        return y + LINE_HEIGHT;
    }

    private static double[] stamina(BodySnapshot body) {
        // 体力由有耐力上限的组织容量减去当前疲劳得到，不伪造额外身体字段。
        Map<String, TissueState> states = new HashMap<>();
        body.getTissueStatesList().forEach(tissue -> states.put(tissue.getId(), tissue));
        double maximum = 0;
        double current = 0;
        for (TissueDefinition definition : body.getStructure().getTissuesList()) {
            if (!definition.getCapacity().hasEnduranceJoules()) {
                continue;
            }
            double capacity = definition.getCapacity().getEnduranceJoules();
            TissueState state = states.get(definition.getId());
            double fatigue;
            if (state == null) {
                fatigue = 0;
            } else {
                fatigue = state.getFatigue();
            }
            maximum += capacity;
            current += Math.max(0, capacity - fatigue);
        }
        return new double[]{current, maximum};
    }

    private static double qi(BodySnapshot body) {
        // 真元总量汇总内腔、外壁、器官缓存和在途明细。
        double total = 0;
        for (var state : body.getMeridianStatesList()) {
            total += state.getLumenQi().getEntriesList().stream()
                    .mapToDouble(entry -> entry.getAmount()).sum();
            total += state.getWallQi().getEntriesList().stream()
                    .mapToDouble(entry -> entry.getAmount()).sum();
        }
        for (var state : body.getOrganStatesList()) {
            total += state.getBuffer().getEntriesList().stream()
                    .mapToDouble(entry -> entry.getAmount()).sum();
        }
        for (var transit : body.getTransitsList()) {
            total += transit.getCargo().getEntriesList().stream()
                    .mapToDouble(entry -> entry.getAmount()).sum();
        }
        return total;
    }

    private static String status(BodySnapshot body) {
        // 当前协议没有独立部位伤病枚举，展示已有经脉条件；全部完整时显示正常。
        for (var state : body.getMeridianStatesList()) {
            if (state.getCondition() != MeridianCondition.INTACT) {
                return state.getCondition().name();
            }
        }
        return "正常";
    }

    private static boolean isHoldingLens(MinecraftClient client) {
        // 客户端只决定是否显示，目标和数据仍由服务端包提供。
        return client.player.getMainHandStack().isOf(net.minecraft.registry.Registries.ITEM
                .get(new net.minecraft.util.Identifier("meridian_debug", "limb_lens")))
                || client.player.getOffHandStack().isOf(net.minecraft.registry.Registries.ITEM
                .get(new net.minecraft.util.Identifier("meridian_debug", "limb_lens")));
    }

    private record LensState(String targetName, BodySnapshot body) {
        private static final LensState NONE = new LensState("", null);
    }
}
