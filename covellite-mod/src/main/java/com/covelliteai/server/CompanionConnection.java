package com.covelliteai.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.ProtocolInfo;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link Connection} that never owns a Netty channel.
 *
 * <p>It exists because {@code ServerPlayer.connection} is not nullable and the
 * vanilla join sequence needs a connection object. Every outbound packet is
 * discarded, so the artificial player never generates real traffic and never
 * queues packets for a client that does not exist.</p>
 *
 * <p>This mirrors the approach used by NeoForge's {@code FakePlayer} instead of
 * building a synthetic Netty pipeline. An {@code EmbeddedChannel}-based
 * connection would require recreating the vanilla codec pipeline (splitter,
 * decoder, prepender, encoder, bundler) through private internals, which is
 * far more fragile and pointless: we deliberately want to drop the bytes.</p>
 */
public final class CompanionConnection extends Connection {

    private static final SocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);

    /**
     * NeoForge stores negotiated payload information in Netty channel
     * attributes, so a non-null channel object is required even though no
     * bytes will ever be written. This in-memory channel only carries
     * attributes; it has no codec pipeline and is never used for I/O.
     */
    private final Channel attributeStore = new EmbeddedChannel();
    private final AtomicLong discardedPackets = new AtomicLong();

    public CompanionConnection() {
        super(PacketFlow.SERVERBOUND);

        // The companion has no client, but other mods still react to the
        // player login event and try to send their configuration payloads to
        // it. NeoForge rejects those sends unless the connection negotiated
        // them, which aborts the whole login event dispatch.
        //
        // configureMockConnection is NeoForge's own supported way to model a
        // fully compatible counterpart (used by its game tests): it records
        // every registered payload as available on this connection. The
        // packets still never leave the process, because send() below drops
        // them; this only makes the checks pass and keeps other mods' login
        // handlers working.
        try {
            NetworkRegistry.configureMockConnection(this);
        } catch (RuntimeException e) {
            // Never let a NeoForge internal helper prevent the companion from
            // joining. The listener replacement handles normal traffic; this
            // path only matters for mods that sync during login.
            com.covelliteai.CovelliteAI.LOGGER.warn(
                    "Could not configure mock payload channels for companion connection", e);
        }
    }

    /**
     * Number of outbound packets intentionally dropped. Used to verify that
     * the whole vanilla join sequence ran through this connection.
     */
    public long discardedPackets() {
        return this.discardedPackets.get();
    }

    @Override
    public Channel channel() {
        return this.attributeStore;
    }

    // ------------------------------------------------------------------
    // Outbound traffic is intentionally discarded
    // ------------------------------------------------------------------

    @Override
    public void send(Packet<?> packet) {
        this.discardedPackets.incrementAndGet();
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        this.discardedPackets.incrementAndGet();
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
        this.discardedPackets.incrementAndGet();
    }

    @Override
    public void flushChannel() {
        // Nothing to flush.
    }

    // ------------------------------------------------------------------
    // Protocol setup is a no-op: there is no channel pipeline to configure
    // ------------------------------------------------------------------

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        // No inbound protocol: no client can send packets to this connection.
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
        // No outbound protocol: nothing is serialized.
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
        // Never used by the companion.
    }

    // ------------------------------------------------------------------
    // Connection state
    // ------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        // A synthetic connection is always "up"; this prevents callers from
        // treating the companion as a disconnected client.
        return true;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return LOOPBACK;
    }

    @Override
    public String getLoggableAddress(boolean logIps) {
        return "companion";
    }

    @Override
    public void disconnect(Component reason) {
        // The companion cannot be disconnected by network events.
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        // The companion cannot be disconnected by network events.
    }

    @Override
    public void tick() {
        // The companion is ticked by CompanionManager, not by the network loop.
    }
}
