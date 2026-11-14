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
 *
 * <p>Two input modes coexist:</p>
 * <ul>
 *   <li><b>exact</b> ({@link #move}, {@link #look}): the value is applied
 *       immediately for a fixed number of ticks. This is what tools such as
 *       {@code move} and combat aiming use, so distances stay predictable.</li>
 *   <li><b>smooth</b> ({@link #moveSmooth}, {@link #lookSmooth}): the value is a
 *       target the controller approaches over several ticks, which gives
 *       navigation natural acceleration, braking and head turning. The caller
 *       refreshes it every tick.</li>
 * </ul>
 */
public final class CompanionController {

    /** Per-tick approach for the smooth mode: ~4 ticks to reach full input. */
    private static final double ACCEL = 0.28D;
    /** Braking is faster than accelerating, like releasing the keys. */
    private static final double BRAKE = 0.45D;
    /** Degrees per tick for smooth look. */
    private static final float LOOK_STEP = 22.5F;

    private double forward;
    private double strafe;
    private boolean sprint;
    private boolean jumpRequested;
    private boolean sneak;
    private int remainingTicks;

    private Float pendingYaw;
    private Float pendingPitch;

    // Smooth mode state.
    private boolean smoothActive;
    private boolean smoothFresh;
    private double targetForward;
    private double targetStrafe;
    private boolean targetSprint;
    private double smoothForward;
    private double smoothStrafe;

    // Smooth look state.
    private boolean smoothLookActive;
    private float smoothYaw;
    private float smoothPitch;
    private boolean hasSmoothAngles;

    // Climbing (ladder / vine / scaffolding).
    private boolean climb;

    public synchronized void move(double forward, double strafe, boolean sprint, int ticks) {
        this.forward = clamp(forward);
        this.strafe = clamp(strafe);
        this.sprint = sprint;
        this.remainingTicks = Math.max(1, ticks);
        this.smoothActive = false;
    }

    /**
     * Requests one tick of smoothed input. Call it every tick; when the caller
     * stops calling, the input brakes to zero on its own.
     */
    public synchronized void moveSmooth(double forward, double strafe, boolean sprint) {
        this.targetForward = clamp(forward);
        this.targetStrafe = clamp(strafe);
        this.targetSprint = sprint;
        this.smoothActive = true;
        this.smoothFresh = true;
    }

    public synchronized void look(float yaw, float pitch) {
        this.pendingYaw = yaw;
        this.pendingPitch = pitch;
        this.smoothLookActive = false;
        this.hasSmoothAngles = false;
    }

    /** Turns the head towards the given angles at a limited rate. */
    public synchronized void lookSmooth(float yaw, float pitch) {
        this.smoothYaw = yaw;
        this.smoothPitch = Math.max(-60.0F, Math.min(60.0F, pitch));
        this.smoothLookActive = true;
    }

    public synchronized void jump() {
        this.jumpRequested = true;
    }

    public synchronized void setSneak(boolean sneak) {
        this.sneak = sneak;
    }

    /** True while the companion should hold the climb input (ladders, vines). */
    public synchronized void setClimb(boolean climb) {
        this.climb = climb;
    }

    public synchronized void stop() {
        this.forward = 0;
        this.strafe = 0;
        this.sprint = false;
        this.jumpRequested = false;
        this.remainingTicks = 0;
        this.smoothActive = false;
        this.smoothFresh = false;
        this.targetForward = 0;
        this.targetStrafe = 0;
        this.targetSprint = false;
        this.smoothForward = 0;
        this.smoothStrafe = 0;
        this.climb = false;
    }

    public synchronized boolean isMoving() {
        return this.remainingTicks > 0 || this.smoothActive;
    }

    /**
     * Applies one tick of input. Must run on the server thread right before
     * {@link CompanionPacketListener#tick()}.
     */
    public synchronized void tick(ServerPlayer player) {
        applyRotation(player);
        player.setShiftKeyDown(this.sneak);

        if (this.smoothActive) {
            tickSmooth(player);
        } else if (this.remainingTicks > 0) {
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

        // Climbing is a held input: vanilla ladders and vines need it every tick.
        if (this.climb) {
            player.setJumping(true);
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void applyRotation(ServerPlayer player) {
        if (this.pendingYaw != null) {
            player.setYRot(this.pendingYaw);
            player.setXRot(this.pendingPitch);
            player.setYHeadRot(this.pendingYaw);
            this.pendingYaw = null;
            this.pendingPitch = null;
            return;
        }
        if (!this.smoothLookActive) {
            return;
        }
        if (!this.hasSmoothAngles) {
            this.smoothYaw = player.getYRot();
            this.smoothPitch = player.getXRot();
            this.hasSmoothAngles = true;
        }
        float yaw = approachAngle(this.smoothYaw, player.getYRot(), LOOK_STEP);
        float pitch = approach(this.smoothPitch, player.getXRot(), LOOK_STEP);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
    }

    private void tickSmooth(ServerPlayer player) {
        if (!this.smoothFresh) {
            // No refresh this tick: release the keys.
            this.targetForward = 0;
            this.targetStrafe = 0;
            this.targetSprint = false;
        }
        this.smoothFresh = false;

        this.smoothForward = approach(this.targetForward, this.smoothForward, ACCEL, BRAKE);
        this.smoothStrafe = approach(this.targetStrafe, this.smoothStrafe, ACCEL, BRAKE);

        float input = Math.abs((float) this.smoothForward) + Math.abs((float) this.smoothStrafe);
        player.zza = (float) this.smoothForward;
        player.xxa = (float) this.smoothStrafe;
        // Never sprint from a standstill: it looks like a gliding player.
        player.setSprinting(this.targetSprint && input > 0.55F);

        if (input <= 0.01F) {
            this.smoothActive = false;
            player.zza = 0.0F;
            player.xxa = 0.0F;
            player.setSprinting(false);
        }
    }

    private static double approach(double target, double current, double accel) {
        double delta = target - current;
        if (Math.abs(delta) <= accel) {
            return target;
        }
        return current + Math.signum(delta) * accel;
    }

    private static double approach(double target, double current, double accel, double brake) {
        double delta = target - current;
        double rate = Math.abs(target) < Math.abs(current) ? brake : accel;
        if (Math.abs(delta) <= rate) {
            return target;
        }
        return current + Math.signum(delta) * rate;
    }

    /** Turns towards a target angle using the shortest direction. */
    private static float approachAngle(float target, float current, float maxStep) {
        float delta = wrapDegrees(target - current);
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.signum(delta) * maxStep;
    }

    private static float approach(float target, float current, float maxStep) {
        float delta = target - current;
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.signum(delta) * maxStep;
    }

    private static float wrapDegrees(float angle) {
        float wrapped = angle % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
