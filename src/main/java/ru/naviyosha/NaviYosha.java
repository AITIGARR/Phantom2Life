package ru.naviyosha;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import java.util.List;
import java.util.UUID;

/** Server-only invisible sign: persistent vanilla display entities, no block overrides. */
public final class NaviYosha implements ModInitializer {
    private static final String MARK = "p2l-sign";
    private static final String LINK = "p2l-link-";
    private static final String WOOD = "p2l-wood-";
    private static final String WAX = "p2l-waxed";

    @Override public void onInitialize() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            ItemStack stack = player.getItemInHand(hand);
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            if (data == null || !"sign".equals(data.copyTag().getString("phantom2life_kind").orElse(""))) return InteractionResult.PASS;
            Direction face = hit.getDirection();
            var support = hit.getBlockPos();
            if (!level.getBlockState(support).isSolid()) return InteractionResult.FAIL;
            var place = support.relative(face);
            if (!level.getBlockState(place).isAir()) return InteractionResult.FAIL;
            String wood = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            String link = LINK + UUID.randomUUID();
            double x = place.getX() + .5, y = place.getY() + (face == Direction.UP ? .35 : .5), z = place.getZ() + .5;
            ServerLevel server = (ServerLevel) level;
            Display.TextDisplay text = new Display.TextDisplay(EntityType.TEXT_DISPLAY, server);
            text.setPos(x, y, z);
            text.setBillboardConstraints(Display.BillboardConstraints.CENTER);
            text.setBackgroundColor(0);
            text.setText(Component.literal(""));
            text.addTag(MARK); text.addTag(link);
            Interaction target = new Interaction(EntityType.INTERACTION, server);
            target.setPos(x, y, z); target.setWidth(.9f); target.setHeight(.6f);
            target.addTag(MARK); target.addTag(link); target.addTag(WOOD + wood);
            server.addFreshEntity(text); server.addFreshEntity(target);
            if (!player.getAbilities().instabuild) stack.shrink(1);
            return InteractionResult.SUCCESS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(entity instanceof Interaction target) || !target.getTags().contains(MARK)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
            ItemStack held = player.getItemInHand(hand);
            if (held.is(Items.HONEYCOMB)) {
                if (target.addTag(WAX) && !player.getAbilities().instabuild) held.shrink(1);
                return InteractionResult.SUCCESS;
            }
            if (target.getTags().contains(WAX)) return InteractionResult.SUCCESS;
            Display.TextDisplay text = paired((ServerLevel)level, target);
            if (text == null) return InteractionResult.SUCCESS;
            if (held.getItem() instanceof DyeItem dye) {
                int rgb = dye.getDyeColor().getTextColor();
                text.setText(text.getText().copy().withStyle(style -> style.withColor(rgb)));
                if (!player.getAbilities().instabuild) held.shrink(1);
            } else if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(Component.literal("Текст: /p2l text <сообщение>. Цвет: краситель. Воск: медовые соты."), false);
            }
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(entity instanceof Interaction target) || !target.getTags().contains(MARK)) return InteractionResult.PASS;
            Display.TextDisplay text = paired((ServerLevel) level, target);
            if (text != null) text.discard();
            String wood = target.getTags().stream().filter(s -> s.startsWith(WOOD)).findFirst().orElse(WOOD + "oak_sign").substring(WOOD.length());
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(wood));
            ItemStack drop = new ItemStack(item == null ? Items.OAK_SIGN : item);
            CompoundTag tag = new CompoundTag(); tag.putString("phantom2life_kind", "sign");
            drop.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            drop.set(DataComponents.CUSTOM_NAME, Component.literal("Невидимая табличка"));
            drop.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Декор из фантомной мембраны"))));
            drop.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            if (!player.getAbilities().instabuild) target.spawnAtLocation((ServerLevel)level, drop);
            target.discard();
            return InteractionResult.SUCCESS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("p2l").then(Commands.literal("text")
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    String message = StringArgumentType.getString(ctx, "message");
                    Interaction target = player.level().getEntitiesOfClass(Interaction.class,
                        player.getBoundingBox().inflate(5), e -> e.getTags().contains(MARK))
                        .stream().min((a,b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player))).orElse(null);
                    if (target == null || target.getTags().contains(WAX)) return 0;
                    Display.TextDisplay text = paired(player.level(), target);
                    if (text == null) return 0;
                    text.setText(Component.literal(message).withStyle(text.getText().getStyle()));
                    ctx.getSource().sendSuccess(() -> Component.literal("Текст таблички обновлён"), false);
                    return 1;
                })))));
    }

    private static Display.TextDisplay paired(ServerLevel level, Interaction target) {
        String link = target.getTags().stream().filter(s -> s.startsWith(LINK)).findFirst().orElse("");
        if (link.isEmpty()) return null;
        return level.getEntitiesOfClass(Display.TextDisplay.class, new AABB(target.blockPosition()).inflate(2),
            e -> e.getTags().contains(link)).stream().findFirst().orElse(null);
    }
}
