package dev.kizuna.meridian.debug.fabric;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** 仇恨牵引器的实体选择和模式切换入口。 */
final class AggroLinkItem extends Item {
    AggroLinkItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        // 客户端只保留原版交互反馈，选择状态和仇恨覆盖由服务端保存。
        if (user.getWorld().isClient) {
            return ActionResult.SUCCESS;
        }
        if (user instanceof ServerPlayerEntity player) {
            handleEntity(player, entity);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        // 右键空气取消当前选择，避免把半完成的调试状态留在玩家身上。
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            DebugFabric.cancelAggroSelection(player);
        }
        return TypedActionResult.success(user.getStackInHand(hand), world.isClient /* clientSide */);
    }

    private static void handleEntity(ServerPlayerEntity player, LivingEntity entity) {
        if (entity instanceof PlayerEntity) {
            send(player, "不能对玩家使用");
            return;
        }
        if (!(entity instanceof MobEntity mob)) {
            send(player, "目标不是可攻击生物");
            return;
        }
        if (!AggroLinkRegistry.canAttack(mob)) {
            send(player, "该生物无法攻击");
            return;
        }
        DebugFabric.useAggroTarget(player, mob);
    }

    private static void send(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), true /* overlay */);
    }
}
