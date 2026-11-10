package com.covelliteai.server;

import java.util.Locale;

import com.covelliteai.CovelliteAI;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Opt-in, single-run verification scenario for the integrated server.
 *
 * <p>It only activates when the file {@code config/covelliteai-prototype-test.flag}
 * exists. On the host's first login it teleports the companion next to the
 * player, tries to walk it in several directions until one is not blocked,
 * rotates it, jumps, despawns and respawns it, logging every step. This exists
 * only to validate the prototype; it is not autonomy and it is removed once the
 * prototype is accepted.</p>
 */
public final class PrototypeScenario {

    private static final String PREFIX = "[PROTOTYPE] ";
    private static final float[] DIRECTIONS = { 0.0F, 90.0F, 180.0F, 270.0F };
    private static final int MOVE_TICKS = 25;
    private static final double MIN_MOVE_DISTANCE = 1.0D;

    private final CompanionManager manager;

    private ServerPlayer host;
    private boolean started;
    private boolean finished;
    private boolean despawned;
    private int phase;
    private int phaseTicks;
    private int moveAttempt;
    private double startX;
    private double startZ;
    private double jumpBaseY;
    private double jumpMaxY;
    private boolean movementOk;
    private boolean lookOk;
    private boolean jumpOk;

    public PrototypeScenario(CompanionManager manager) {
        this.manager = manager;
    }

    public void onPlayerLogin(ServerPlayer player, MinecraftServer server) {
        if (this.started || player == null) {
            return;
        }
        if (!server.isSingleplayerOwner(player.getGameProfile())) {
            return;
        }
        this.started = true;
        this.host = player;
        this.phase = 0;
        this.phaseTicks = 0;
        log("HOST_JOINED name=" + player.getGameProfile().getName()
                + " pos=" + fmt(player.getX()) + "," + fmt(player.getY()) + "," + fmt(player.getZ()));
    }

    public void tick(MinecraftServer server) {
        if (!this.started || this.finished) {
            return;
        }

        this.phaseTicks++;

        // While intentionally despawned there is no companion to inspect, and
        // that is expected: wait for the respawn phase instead of aborting.
        if (this.despawned) {
            if (this.phase == 5 && this.phaseTicks >= 20) {
                boolean spawned = this.manager.spawn(server);
                CompanionPlayer current = this.manager.player();
                log("RESPAWN ok=" + spawned + " count=" + server.getPlayerList().getPlayerCount()
                        + " pos=" + (current == null ? "null" : fmt(current.getX()) + ","
                        + fmt(current.getY()) + "," + fmt(current.getZ())));
                this.despawned = false;
                this.phase = 6;
                this.phaseTicks = 0;
            }
            return;
        }

        CompanionPlayer companion = this.manager.player();
        if (companion == null) {
            log("ABORT companion_missing phase=" + this.phase);
            this.finished = true;
            return;
        }

        switch (this.phase) {
            case 0 -> {
                if (this.phaseTicks >= 20) {
                    boolean moved = this.manager.teleportTo(this.host);
                    log("TPHERE ok=" + moved + " companion=" + fmt(companion.getX()) + ","
                            + fmt(companion.getY()) + "," + fmt(companion.getZ()));
                    log("STATE " + this.manager.state());
                    this.moveAttempt = 0;
                    beginMove(companion);
                    this.phase = 1;
                    this.phaseTicks = 0;
                }
            }
            case 1 -> {
                if (this.phaseTicks >= MOVE_TICKS) {
                    double dx = companion.getX() - this.startX;
                    double dz = companion.getZ() - this.startZ;
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    log(String.format(Locale.ROOT,
                            "MOVE_END attempt=%d yaw=%.1f distance=%.3f pos=%.3f,%.3f,%.3f on_ground=%s",
                            this.moveAttempt, DIRECTIONS[this.moveAttempt], distance,
                            companion.getX(), companion.getY(), companion.getZ(),
                            companion.onGround()));
                    if (distance > MIN_MOVE_DISTANCE) {
                        this.movementOk = true;
                        log("MOVEMENT_OK=true");
                        this.phase = 2;
                        this.phaseTicks = 0;
                    } else if (this.moveAttempt + 1 < DIRECTIONS.length) {
                        this.moveAttempt++;
                        beginMove(companion);
                        this.phaseTicks = 0;
                    } else {
                        log("MOVEMENT_OK=false reason=blocked_in_all_directions");
                        this.phase = 2;
                        this.phaseTicks = 0;
                    }
                }
            }
            case 2 -> {
                if (this.phaseTicks == 1) {
                    this.manager.controller().look(90.0F, 30.0F);
                } else if (this.phaseTicks == 2) {
                    log("LOOK yaw=" + fmt(companion.getYRot()) + " pitch=" + fmt(companion.getXRot()));
                    this.lookOk = Math.abs(companion.getYRot() - 90.0F) < 0.01F
                            && Math.abs(companion.getXRot() - 30.0F) < 0.01F;
                    log("LOOK_OK=" + this.lookOk);
                    this.phase = 3;
                    this.phaseTicks = 0;
                }
            }
            case 3 -> {
                if (this.phaseTicks == 1) {
                    this.jumpBaseY = companion.getY();
                    this.jumpMaxY = companion.getY();
                    this.manager.controller().jump();
                } else if (this.phaseTicks > 1 && this.phaseTicks <= 12) {
                    this.jumpMaxY = Math.max(this.jumpMaxY, companion.getY());
                } else if (this.phaseTicks >= 13) {
                    this.jumpOk = this.jumpMaxY > this.jumpBaseY + 0.3D;
                    log("JUMP base_y=" + fmt(this.jumpBaseY) + " max_y=" + fmt(this.jumpMaxY)
                            + " on_ground_now=" + companion.onGround() + " ok=" + this.jumpOk);
                    this.phase = 4;
                    this.phaseTicks = 0;
                }
            }
            case 4 -> {
                if (this.phaseTicks == 1) {
                    log("DESPAWN_BEFORE_COUNT=" + server.getPlayerList().getPlayerCount());
                    this.manager.despawn(server);
                    log("DESPAWN_AFTER_COUNT=" + server.getPlayerList().getPlayerCount());
                    log("DESPAWN_OK=" + (this.manager.player() == null));
                    this.despawned = true;
                    this.phase = 5;
                    this.phaseTicks = 0;
                }
            }
            case 6 -> {
                if (this.phaseTicks >= 20) {
                    this.manager.teleportTo(this.host);
                    log("DUPLICATE_CHECK " + this.manager.state());
                    log("RESULT movement=" + this.movementOk + " look=" + this.lookOk
                            + " jump=" + this.jumpOk);
                    log("SCENARIO_DONE");
                    this.finished = true;
                }
            }
            default -> {
                // No action.
            }
        }
    }

    private void beginMove(CompanionPlayer companion) {
        float yaw = DIRECTIONS[this.moveAttempt];
        this.manager.controller().look(yaw, 0.0F);
        this.manager.controller().move(1.0, 0.0, false, MOVE_TICKS);
        this.startX = companion.getX();
        this.startZ = companion.getZ();
        log("MOVE_START attempt=" + this.moveAttempt + " yaw=" + yaw
                + " pos=" + fmt(this.startX) + "," + fmt(this.startZ));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void log(String message) {
        CovelliteAI.LOGGER.info(PREFIX + message);
    }
}
