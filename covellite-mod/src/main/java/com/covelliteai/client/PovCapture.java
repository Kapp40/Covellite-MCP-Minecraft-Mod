package com.covelliteai.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Takes real screenshots from the companion's point of view.
 *
 * <p>The companion lives on the integrated server. The human's client receives
 * its player-info and tracking packets, so it already has a client-side player
 * entity for it. This class temporarily makes that exact entity the camera,
 * lets two frames render through the normal pipeline (Sodium/Iris included),
 * grabs the main render target and restores the human camera. Vanilla never
 * renders the entity that is the camera in first person, so the companion's
 * own model does not appear around the camera.</p>
 *
 * <p>The captured frame also does not contain the human's hands: the screenshot
 * is taken during {@code RenderLevelStageEvent.Stage.AFTER_LEVEL}, while
 * {@code GameRenderer.renderItemInHand} runs afterwards using
 * {@code minecraft.player}. What the companion is holding is not simulated;
 * its own hand is never drawn by the vanilla first-person renderer.</p>
 *
 * <p>Client-only: this class is loaded from guarded code and must never be
 * referenced from common server paths.</p>
 */
public final class PovCapture {

    private static final Object LOCK = new Object();
    private static Request active;

    private PovCapture() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(PovCapture::onRenderStage);
    }

    /**
     * Requests a screenshot and blocks the caller until the client thread has
     * written the PNG. Intended to be called from the integrated server thread.
     */
    public static Map<String, Object> capture(java.util.UUID uuid,
                                              double x, double y, double z,
                                              float yaw, float pitch,
                                              long timeoutMs) {
        Request request = new Request(uuid, x, y, z, yaw, pitch);
        synchronized (LOCK) {
            if (active != null) {
                throw new IllegalStateException("another screenshot is already in progress");
            }
            active = request;
        }

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> begin(request));

        try {
            return request.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail(request, "screenshot timed out; is the game window rendering?");
            throw new IllegalStateException("screenshot timed out after " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(request, "interrupted");
            throw new IllegalStateException("screenshot interrupted");
        } catch (Exception e) {
            fail(request, String.valueOf(e.getMessage()));
            throw new IllegalStateException("screenshot failed: " + e.getMessage(), e);
        }
    }

    private static void begin(Request request) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            fail(request, "no client world is loaded");
            return;
        }
        try {
            AbstractClientPlayer camera = null;
            for (AbstractClientPlayer candidate : level.players()) {
                if (candidate.getUUID().equals(request.uuid)) {
                    camera = candidate;
                    break;
                }
            }
            if (camera == null) {
                // The client only tracks players near the human. Far away there is
                // no entity (and usually no loaded chunks) to render.
                fail(request, "the companion's client entity is not present; move the human player closer");
                return;
            }

            request.previous = minecraft.getCameraEntity();
            request.camera = camera;
            request.previousX = camera.getX();
            request.previousY = camera.getY();
            request.previousZ = camera.getZ();
            request.previousYRot = camera.getYRot();
            request.previousXRot = camera.getXRot();
            request.previousYHeadRot = camera.getYHeadRot();

            // Match the authoritative server position/rotation exactly instead
            // of relying on the last interpolation tick.
            camera.setPos(request.x, request.y, request.z);
            camera.setYRot(request.yaw);
            camera.setXRot(request.pitch);
            camera.setYHeadRot(request.yaw);
            camera.setOldPosAndRot();
            camera.yHeadRotO = request.yaw;

            request.framesLeft = 2;
            request.cameraSet = true;
            minecraft.setCameraEntity(camera);
        } catch (RuntimeException e) {
            fail(request, "could not create the POV camera: " + e);
        }
    }

    /** Puts the camera entity back where and how it was before the capture. */
    private static void restoreCamera(Request request) {
        Minecraft minecraft = Minecraft.getInstance();
        if (request.camera != null) {
            request.camera.setPos(request.previousX, request.previousY, request.previousZ);
            request.camera.setYRot(request.previousYRot);
            request.camera.setXRot(request.previousXRot);
            request.camera.setYHeadRot(request.previousYHeadRot);
            request.camera.setOldPosAndRot();
            request.camera.yHeadRotO = request.previousYHeadRot;
        }
        minecraft.setCameraEntity(request.previous);
        request.cameraSet = false;
    }

    private static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Request request;
        synchronized (LOCK) {
            request = active;
        }
        if (request == null || !request.cameraSet) {
            return;
        }
        if (--request.framesLeft > 0) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = minecraft.getMainRenderTarget();
        NativeImage image = null;
        try {
            image = Screenshot.takeScreenshot(target);
            int width = image.getWidth();
            int height = image.getHeight();

            // Restore the human camera before touching the disk.
            restoreCamera(request);

            Path directory = minecraft.gameDirectory.toPath().resolve("covemcp-screenshots");
            Files.createDirectories(directory);
            Path file = directory.resolve("pov-" + System.currentTimeMillis() + ".png");
            image.writeToFile(file);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("path", file.toAbsolutePath().toString());
            result.put("width", width);
            result.put("height", height);
            result.put("x", request.x);
            result.put("y", request.y);
            result.put("z", request.z);
            result.put("yaw", request.yaw);
            result.put("pitch", request.pitch);
            synchronized (LOCK) {
                if (active == request) {
                    active = null;
                }
            }
            request.future.complete(result);
        } catch (Exception e) {
            if (request.previous != null) {
                restoreCamera(request);
            }
            fail(request, "could not write the screenshot: " + e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private static void fail(Request request, String message) {
        request.cameraSet = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (request.previous != null) {
            restoreCamera(request);
        }
        synchronized (LOCK) {
            if (active == request) {
                active = null;
            }
        }
        request.future.completeExceptionally(new IllegalStateException(message));
    }

    private static final class Request {
        final UUID uuid;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Entity previous;
        AbstractClientPlayer camera;
        double previousX;
        double previousY;
        double previousZ;
        float previousYRot;
        float previousXRot;
        float previousYHeadRot;
        int framesLeft;
        boolean cameraSet;

        Request(UUID uuid, double x, double y, double z, float yaw, float pitch) {
            this.uuid = uuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
