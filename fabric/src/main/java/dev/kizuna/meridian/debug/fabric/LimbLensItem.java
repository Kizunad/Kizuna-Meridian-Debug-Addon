package dev.kizuna.meridian.debug.fabric;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 肢体透视镜的右键入口；实际快照查询由服务端调试入口统一执行。 */
final class LimbLensItem extends Item {
    LimbLensItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player user, LivingEntity entity, InteractionHand hand) {
        // 客户端只播放原版交互；权限、目标和身体读取全部在服务端完成。
        if (user.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (user instanceof net.minecraft.server.level.ServerPlayer player) {
            LivingEntity target = player.isShiftKeyDown() ? player : entity;
            DebugFabric.printSnapshot(player, target);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        // 潜行右键空中查看自己；非潜行空挥不创建额外查询。26.2 的 SUCCESS 只在客户端挥手，与旧版分端成功相同。
        if (!world.isClientSide() && user instanceof net.minecraft.server.level.ServerPlayer player
                && player.isShiftKeyDown()) {
            DebugFabric.printSnapshot(player, player);
        }
        return InteractionResult.SUCCESS;
    }
}
