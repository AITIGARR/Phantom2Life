package ru.naviyosha;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityType;
import net.minecraft.core.Registry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WoodType;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Two separate registered server-side sign BLOCKS (standing + wall) and one
 * registered sign ITEM. Polymer maps their block states to vanilla pale-oak
 * invisible structure_void states for vanilla clients; the real server IDs persist.
 *
 * The block and its SignBlockEntity are real. On clients that cannot render
 * transparent sign textures, lightweight helpers show only text and accept
 * interactions. No sign boards are drawn by the client.
 */
public final class PhantomSignBlocks {
    public static final String ID = "naviyosha";
    public static final Block SIGN = registerBlock("invisible_sign", Blocks.PALE_OAK_SIGN, properties -> new Standing(properties));
    public static final Block WALL_SIGN = registerBlock("invisible_wall_sign", Blocks.PALE_OAK_WALL_SIGN, properties -> new Wall(properties));
    public static final Item SIGN_ITEM = registerItem();

    private PhantomSignBlocks() {}

    public static boolean isOurBlock(BlockState state) {
        return state.is(SIGN) || state.is(WALL_SIGN);
    }

    private static Block registerBlock(String path, Block base, java.util.function.Function<BlockBehaviour.Properties, Block> creator) {
        var id = Identifier.fromNamespaceAndPath(ID, path);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return Registry.register(BuiltInRegistries.BLOCK, key,
            creator.apply(BlockBehaviour.Properties.ofFullCopy(base).setId(key)));
    }

    private static Item registerItem() {
        var id = Identifier.fromNamespaceAndPath(ID, "invisible_sign");
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        var item = new PolymerSign(SIGN, WALL_SIGN, new Item.Properties().setId(key).useBlockDescriptionPrefix());
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    public static void initialize() {
        // SignBlock's vanilla block entity type must also be valid for our
        // distinct server-side sign blocks. This keeps all native sign text,
        // double-sided editing, wax, dye and glow-ink handling intact.
        ((FabricBlockEntityType) BlockEntityType.SIGN).addSupportedBlock(SIGN);
        ((FabricBlockEntityType) BlockEntityType.SIGN).addSupportedBlock(WALL_SIGN);
    }

    public static final class Standing extends StandingSignBlock implements PolymerBlock {
        public Standing(BlockBehaviour.Properties properties) {
            super(WoodType.PALE_OAK, properties);
        }

        @Override
        public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
            return Blocks.STRUCTURE_VOID.defaultBlockState();
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            if (type != BlockEntityType.SIGN) return null;
            return (world, pos, blockState, blockEntity) -> {
                if (blockEntity instanceof SignBlockEntity sign) {
                    SignBlockEntity.tick(world, pos, blockState, sign);
                    if (world instanceof ServerLevel server) {
                        PhantomSignVisuals.tick(server, pos, sign, blockState);
                    }
                }
            };
        }
    }

    public static final class Wall extends WallSignBlock implements PolymerBlock {
        public Wall(BlockBehaviour.Properties properties) {
            super(WoodType.PALE_OAK, properties);
        }

        @Override
        public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
            return Blocks.STRUCTURE_VOID.defaultBlockState();
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            if (type != BlockEntityType.SIGN) return null;
            return (world, pos, blockState, blockEntity) -> {
                if (blockEntity instanceof SignBlockEntity sign) {
                    SignBlockEntity.tick(world, pos, blockState, sign);
                    if (world instanceof ServerLevel server) {
                        PhantomSignVisuals.tick(server, pos, sign, blockState);
                    }
                }
            };
        }
    }

    public static final class PolymerSign extends SignItem implements PolymerItem {
        public PolymerSign(Block standingBlock, Block wallBlock, Item.Properties properties) {
            super(standingBlock, wallBlock, properties);
        }

        @Override
        protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, Player player,
                ItemStack itemStack, BlockState placedState) {
            // SignItem ordinarily opens the editor immediately after placing.
            // A vanilla client only sees structure_void for this Polymer block,
            // so opening here targets a non-sign and fails. The server tick
            // opens the editor with a temporary real-position sign packet.
            if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                // Exact successful vanilla placement coordinates, no guessing
                // from the clicked support block / ray on a mobile client.
                PhantomSignVisuals.onPlaced(sp, pos);
            }
            return true;
        }

        @Override
        public Item getPolymerItem(ItemStack stack, PacketContext context) {
            return Items.PALE_OAK_SIGN;
        }
    }
}
