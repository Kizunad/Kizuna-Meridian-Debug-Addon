package dev.kizuna.meridian.debug.fabric;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** 调试库物品和独立创造栏页。 */
final class DebugItems {
    static final Identifier LENS_ID = new Identifier("meridian_debug", "limb_lens");
    static final Identifier AGGRO_LINK_ID = new Identifier("meridian_debug", "aggro_link");
    static final Identifier GROUP_ID = new Identifier("meridian_debug", "debug");
    static final LimbLensItem LIMB_LENS = new LimbLensItem(new net.minecraft.item.Item.Settings().maxCount(1));
    static final AggroLinkItem AGGRO_LINK = new AggroLinkItem(new net.minecraft.item.Item.Settings().maxCount(1));

    private DebugItems() {
        // 物品注册只发生一次，世界切换不会重建注册表对象。
    }

    static void register() {
        // 物品先注册，再以同一个实例作为创造栏图标和条目。
        Registry.register(Registries.ITEM, LENS_ID, LIMB_LENS);
        Registry.register(Registries.ITEM, AGGRO_LINK_ID, AGGRO_LINK);
        ItemGroup group = FabricItemGroup.builder()
                .displayName(Text.translatable("itemGroup.meridian_debug.debug"))
                .icon(() -> new ItemStack(LIMB_LENS))
                .entries((context, entries) -> {
                    entries.add(LIMB_LENS);
                    entries.add(AGGRO_LINK);
                })
                .build();
        Registry.register(Registries.ITEM_GROUP, GROUP_ID, group);
    }
}
