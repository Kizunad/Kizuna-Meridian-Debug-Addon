package dev.kizuna.meridian.debug.fabric;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** 肢体透视镜的右键入口；实际快照查询由服务端调试入口统一执行。 */
final class LimbLensItem extends Item {
    LimbLensItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        // 客户端只播放原版交互；权限、目标和身体读取全部在服务端完成。
        if (user.getWorld().isClient) {
            return ActionResult.SUCCESS;
        }
        if (user instanceof net.minecraft.server.network.ServerPlayerEntity player) {
            LivingEntity target = player.isSneaking() ? player : entity;
            DebugFabric.printSnapshot(player, target);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        // 潜行右键空中查看自己；非潜行空挥不创建额外查询。
        if (!world.isClient && user instanceof net.minecraft.server.network.ServerPlayerEntity player
                && player.isSneaking()) {
            DebugFabric.printSnapshot(player, player);
        }
        return TypedActionResult.success(user.getStackInHand(hand), world.isClient);
    }
}
