package dev.kizuna.meridian.debug.fabric;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 仇恨牵引器的实体选择和模式切换入口。 */
final class AggroLinkItem extends Item {
    AggroLinkItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player user, LivingEntity entity, InteractionHand hand) {
        // 客户端只保留原版交互反馈，选择状态和仇恨覆盖由服务端保存。
        if (user.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (user instanceof ServerPlayer player) {
            handleEntity(player, entity);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        // 右键空气取消当前选择，避免把半完成的调试状态留在玩家身上。26.2 的 SUCCESS 只在客户端挥手，与旧版分端成功相同。
        if (!world.isClientSide() && user instanceof ServerPlayer player) {
            DebugFabric.cancelAggroSelection(player);
        }
        return InteractionResult.SUCCESS;
    }

    private static void handleEntity(ServerPlayer player, LivingEntity entity) {
        if (entity instanceof Player) {
            send(player, "不能对玩家使用");
            return;
        }
        if (!(entity instanceof Mob mob)) {
            send(player, "目标不是可攻击生物");
            return;
        }
        if (!AggroLinkRegistry.canAttack(mob)) {
            send(player, "该生物无法攻击");
            return;
        }
        DebugFabric.useAggroTarget(player, mob);
    }

    private static void send(ServerPlayer player, String message) {
        // 选择提示显示在快捷栏上方，不进聊天记录。
        player.sendOverlayMessage(Component.literal(message));
    }
}
