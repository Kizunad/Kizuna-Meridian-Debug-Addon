package dev.kizuna.meridian.debug.fabric;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/** 调试库物品和独立创造栏页。 */
final class DebugItems {
    static final ResourceLocation LENS_ID = new ResourceLocation("meridian_debug", "limb_lens");
    static final ResourceLocation AGGRO_LINK_ID = new ResourceLocation("meridian_debug", "aggro_link");
    static final ResourceLocation GROUP_ID = new ResourceLocation("meridian_debug", "debug");
    static final LimbLensItem LIMB_LENS = new LimbLensItem(new net.minecraft.world.item.Item.Properties().stacksTo(1));
    static final AggroLinkItem AGGRO_LINK = new AggroLinkItem(new net.minecraft.world.item.Item.Properties().stacksTo(1));

    private DebugItems() {
        // 物品注册只发生一次，世界切换不会重建注册表对象。
    }

    static void register() {
        // 物品先注册，再以同一个实例作为创造栏图标和条目。
        Registry.register(BuiltInRegistries.ITEM, LENS_ID, LIMB_LENS);
        Registry.register(BuiltInRegistries.ITEM, AGGRO_LINK_ID, AGGRO_LINK);
        CreativeModeTab group = FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.meridian_debug.debug"))
                .icon(() -> new ItemStack(LIMB_LENS))
                .displayItems((context, entries) -> {
                    entries.accept(LIMB_LENS);
                    entries.accept(AGGRO_LINK);
                })
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, GROUP_ID, group);
    }
}
