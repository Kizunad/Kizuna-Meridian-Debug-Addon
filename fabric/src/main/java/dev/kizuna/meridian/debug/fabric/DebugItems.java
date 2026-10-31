package dev.kizuna.meridian.debug.fabric;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 调试库物品和独立创造栏页。 */
final class DebugItems {
    static final Identifier LENS_ID = Identifier.fromNamespaceAndPath("meridian_debug", "limb_lens");
    static final Identifier AGGRO_LINK_ID = Identifier.fromNamespaceAndPath("meridian_debug", "aggro_link");
    static final Identifier GROUP_ID = Identifier.fromNamespaceAndPath("meridian_debug", "debug");
    static final LimbLensItem LIMB_LENS = new LimbLensItem(properties(LENS_ID));
    static final AggroLinkItem AGGRO_LINK = new AggroLinkItem(properties(AGGRO_LINK_ID));

    private DebugItems() {
        // 物品注册只发生一次，世界切换不会重建注册表对象。
    }

    private static Item.Properties properties(Identifier id) {
        // 26.2 的物品构造时就要带注册键，语言键由它推出，与 1.20.1 按注册名推出的键相同。
        return new Item.Properties().stacksTo(1).setId(ResourceKey.create(Registries.ITEM, id));
    }

    static void register() {
        // 物品先注册，再以同一个实例作为创造栏图标和条目。
        Registry.register(BuiltInRegistries.ITEM, LENS_ID, LIMB_LENS);
        Registry.register(BuiltInRegistries.ITEM, AGGRO_LINK_ID, AGGRO_LINK);
        CreativeModeTab group = FabricCreativeModeTab.builder()
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
