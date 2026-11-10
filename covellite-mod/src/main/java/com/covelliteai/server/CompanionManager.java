package com.covelliteai.server;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.covelliteai.CovelliteAI;
import com.covelliteai.CovelliteConfig;
import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;

/**
 * Owns the lifecycle of the artificial player inside the integrated server.
 *
 * <p>The join sequence deliberately uses {@link PlayerList#placeNewPlayer}
 * instead of manually touching {@code players}, the entity tracker or the
 * chunk map. That single call performs the same work a real login does:
 * playerdata load, permission level, scoreboard, player-info broadcast to the
 * human client and entity tracking. The only replacement is the network
 * listener, which is swapped after the join so the companion ticks without a
 * client attached.</p>
 */
public final class CompanionManager {

    private final CovelliteConfig config;
    private final CompanionController controller = new CompanionController();

    /** Ticks until an automatic respawn happens; negative means "not scheduled". */
    private static final int RESPAWN_DELAY_TICKS = 60;

    private CompanionPlayer player;
    private CompanionPacketListener listener;
    private CompanionConnection connection;
    private MinecraftServer server;
    private int respawnTicks = -1;

    public CompanionManager(CovelliteConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public boolean spawn(MinecraftServer server) {
        this.server = server;
        if (this.player != null) {
            CovelliteAI.LOGGER.info("Companion already present; spawn ignored");
            return false;
        }
        if (server == null || !server.isRunning()) {
            CovelliteAI.LOGGER.warn("Cannot spawn companion: server is not running");
            return false;
        }
        if (!isIntegratedServer(server)) {
            // Hard architectural guard: CovelliteAI only lives inside the
            // singleplayer integrated server. It must never create a presence
            // on a dedicated or otherwise separate server.
            CovelliteAI.LOGGER.info(
                    "Companion not spawned: server is not an IntegratedServer (class={}, singleplayer={})",
                    server.getClass().getName(), server.isSingleplayer());
            return false;
        }

        PlayerList playerList = server.getPlayerList();
        String name = this.config.playerName;
        UUID uuid = offlineUuid(name);

        ServerPlayer existing = playerList.getPlayer(uuid);
        if (existing != null) {
            if (existing == this.player) {
                CovelliteAI.LOGGER.info("Companion already present; spawn ignored");
                return false;
            }
            // A previous death can leave a stale entry (often with health 0)
            // registered in the player list. Remove it so the fresh join wins.
            CovelliteAI.LOGGER.warn(
                    "Removing stale companion entry before spawn (alive={}, health={})",
                    existing.isAlive(), existing.getHealth());
            playerList.remove(existing);
        }

        ServerLevel level = server.overworld();
        GameProfile profile = new GameProfile(uuid, name);
        CompanionPlayer companion = new CompanionPlayer(server, level, profile);
        CompanionConnection conn = new CompanionConnection();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

        try {
            // Vanilla join: playerdata load, player-info broadcast, entity tracking.
            playerList.placeNewPlayer(conn, companion, cookie);

            // placeNewPlayer installs its own listener for the join packets. We
            // replace it so ticking is driven by CompanionManager without the
            // client-oriented position rollback and kick logic.
            CompanionPacketListener companionListener =
                    new CompanionPacketListener(server, conn, companion, cookie);
            companion.connection = companionListener;

            // Playerdata saved on death restores with zero health, which would
            // make the companion dead the instant it joins.
            if (!companion.isAlive()) {
                CovelliteAI.LOGGER.warn("Companion playerdata was dead; restoring full health");
                companion.setHealth(companion.getMaxHealth());
                companion.setRemainingFireTicks(0);
            }

            this.player = companion;
            this.listener = companionListener;
            this.connection = conn;
            this.respawnTicks = -1;
        } catch (RuntimeException e) {
            CovelliteAI.LOGGER.error("Companion spawn failed; cleaning up", e);
            ServerPlayer partial = playerList.getPlayer(uuid);
            if (partial != null) {
                playerList.remove(partial);
            }
            this.player = null;
            this.listener = null;
            this.connection = null;
            return false;
        }

        CovelliteAI.LOGGER.info(
                "Join stream completed: {} outbound packets discarded, connection connected={}",
                conn.discardedPackets(), conn.isConnected());
        CovelliteAI.LOGGER.info(
                "Companion '{}' joined at ({}, {}, {}) in {} (entity id {}, uuid {})",
                companion.getGameProfile().getName(),
                String.format("%.2f", companion.getX()),
                String.format("%.2f", companion.getY()),
                String.format("%.2f", companion.getZ()),
                companion.serverLevel().dimension().location(),
                companion.getId(),
                companion.getUUID());
        return true;
    }

    public void tick(MinecraftServer server) {
        this.server = server;
        CompanionPlayer companion = this.player;
        if (companion == null) {
            tickRespawn(server);
            return;
        }

        // Defensive: another mod or an operator command may have removed it,
        // or the companion may have died in game.
        if (companion.isRemoved()
                || !companion.isAlive()
                || server.getPlayerList().getPlayer(companion.getUUID()) != companion) {
            CovelliteAI.LOGGER.warn("Companion is no longer registered; scheduling respawn");
            // Revive before removing so the saved playerdata is not stored dead.
            if (!companion.isAlive() && !companion.isRemoved()) {
                companion.setHealth(companion.getMaxHealth());
            }
            if (server.getPlayerList().getPlayer(companion.getUUID()) == companion) {
                server.getPlayerList().remove(companion);
            }
            clearReferences();
            this.controller.stop();
            this.respawnTicks = RESPAWN_DELAY_TICKS;
            return;
        }

        this.controller.tick(companion);
        CompanionPacketListener companionListener = this.listener;
        if (companionListener != null) {
            companionListener.tick();
        }
    }

    /**
     * Brings the companion back a few seconds after it died or was removed by
     * something else. An explicit {@link #despawn(MinecraftServer)} is honoured:
     * it clears the scheduled respawn.
     */
    private void tickRespawn(MinecraftServer server) {
        if (this.respawnTicks < 0) {
            return;
        }
        if (server == null || !server.isRunning() || !isIntegratedServer(server)) {
            this.respawnTicks = -1;
            return;
        }
        if (--this.respawnTicks > 0) {
            return;
        }
        this.respawnTicks = -1;
        CovelliteAI.LOGGER.info("Respawning companion after death or removal");
        spawn(server);
    }

    /** Immediate respawn used by the MCP control surface. */
    public boolean respawn(MinecraftServer server) {
        despawn(server);
        return spawn(server);
    }

    public MinecraftServer server() {
        return this.server;
    }

    /**
     * Removes the companion, which saves its playerdata, statistics and
     * advancements through the vanilla player-list path.
     */
    public void despawn(MinecraftServer server) {
        CompanionPlayer companion = this.player;
        CompanionPacketListener companionListener = this.listener;
        clearReferences();
        this.respawnTicks = -1;
        this.controller.stop();

        if (companion == null) {
            return;
        }
        if (server == null) {
            CovelliteAI.LOGGER.warn("Companion despawn requested without a server instance");
            return;
        }

        ServerPlayer listed = server.getPlayerList().getPlayer(companion.getUUID());
        if (listed == companion) {
            server.getPlayerList().remove(companion);
            CovelliteAI.LOGGER.info("Companion removed from player list and saved");
        } else if (companionListener != null) {
            CovelliteAI.LOGGER.warn("Companion was not in the player list during despawn");
        }
    }

    private void clearReferences() {
        this.player = null;
        this.listener = null;
        this.connection = null;
    }

    // ------------------------------------------------------------------
    // State / control
    // ------------------------------------------------------------------

    public boolean isPresent() {
        return this.player != null;
    }

    public CompanionPlayer player() {
        return this.player;
    }

    public CompanionController controller() {
        return this.controller;
    }

    /**
     * Moves the companion next to a real player, used by the prototype command
     * to make it immediately visible.
     */
    public boolean teleportTo(ServerPlayer target) {
        CompanionPlayer companion = this.player;
        if (companion == null || target == null) {
            return false;
        }
        if (companion.serverLevel() != target.serverLevel()) {
            CovelliteAI.LOGGER.warn("Companion teleport skipped: different dimension");
            return false;
        }
        companion.teleportTo(target.serverLevel(),
                target.getX() + 1.0, target.getY(), target.getZ(),
                target.getYRot(), target.getXRot());
        return true;
    }

    public Map<String, Object> state() {
        Map<String, Object> map = new LinkedHashMap<>();
        CompanionPlayer companion = this.player;
        map.put("present", companion != null);
        map.put("expected_name", this.config.playerName);
        if (companion != null) {
            MinecraftServer server = companion.serverLevel().getServer();
            int duplicates = 0;
            for (ServerPlayer listed : server.getPlayerList().getPlayers()) {
                if (listed.getUUID().equals(companion.getUUID())) {
                    duplicates++;
                }
            }
            map.put("integrated_server", isIntegratedServer(server));
            map.put("player_list_count", server.getPlayerList().getPlayerCount());
            map.put("duplicate_count", duplicates);
            map.put("discarded_packets", this.connection == null ? 0L : this.connection.discardedPackets());
            map.put("name", companion.getGameProfile().getName());
            map.put("uuid", companion.getUUID().toString());
            map.put("entity_id", companion.getId());
            map.put("dimension", companion.serverLevel().dimension().location().toString());
            map.put("x", companion.getX());
            map.put("y", companion.getY());
            map.put("z", companion.getZ());
            map.put("yaw", companion.getYRot());
            map.put("pitch", companion.getXRot());
            map.put("health", companion.getHealth());
            map.put("alive", companion.isAlive());
            map.put("sneaking", companion.isShiftKeyDown());
            map.put("sprinting", companion.isSprinting());
            map.put("on_ground", companion.onGround());
            map.put("moving", this.controller.isMoving());
        }
        return map;
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Detects the singleplayer integrated server without referencing the
     * client-only class from common code (which would break dedicated-server
     * loading). The class-name check is explicit; the singleplayer flag is a
     * second, API-level confirmation.
     */
    private static boolean isIntegratedServer(MinecraftServer server) {
        return server != null
                && server.isSingleplayer()
                && "net.minecraft.client.server.IntegratedServer".equals(server.getClass().getName());
    }
}
