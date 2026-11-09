/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 remix-vivecraft-compat contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added in the remix-vivecraft-compat fork; not part of the upstream mod.
 */
package com.nvidia.rtxremix.minecraft.vr;

import com.nvidia.rtxremix.CameraInfo;
import com.nvidia.rtxremix.CameraType;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * VR camera addon for Vivecraft (stages 0 and 1 of the remix-vivecraft-compat plan).
 *
 * <p>Stage 0: per-pass diagnostics. Every {@code WorldRenderer.render} call opens
 * a pass record; the camera update, {@code Submit}, interop read-back and overlay
 * fill it in; {@code Present} closes the frame and prints the records when the
 * frame is sampled. A frame dump hotkey prints full matrices for one frame.
 *
 * <p>Stage 1: the Remix camera is built from the view and projection matrices
 * {@code WorldRenderer.render} receives for the current pass (Vivecraft has
 * already replaced them with the eye's pose, roll and asymmetric OpenVR
 * frustum), instead of yaw/pitch and the desktop FOV.
 *
 * <p>All entry points run on the render thread and allocate nothing unless a
 * frame is being logged.
 */
public final class VrCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("RemixVR");
    private static final String P = "[RemixVR] ";
    private static final int MAX_PASSES = 12;

    private static final VrCompatConfig CONFIG = new VrCompatConfig();
    private static VivecraftBridge vivecraft;
    private static boolean initialized;

    private static KeyBinding cycleModeKey;
    private static KeyBinding dumpKey;
    private static KeyBinding denoiseKey;
    private static KeyBinding projectionKey;

    // --- frame state -------------------------------------------------------
    private static long frameIndex;
    private static int passCount;
    private static int submitsThisFrame;
    private static int orphanSubmitsThisFrame;
    private static boolean sampleThisFrame;
    private static boolean dumpRequested;
    private static boolean dumpThisFrame;
    private static int vrFramesLogged;
    private static long lastSignature = Long.MIN_VALUE;
    private static String lastEyeSize;
    private static int lastInteropW = -1;
    private static int lastInteropH = -1;

    private static final PassRecord[] PASSES = new PassRecord[MAX_PASSES];

    static {
        for (int i = 0; i < MAX_PASSES; i++) {
            PASSES[i] = new PassRecord();
        }
    }

    // Diagnostic Remix overrides: denoiser and upscaler off.
    private static boolean temporalOff;
    private static String savedUpscaler;
    private static String savedDenoiser;

    // Scratch, reused every pass.
    private static final Matrix4f SCRATCH_PROJ = new Matrix4f();
    private static final RemixCameraMath.Tangents SCRATCH_TAN = new RemixCameraMath.Tangents();
    private static final float[] VIEW_ARRAY = new float[16];
    private static final float[] PROJ_ARRAY = new float[16];
    private static final int[] VIEWPORT = new int[4];

    private VrCompat() {
    }

    private static final class PassRecord {
        String pass = "?";
        boolean vanilla = true;
        boolean first;
        boolean captured;
        double camX, camY, camZ;
        final Matrix4f position = new Matrix4f();
        final Matrix4f projection = new Matrix4f();
        boolean cameraUpdated;
        String cameraPath = "-";
        boolean pureProjection;
        final RemixCameraMath.Tangents tangents = new RemixCameraMath.Tangents();
        final Matrix4f remixView = new Matrix4f();
        final Matrix4f remixProj = new Matrix4f();
        int submitIndex;
        int interopW, interopH;
        boolean overlay;
        int fbo = -1;
        final int[] viewport = new int[4];

        void reset() {
            pass = "?";
            vanilla = true;
            first = false;
            captured = false;
            cameraUpdated = false;
            cameraPath = "-";
            pureProjection = false;
            submitIndex = 0;
            interopW = interopH = 0;
            overlay = false;
            fbo = -1;
        }
    }

    // ======================================================================
    // Init
    // ======================================================================

    /** Called once from {@code RtxRemixMod.onInitializeClient}. */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        vivecraft = new VivecraftBridge();
        CONFIG.load();

        cycleModeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rtxremix.vr.cycleCameraMode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_7, "category.rtxremix.vr"));
        dumpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rtxremix.vr.dumpFrame", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_8, "category.rtxremix.vr"));
        denoiseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rtxremix.vr.toggleTemporal", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_9, "category.rtxremix.vr"));
        projectionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rtxremix.vr.cycleProjection", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_4, "category.rtxremix.vr"));
        ClientTickEvents.END_CLIENT_TICK.register(VrCompat::onClientTick);

        LOGGER.info(P + "remix-vivecraft-compat stage 0+1 | mod {} | Vivecraft {} | {}",
                RtxRemixMod.getModVersion(), vivecraft.version(), runtimeIdentity());
        LOGGER.info(P + "config: {}", CONFIG);
        LOGGER.info(P + "hotkeys: Numpad7 camera mode, Numpad4 projection source, Numpad8 frame dump, "
                + "Numpad9 denoiser+upscaler off/on");
    }

    private static String runtimeIdentity() {
        try {
            Path dll = FabricLoader.getInstance().getGameDir().resolve("remix-sdk").resolve("d3d9.dll");
            if (!Files.isRegularFile(dll)) {
                return "runtime d3d9.dll missing";
            }
            return "runtime d3d9.dll size=" + Files.size(dll) + " mtime=" + Files.getLastModifiedTime(dll);
        } catch (Exception e) {
            return "runtime unknown (" + e + ")";
        }
    }

    private static void onClientTick(MinecraftClient client) {
        while (cycleModeKey.wasPressed()) {
            CONFIG.cameraMode = CONFIG.cameraMode.next();
            CONFIG.save();
            announce(client, "camera mode -> " + CONFIG.cameraMode);
        }
        while (projectionKey.wasPressed()) {
            CONFIG.projection = CONFIG.projection == VrCompatConfig.ProjectionSource.REBUILD
                    ? VrCompatConfig.ProjectionSource.RAW
                    : VrCompatConfig.ProjectionSource.REBUILD;
            CONFIG.save();
            announce(client, "projection source -> " + CONFIG.projection);
        }
        while (dumpKey.wasPressed()) {
            dumpRequested = true;
            announce(client, "frame dump requested");
        }
        while (denoiseKey.wasPressed()) {
            toggleTemporal(client);
        }
    }

    private static void announce(MinecraftClient client, String message) {
        LOGGER.info(P + "switch: {} (frame {})", message, frameIndex);
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[RemixVR] " + message), true);
        }
    }

    private static void toggleTemporal(MinecraftClient client) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        RemixAPI api = renderer != null && renderer.isInitialized() ? renderer.getRemixApi() : null;
        if (api == null) {
            announce(client, "denoiser/upscaler toggle ignored: renderer not initialized");
            return;
        }
        try {
            if (!temporalOff) {
                savedUpscaler = api.getConfigVariableResolved("rtx.upscalerType");
                savedDenoiser = api.getConfigVariableResolved("rtx.useDenoiser");
                api.setConfigVariable("rtx.upscalerType", "0");
                api.setConfigVariable("rtx.useDenoiser", "False");
                temporalOff = true;
                announce(client, "denoiser+upscaler OFF (was upscalerType=" + savedUpscaler
                        + " useDenoiser=" + savedDenoiser + ")");
            } else {
                api.setConfigVariable("rtx.upscalerType", nonEmpty(savedUpscaler, "1"));
                api.setConfigVariable("rtx.useDenoiser", nonEmpty(savedDenoiser, "True"));
                temporalOff = false;
                announce(client, "denoiser+upscaler restored (upscalerType=" + savedUpscaler
                        + " useDenoiser=" + savedDenoiser + ")");
            }
        } catch (RuntimeException e) {
            LOGGER.warn(P + "denoiser/upscaler toggle failed", e);
        }
    }

    private static String nonEmpty(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    // ======================================================================
    // Hooks
    // ======================================================================

    /** {@code WorldRenderer.render} HEAD: opens a pass record and captures its matrices. */
    public static void onWorldRenderHead(Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix) {
        if (!initialized || !CONFIG.enabled) {
            return;
        }
        PassRecord r = passCount < MAX_PASSES ? PASSES[passCount] : PASSES[MAX_PASSES - 1];
        if (passCount < MAX_PASSES) {
            passCount++;
        }
        r.reset();
        if (vivecraft.isLoaded()) {
            Object pass = vivecraft.currentPass();
            r.pass = pass == null ? "NONE" : ((Enum<?>) pass).name();
            r.vanilla = vivecraft.isVanillaRenderPass();
            r.first = vivecraft.isFirstRenderPass();
        } else {
            r.pass = "NO_VIVECRAFT";
        }
        if (positionMatrix != null && projectionMatrix != null) {
            r.position.set(positionMatrix);
            r.projection.set(projectionMatrix);
            r.captured = true;
        }
        Vec3d pos = camera.getPos();
        r.camX = pos.x;
        r.camY = pos.y;
        r.camZ = pos.z;
    }

    /**
     * {@code RemixRenderer.updateCamera}: sets up the Remix WORLD and VIEW_MODEL
     * cameras from the captured pass matrices. Returns false to let the upstream
     * camera code run unchanged.
     */
    public static boolean applyCamera(RemixAPI api, RemixWeatherRenderer weather, Camera camera, Vec3d pos) {
        if (!initialized || !CONFIG.enabled || passCount == 0) {
            return false;
        }
        PassRecord r = PASSES[passCount - 1];
        if (r.cameraUpdated) {
            // A second camera update without a new WorldRenderer.render: matrices are stale.
            r.cameraPath = "ORIGINAL(stale)";
            return false;
        }
        r.cameraUpdated = true;

        boolean usePass;
        switch (CONFIG.cameraMode) {
            case ORIGINAL -> usePass = false;
            case PASS_MATRICES -> usePass = r.captured;
            default -> usePass = r.captured && !r.vanilla && vivecraft.isVrActive();
        }
        r.pureProjection = r.captured && RemixCameraMath.isPureOffAxisPerspective(r.projection);
        if (r.pureProjection) {
            RemixCameraMath.tangents(r.projection, r.tangents);
        }
        if (!usePass) {
            r.cameraPath = "ORIGINAL";
            return false;
        }

        boolean rebuild = CONFIG.projection == VrCompatConfig.ProjectionSource.REBUILD && r.pureProjection;
        Matrix4f projGl = rebuild
                ? RemixCameraMath.glFrustum(r.tangents, RemixCameraMath.REMIX_NEAR, RemixCameraMath.REMIX_FAR,
                        SCRATCH_PROJ)
                : SCRATCH_PROJ.set(r.projection);
        RemixCameraMath.glProjectionToRemix(projGl, r.remixProj);
        RemixCameraMath.glViewToRemix(r.position, pos.x, pos.y, pos.z, r.remixView);
        r.cameraPath = rebuild ? "PASS/REBUILD" : (r.pureProjection ? "PASS/RAW" : "PASS/RAW(impure)");

        if (weather != null) {
            weather.updateCameraMatrices(r.position, projGl);
        }

        RemixCameraMath.toRemixArray(r.remixView, CONFIG.transposeMatrices, VIEW_ARRAY);
        RemixCameraMath.toRemixArray(r.remixProj, CONFIG.transposeMatrices, PROJ_ARRAY);
        // No parameterizedExt: Remix takes view/projection as is (remixapi_SetupCamera).
        CameraInfo world = CameraInfo.builder()
                .type(CameraType.WORLD).view(VIEW_ARRAY).projection(PROJ_ARRAY).build();
        CameraInfo viewModel = CameraInfo.builder()
                .type(CameraType.VIEW_MODEL).view(VIEW_ARRAY).projection(PROJ_ARRAY).build();
        api.setupCamera(world);
        api.setupCamera(viewModel);
        return true;
    }

    /** {@code RemixRenderer.submitFrame}. {@code duringPresent}: the fallback submit before a present with no world submit. */
    public static void onSubmit(boolean duringPresent) {
        if (!initialized) {
            return;
        }
        submitsThisFrame++;
        if (duringPresent || passCount == 0 || PASSES[passCount - 1].submitIndex != 0) {
            orphanSubmitsThisFrame++;
            return;
        }
        PASSES[passCount - 1].submitIndex = submitsThisFrame;
    }

    /** {@code RemixRenderer.blitVkImageToGL}: interop image size after Submit. */
    public static void onInteropImage(int width, int height) {
        if (!initialized || passCount == 0) {
            return;
        }
        PassRecord r = PASSES[passCount - 1];
        r.interopW = width;
        r.interopH = height;
    }

    /** {@code RtxRemixMod.submitRemixFrameIfReady}, right before the overlay is drawn into the pass framebuffer. */
    public static void onOverlay() {
        if (!initialized || passCount == 0) {
            return;
        }
        PassRecord r = PASSES[passCount - 1];
        r.overlay = true;
        if (sampleThisFrame || dumpThisFrame) {
            // The overlay binds MinecraftClient.getFramebuffer() (Vivecraft swaps it per pass).
            var fb = MinecraftClient.getInstance().getFramebuffer();
            r.fbo = fb != null ? fb.fbo : GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            if (fb != null) {
                r.viewport[0] = 0;
                r.viewport[1] = 0;
                r.viewport[2] = fb.textureWidth;
                r.viewport[3] = fb.textureHeight;
            } else {
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
                System.arraycopy(VIEWPORT, 0, r.viewport, 0, 4);
            }
        }
    }

    /** {@code RemixRenderer.presentFrameVk}, before the GL-to-Remix copy: closes the frame. */
    public static void onPresent() {
        if (!initialized) {
            return;
        }
        boolean anyVr = false;
        long signature = 17;
        for (int i = 0; i < passCount; i++) {
            PassRecord r = PASSES[i];
            anyVr |= !r.vanilla;
            signature = signature * 31 + r.pass.hashCode();
            signature = signature * 31 + r.submitIndex;
            signature = signature * 31 + (r.overlay ? 1 : 0);
            signature = signature * 31 + r.cameraPath.hashCode();
        }
        signature = signature * 31 + orphanSubmitsThisFrame;

        boolean signatureChanged = signature != lastSignature && passCount > 0;
        if (sampleThisFrame || dumpThisFrame || signatureChanged) {
            logFrame(signatureChanged && !sampleThisFrame && !dumpThisFrame);
            if (anyVr && vrFramesLogged < CONFIG.logFirstVrFrames) {
                vrFramesLogged++;
            }
        }
        if (dumpThisFrame) {
            dumpFrame();
        }
        if (anyVr) {
            logEyeSizeIfChanged();
        }
        if (passCount > 0) {
            lastSignature = signature;
        }

        // Next frame.
        frameIndex++;
        passCount = 0;
        submitsThisFrame = 0;
        orphanSubmitsThisFrame = 0;
        dumpThisFrame = dumpRequested;
        dumpRequested = false;
        int every = CONFIG.logEveryFrames;
        sampleThisFrame = (every > 0 && frameIndex % every == 0)
                || (anyVr && vrFramesLogged < CONFIG.logFirstVrFrames);
    }

    // ======================================================================
    // Logging
    // ======================================================================

    private static void logFrame(boolean becauseChanged) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(P).append("frame ").append(frameIndex)
                .append(becauseChanged ? " (pass layout changed)" : "")
                .append(": passes=").append(passCount)
                .append(" submits=").append(submitsThisFrame)
                .append(" orphanSubmits=").append(orphanSubmitsThisFrame)
                .append(" mode=").append(CONFIG.cameraMode)
                .append(" proj=").append(CONFIG.projection)
                .append(" temporalOff=").append(temporalOff)
                .append(" vrActive=").append(vivecraft.isVrActive());
        LOGGER.info(sb.toString());
        for (int i = 0; i < passCount; i++) {
            PassRecord r = PASSES[i];
            LOGGER.info(String.format(
                    "%s  #%d pass=%s vanilla=%s first=%s submit#=%d camera=%s overlay=%s "
                            + "cam=(%.4f, %.4f, %.4f) fwd=%s up=%s right=%s roll=%.2f "
                            + "frustum=%s fovY=%.2f aspect=%.4f interop=%dx%d fbo=%d viewport=%dx%d",
                    P, i, r.pass, r.vanilla, r.first, r.submitIndex, r.cameraPath, r.overlay,
                    r.camX, r.camY, r.camZ,
                    axis(r.position, 2, true), axis(r.position, 1, false), axis(r.position, 0, false),
                    roll(r.position),
                    r.pureProjection ? r.tangents.toString() : "impure",
                    r.pureProjection ? r.tangents.fovYDegrees() : 0.0f,
                    r.pureProjection ? r.tangents.aspect() : 0.0f,
                    r.interopW, r.interopH, r.fbo, r.viewport[2], r.viewport[3]));
        }
    }

    private static void dumpFrame() {
        MinecraftClient client = MinecraftClient.getInstance();
        LOGGER.info(P + "===== FRAME DUMP frame {} =====", frameIndex);
        LOGGER.info(P + "window framebuffer={}x{} eyeTexture={} config: {}",
                client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight(),
                vivecraft.eyeTextureSize(), CONFIG);
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized() && renderer.getRemixApi() != null) {
            RemixAPI api = renderer.getRemixApi();
            for (String key : new String[] {"rtx.upscalerType", "rtx.useDenoiser", "rtx.graphicsPreset",
                    "rtx.resolutionScale", "rtx.qualityDLSS"}) {
                try {
                    LOGGER.info(P + "remix {} = {}", key, api.getConfigVariableResolved(key));
                } catch (RuntimeException e) {
                    LOGGER.info(P + "remix {} = <error {}>", key, e.getMessage());
                }
            }
        }
        Matrix4f[] eyeProj = vivecraft.eyeProjections();
        if (eyeProj != null) {
            for (int i = 0; i < eyeProj.length; i++) {
                if (eyeProj[i] != null) {
                    LOGGER.info(P + "vivecraft eyeProj[{}] = {}", i, RemixCameraMath.format(eyeProj[i]));
                }
            }
        }
        for (int i = 0; i < passCount; i++) {
            PassRecord r = PASSES[i];
            LOGGER.info(P + "-- pass #{} {} (vanilla={}, submit#={}, camera={}, overlay={}, interop={}x{}, "
                            + "fbo={}, viewport={}x{})",
                    i, r.pass, r.vanilla, r.submitIndex, r.cameraPath, r.overlay, r.interopW, r.interopH,
                    r.fbo, r.viewport[2], r.viewport[3]);
            LOGGER.info(String.format("%s   camPos = (%.6f, %.6f, %.6f)", P, r.camX, r.camY, r.camZ));
            LOGGER.info(P + "   positionMatrix   = {}", RemixCameraMath.format(r.position));
            LOGGER.info(P + "   projectionMatrix = {}", RemixCameraMath.format(r.projection));
            if (r.pureProjection) {
                LOGGER.info(P + "   frustum {} fovY={} aspect={}", r.tangents, r.tangents.fovYDegrees(),
                        r.tangents.aspect());
            }
            if (r.cameraPath.startsWith("PASS")) {
                LOGGER.info(P + "   remix view       = {}", RemixCameraMath.format(r.remixView));
                LOGGER.info(P + "   remix projection = {}", RemixCameraMath.format(r.remixProj));
            }
            if (vivecraft.isLoaded() && !r.vanilla) {
                Matrix4f passMatrix = passMatrixFor(r.pass);
                if (passMatrix != null) {
                    LOGGER.info(P + "   vivecraft getRenderPassMatrix = {}", RemixCameraMath.format(passMatrix));
                }
            }
        }
        LOGGER.info(P + "submits={} orphanSubmits={}", submitsThisFrame, orphanSubmitsThisFrame);
        LOGGER.info(P + "===== END FRAME DUMP =====");
    }

    private static Matrix4f passMatrixFor(String passName) {
        try {
            Class<?> type = Class.forName("org.vivecraft.api.client.data.RenderPass", false,
                    VrCompat.class.getClassLoader());
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(passName)) {
                    return vivecraft.renderPassMatrix(constant);
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
        return null;
    }

    private static void logEyeSizeIfChanged() {
        String eye = vivecraft.eyeTextureSize();
        int w = passCount > 0 ? PASSES[0].interopW : -1;
        int h = passCount > 0 ? PASSES[0].interopH : -1;
        boolean eyeChanged = eye != null && !eye.equals(lastEyeSize);
        boolean interopChanged = w > 0 && (w != lastInteropW || h != lastInteropH);
        if (eyeChanged || interopChanged) {
            MinecraftClient client = MinecraftClient.getInstance();
            LOGGER.info(P + "sizes: window={}x{} eyeTexture={} interop={}x{}",
                    client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight(),
                    eye, w, h);
            lastEyeSize = eye;
            lastInteropW = w;
            lastInteropH = h;
        }
    }

    /**
     * World-space camera axis from a camera-relative view rotation (rows of the
     * rotation are the camera axes). {@code negate} for forward, which is -Z in GL.
     */
    private static String axis(Matrix4f view, int row, boolean negate) {
        float s = negate ? -1.0f : 1.0f;
        float x, y, z;
        switch (row) {
            case 0 -> { x = view.m00(); y = view.m10(); z = view.m20(); }
            case 1 -> { x = view.m01(); y = view.m11(); z = view.m21(); }
            default -> { x = view.m02(); y = view.m12(); z = view.m22(); }
        }
        return String.format("(%.3f, %.3f, %.3f)", s * x, s * y, s * z);
    }

    /** Head roll in degrees: angle of the camera's right axis above the horizontal. */
    private static float roll(Matrix4f view) {
        return (float) Math.toDegrees(Math.asin(Math.max(-1.0f, Math.min(1.0f, view.m10()))));
    }
}
