package com.covelliteai.server;

import java.util.Collections;
import java.util.Set;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import org.jetbrains.annotations.Nullable;

/**
 * Network listener for the artificial player.
 *
 * <p>It deliberately does <strong>not</strong> run the vanilla
 * {@link ServerGamePacketListenerImpl#tick()} implementation. That
 * implementation expects a real client: it resets the player to the last
 * "good" client position, counts floating ticks, sends keep-alives and kicks
 * idle players. None of those apply here.</p>
 *
 * <p>Instead, the listener only advances the player simulation. Position,
 * rotation and movement are driven server-side by {@link CompanionController}
 * and broadcast to real clients through the normal entity tracker, exactly
 * like any other player.</p>
 */
public final class CompanionPacketListener extends ServerGamePacketListenerImpl {

    public CompanionPacketListener(MinecraftServer server, Connection connection,
                                   ServerPlayer player, CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    /**
     * One server tick of the artificial player. Called explicitly by
     * {@link CompanionManager} from {@code ServerTickEvent.Post}.
     */
    @Override
    public void tick() {
        this.player.doTick();
    }

    @Override
    public void resetPosition() {
        // No client position to validate; position is authoritative on the server.
    }

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        teleport(x, y, z, yaw, pitch, Collections.emptySet());
    }

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch,
                         Set<RelativeMovement> relativeMovements) {
        // Apply immediately on the server instead of waiting for a client
        // acknowledgement that would never arrive.
        this.player.absMoveTo(x, y, z, yaw, pitch);
        this.player.setYHeadRot(yaw);
    }

    @Override
    public void send(Packet<?> packet) {
        // Discarded: the companion has no client.
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        // Discarded: the companion has no client.
    }

    @Override
    public void disconnect(Component reason) {
        // Network disconnects do not apply to the companion.
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        // Never triggered: the companion is removed explicitly by CompanionManager.
    }

    @Override
    public boolean isAcceptingMessages() {
        return true;
    }

}
