package ru.naviyosha.mixin;

import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.naviyosha.NaviYosha;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SignPacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
    private void phantom2life$signReply(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        if (NaviYosha.acceptSignPacket(player, packet)) ci.cancel();
    }
}
