package ru.naviyosha;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Only the client-side visualization uses text_display + interaction.
 * A genuine naviyosha:invisible_sign / invisible_wall_sign Block with the
 * vanilla SignBlockEntity is still stored in the world and remains the source
 * of truth for text, dye, glow, wax and persistent data.
 *
 * Polymer transmits minecraft:structure_void as the client block state, which
 * is invisible even on clients that ignore sign-texture alpha / shader fixes.
 */
public final class PhantomSignVisuals {
    private static final String HIT = "p2l-polymer-hit";
    private static final String FACE_FRONT = "p2l-polymer-front";
    private static final String FACE_BACK = "p2l-polymer-back";
    private static final Map<UUID, Edit> EDITS = new ConcurrentHashMap<>();
    private static final List<Pending> PLACEMENTS = new ArrayList<>();
    private static int ticks;

    private record Edit(BlockPos proxy, BlockPos actual, String dimension, boolean front, int expire) {}
    private record Pending(UUID player, BlockPos target, String dimension, int expire) {}

    private PhantomSignVisuals() {}

    public static void initialize() {
        // Mobile clients can treat the Polymer replacement as air and fail to
        // target the Interaction hitbox. Handle the normal right-click packet
        // by tracing against the REAL server-side SignBlock outline.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(world instanceof ServerLevel) || !(player instanceof ServerPlayer sp)
                || player.isSpectator()) return InteractionResult.PASS;
            return tryOpenFromRay(sp, hand);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)
                || hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
            InteractionResult edit = tryOpenFromRay(sp, hand);
            if (edit != InteractionResult.PASS) return edit;
            if (!player.getItemInHand(hand).is(PhantomSignBlocks.SIGN_ITEM)) return InteractionResult.PASS;
            BlockPos clicked = hit.getBlockPos();
            BlockPos target = level.getBlockState(clicked).canBeReplaced()
                ? clicked : clicked.relative(hit.getDirection());
            PLACEMENTS.add(new Pending(sp.getUUID(), target.immutable(),
                level.dimension().identifier().toString(), ticks + 20));
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)
                || !(entity instanceof Interaction target) || !target.getTags().contains(HIT)) {
                return InteractionResult.PASS;
            }
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
            BlockPos pos = target.blockPosition();
            if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)
                || !PhantomSignBlocks.isOurBlock(level.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            if (sp.distanceToSqr(pos.getCenter()) > 36) return InteractionResult.FAIL;

            boolean front = sign.isFacingFrontText(sp);
            var held = player.getItemInHand(hand);
            if (held.is(Items.HONEYCOMB)) {
                if (sign.setWaxed(true) && !sp.getAbilities().instabuild) held.shrink(1);
                return InteractionResult.SUCCESS;
            }
            if (sign.isWaxed()) return InteractionResult.SUCCESS;
            if (held.getItem() instanceof DyeItem dye) {
                if (sign.setText(sign.getText(front).setColor(dye.getDyeColor()), front)
                    && !sp.getAbilities().instabuild) held.shrink(1);
                sync(level, pos, sign, level.getBlockState(pos));
            } else if (held.is(Items.GLOW_INK_SAC) || held.is(Items.INK_SAC)) {
                boolean glow = held.is(Items.GLOW_INK_SAC);
                if (sign.setText(sign.getText(front).setHasGlowingText(glow), front)
                    && !sp.getAbilities().instabuild) held.shrink(1);
                sync(level, pos, sign, level.getBlockState(pos));
            } else {
                openEditor(sp, pos, sign, front);
            }
            return InteractionResult.SUCCESS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)
                || !(entity instanceof Interaction target) || !target.getTags().contains(HIT))
                return InteractionResult.PASS;
            BlockPos pos = target.blockPosition();
            if (sp.distanceToSqr(pos.getCenter()) > 36) return InteractionResult.FAIL;
            if (PhantomSignBlocks.isOurBlock(level.getBlockState(pos))) {
                level.destroyBlock(pos, !sp.getAbilities().instabuild, sp);
            }
            remove(level, pos);
            return InteractionResult.SUCCESS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticks++;
            // A real sign is placed by vanilla after the UseBlockCallback.
            // Open the virtual editor only when the real server block exists.
            for (int i = PLACEMENTS.size() - 1; i >= 0; i--) {
                Pending pending = PLACEMENTS.get(i);
                if (pending.expire() < ticks) { PLACEMENTS.remove(i); continue; }
                ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
                if (player == null || !player.level().dimension().identifier().toString().equals(pending.dimension()))
                    continue;
                ServerLevel level = player.level();
                if (!PhantomSignBlocks.isOurBlock(level.getBlockState(pending.target()))) continue;
                if (level.getBlockEntity(pending.target()) instanceof SignBlockEntity sign) {
                    sync(level, pending.target(), sign, level.getBlockState(pending.target()));
                    openEditor(player, pending.target(), sign, true);
                    PLACEMENTS.remove(i);
                }
            }
            if (ticks % 20 != 0) return;
            EDITS.entrySet().removeIf(e -> e.getValue().expire() < ticks);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerLevel level = player.level();
                for (Interaction obj : level.getEntitiesOfClass(Interaction.class,
                    player.getBoundingBox().inflate(40), e -> e.getTags().contains(HIT))) {
                    BlockPos pos = obj.blockPosition();
                    if (!PhantomSignBlocks.isOurBlock(level.getBlockState(pos))) {
                        remove(level, pos);
                    }
                }
            }
        });
    }

    /** Called by the server SignBlockEntity ticker, including after world reload. */
    public static void tick(ServerLevel level, BlockPos pos, SignBlockEntity sign, BlockState state) {
        if (level.getGameTime() % 20 == 0) sync(level, pos, sign, state);
    }

    /** Idempotent creation and synchronization from the actual SignBlockEntity. */
    private static void sync(ServerLevel level, BlockPos pos, SignBlockEntity sign, BlockState state) {
        AABB box = new AABB(pos).inflate(1.2);
        List<Interaction> hits = level.getEntitiesOfClass(Interaction.class, box, e -> e.getTags().contains(HIT) && e.blockPosition().equals(pos));
        if (hits.isEmpty()) {
            Interaction target = new Interaction(EntityType.INTERACTION, level);
            // EntityDimensions starts at the entity's Y coordinate, not its centre.
            // The previous Y+0.5 placed the clickable area half a block too high.
            target.setPos(pos.getX() + .5, pos.getY() + .02, pos.getZ() + .5);
            target.setWidth(.94f);
            target.setHeight(.96f);
            target.addTag(HIT);
            level.addFreshEntity(target);
        } else {
            // Reposition pre-existing hitboxes from 1.5.0 without replacing
            // any stored sign or forcing players to break decorations.
            Interaction target = hits.get(0);
            if (Math.abs(target.getY() - (pos.getY() + .02)) > .001) {
                target.setPos(pos.getX() + .5, pos.getY() + .02, pos.getZ() + .5);
                target.setWidth(.94f);
                target.setHeight(.96f);
            }
        }
        syncFace(level, pos, state, sign.getFrontText(), true);
        syncFace(level, pos, state, sign.getBackText(), false);
    }

    private static void syncFace(ServerLevel level, BlockPos pos, BlockState state, SignText text, boolean front) {
        String tag = front ? FACE_FRONT : FACE_BACK;
        List<Display.TextDisplay> displays = level.getEntitiesOfClass(Display.TextDisplay.class,
            new AABB(pos).inflate(1.2), e -> e.getTags().contains(tag) && e.blockPosition().equals(pos));
        Display.TextDisplay display = displays.isEmpty() ? null : displays.get(0);
        boolean created = display == null;
        if (created) {
            display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
            display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
            display.setBackgroundColor(0);
            display.addTag(tag);
        }
        // Recalculate the visual transform on every sync, including for
        // already-placed 1.5.0 signs. The former Y + 0.76 was too high.
        float yaw = state.getBlock() instanceof StandingSignBlock
            ? ((StandingSignBlock) state.getBlock()).getYRotationDegrees(state)
            : state.getValue(WallSignBlock.FACING).toYRot();
        if (!front) yaw += 180f;
        double radians = Math.toRadians(yaw);
        double offset = state.getBlock() instanceof StandingSignBlock ? .081 : .45;
        double x = pos.getX() + .5 - Math.sin(radians) * offset;
        double y = pos.getY() + (state.getBlock() instanceof StandingSignBlock ? .50 : .48);
        double z = pos.getZ() + .5 + Math.cos(radians) * offset;
        if (Math.abs(display.getX() - x) > .001
            || Math.abs(display.getY() - y) > .001
            || Math.abs(display.getZ() - z) > .001) {
            display.setPos(x, y, z);
        }
        if (Math.abs(display.getYRot() - yaw) > .1f) display.setYRot(yaw);

        Component message = Component.empty();
        for (int i = 0; i < 4; i++) {
            if (i != 0) message = message.copy().append(Component.literal("\n"));
            message = message.copy().append(text.getMessage(i, false).copy()
                .withStyle(style -> style.withColor(text.getColor().getTextColor())));
        }
        if (!display.getText().equals(message)) display.setText(message);
        // Entity must enter the correct chunk at the final position.
        if (created) level.addFreshEntity(display);
    }

    private static InteractionResult tryOpenFromRay(ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        HitResult ray = player.pick(5.0, 0.0f, false);
        if (!(ray instanceof BlockHitResult hit)) return InteractionResult.PASS;
        ServerLevel level = player.level();
        BlockPos pos = hit.getBlockPos();
        if (!PhantomSignBlocks.isOurBlock(level.getBlockState(pos))
            || !(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return InteractionResult.PASS;
        }
        if (sign.isWaxed()) return InteractionResult.SUCCESS;
        openEditor(player, pos, sign, sign.isFacingFrontText(player));
        return InteractionResult.SUCCESS;
    }

    private static void openEditor(ServerPlayer player, BlockPos pos, SignBlockEntity sign, boolean front) {
        if (sign.isWaxed()) return;
        ServerLevel level = player.level();
        // Open the GUI at the real sign position. The old proxy under the
        // player's feet was frequently overwritten by chunk/block packets.
        // This is a client-only temporary vanilla sign; the server block is
        // still the real Polymer sign with its persistent SignBlockEntity.
        BlockPos proxy = pos.immutable();
        var state = Blocks.OAK_SIGN.defaultBlockState();
        SignBlockEntity fake = new SignBlockEntity(proxy, state);
        fake.setLevel(level);
        fake.setText(sign.getText(front), true);
        EDITS.put(player.getUUID(), new Edit(proxy, pos.immutable(),
            level.dimension().identifier().toString(), front, ticks + 1200));
        player.connection.send(new ClientboundBlockUpdatePacket(proxy, state));
        player.connection.send(ClientboundBlockEntityDataPacket.create(fake));
        player.connection.send(new ClientboundOpenSignEditorPacket(proxy, true));
    }

    /** Returns true if this is a proxy-editor response for our real server block. */
    public static boolean acceptSignPacket(ServerPlayer player, ServerboundSignUpdatePacket packet) {
        Edit session = EDITS.get(player.getUUID());
        if (session == null || !session.proxy().equals(packet.getPos())) return false;
        EDITS.remove(player.getUUID());
        player.level().getServer().execute(() -> {
            ServerLevel level = player.level();
            if (session.dimension().equals(level.dimension().identifier().toString())
                && player.distanceToSqr(session.actual().getCenter()) <= 36
                && PhantomSignBlocks.isOurBlock(level.getBlockState(session.actual()))
                && level.getBlockEntity(session.actual()) instanceof SignBlockEntity sign
                && !sign.isWaxed()) {
                SignText text = sign.getText(session.front());
                String[] lines = packet.getLines();
                for (int i = 0; i < 4; i++) {
                    String value = i < lines.length ? lines[i] : "";
                    text = text.setMessage(i, Component.literal(value));
                }
                sign.setText(text, session.front());
                sync(level, session.actual(), sign, level.getBlockState(session.actual()));
            }
            // Never send the custom block registry ID to an unmodded client.
            // Restore Polymer's actual vanilla-visible representation.
            player.connection.send(new ClientboundBlockUpdatePacket(
                session.proxy(), Blocks.STRUCTURE_VOID.defaultBlockState()));
        });
        return true;
    }

    private static void remove(ServerLevel level, BlockPos pos) {
        AABB box = new AABB(pos).inflate(1.2);
        for (Interaction entity : level.getEntitiesOfClass(Interaction.class,
            box, e -> e.getTags().contains(HIT))) entity.discard();
        for (Display.TextDisplay entity : level.getEntitiesOfClass(Display.TextDisplay.class,
            box, e -> e.blockPosition().equals(pos) && (e.getTags().contains(FACE_FRONT) || e.getTags().contains(FACE_BACK)))) entity.discard();
    }
}
