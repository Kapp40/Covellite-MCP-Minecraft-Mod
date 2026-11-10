package com.covelliteai.server;

import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side movement input for the artificial player.
 *
 * <p>{@code LivingEntity} exposes the same input fields the client writes from
 * the keyboard ({@code xxa}, {@code zza}, {@code jumping}). Writing them on the
 * server thread makes the vanilla movement code run normally, so physics,
 * collisions, friction, sneaking and jumping behave like a real player. No
 * keyboard or mouse event is synthesized.</p>
 */
public final class CompanionController {

    private double forward;
    private double strafe;
    private boolean sprint;
    private boolean jumpRequested;
    private boolean sneak;
    private int remainingTicks;

    private Float pendingYaw;
    private Float pendingPitch;

    public synchronized void move(double forward, double strafe, boolean sprint, int ticks) {
        this.forward = clamp(forward);
        this.strafe = clamp(strafe);
        this.sprint = sprint;
        this.remainingTicks = Math.max(1, ticks);
    }

    public synchronized void look(float yaw, float pitch) {
        this.pendingYaw = yaw;
        this.pendingPitch = pitch;
    }

    public synchronized void jump() {
        this.jumpRequested = true;
    }

    public synchronized void setSneak(boolean sneak) {
        this.sneak = sneak;
    }

    public synchronized void stop() {
        this.forward = 0;
        this.strafe = 0;
        this.sprint = false;
        this.jumpRequested = false;
        this.remainingTicks = 0;
    }

    public synchronized boolean isMoving() {
        return this.remainingTicks > 0;
    }

    /**
     * Applies one tick of input. Must run on the server thread right before
     * {@link CompanionPacketListener#tick()}.
     */
    public synchronized void tick(ServerPlayer player) {
        if (this.pendingYaw != null) {
            player.setYRot(this.pendingYaw);
            player.setXRot(this.pendingPitch);
            player.setYHeadRot(this.pendingYaw);
            this.pendingYaw = null;
            this.pendingPitch = null;
        }

        player.setShiftKeyDown(this.sneak);

        if (this.remainingTicks > 0) {
            player.zza = (float) this.forward;
            player.xxa = (float) this.strafe;
            player.setSprinting(this.sprint);
            this.remainingTicks--;
            if (this.remainingTicks == 0) {
                player.zza = 0.0F;
                player.xxa = 0.0F;
                player.setSprinting(false);
            }
        } else {
            player.zza = 0.0F;
            player.xxa = 0.0F;
            player.setSprinting(false);
            player.setJumping(false);
        }

        // Jumping is independent from horizontal movement: a player can jump
        // while standing still. Server players never receive the client's jump
        // packet, so the impulse must be applied directly. The "jumping" flag
        // only drives mob/fluid logic, not ground jumps.
        if (this.jumpRequested) {
            if (player.onGround()) {
                player.jumpFromGround();
            } else {
                player.setJumping(true);
            }
            this.jumpRequested = false;
        }
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
