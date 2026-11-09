/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 remix-vivecraft-compat contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added in the remix-vivecraft-compat fork; not part of the upstream mod.
 */
package com.nvidia.rtxremix.minecraft.vr;

import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional, reflection-only access to Vivecraft (tested against 1.21.1-1.3.15).
 * Nothing here links against Vivecraft, so the mod loads and behaves exactly
 * like upstream when Vivecraft is absent. Every call degrades to a neutral value
 * and logs once if a signature does not match.
 *
 * <p>Public API ({@code org.vivecraft.api.client}, {@code @since 1.3.0}) is
 * preferred; internal classes are touched only for diagnostics.
 */
final class VivecraftBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("RemixVR");

    private final boolean loaded;
    private final String version;

    private boolean resolved;
    private boolean broken;

    private Method renderingInstance;
    private Method isVanillaRenderPass;
    private Method getCurrentRenderPass;
    private Method isFirstRenderPass;
    private Method getRenderPassMatrix;
    private Method clientInstance;
    private Method isVrActive;

    // Internal, diagnostics only.
    private boolean internalBroken;
    private Method dataHolderInstance;
    private Field vrRendererField;
    private Method getRenderTextureSizes;
    private Field eyeProjField;

    VivecraftBridge() {
        FabricLoader loader = FabricLoader.getInstance();
        this.loaded = loader.isModLoaded("vivecraft");
        this.version = loaded
                ? loader.getModContainer("vivecraft")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("?")
                : "absent";
    }

    boolean isLoaded() {
        return loaded;
    }

    String version() {
        return version;
    }

    private boolean resolve() {
        if (!loaded || broken) {
            return false;
        }
        if (resolved) {
            return true;
        }
        try {
            ClassLoader cl = VivecraftBridge.class.getClassLoader();
            Class<?> rendering = Class.forName("org.vivecraft.api.client.VRRenderingAPI", false, cl);
            Class<?> client = Class.forName("org.vivecraft.api.client.VRClientAPI", false, cl);
            Class<?> renderPass = Class.forName("org.vivecraft.api.client.data.RenderPass", false, cl);
            renderingInstance = rendering.getMethod("instance");
            isVanillaRenderPass = rendering.getMethod("isVanillaRenderPass");
            getCurrentRenderPass = rendering.getMethod("getCurrentRenderPass");
            isFirstRenderPass = rendering.getMethod("isFirstRenderPass");
            getRenderPassMatrix = rendering.getMethod("getRenderPassMatrix", renderPass);
            clientInstance = client.getMethod("instance");
            isVrActive = client.getMethod("isVRActive");
            resolved = true;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            broken = true;
            LOGGER.warn("[RemixVR] Vivecraft {} is loaded but its API does not match; VR support disabled", version, e);
            return false;
        }
    }

    private Object rendering() throws ReflectiveOperationException {
        return renderingInstance.invoke(null);
    }

    boolean isVrActive() {
        if (!resolve()) {
            return false;
        }
        try {
            return (Boolean) isVrActive.invoke(clientInstance.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail("isVRActive", e);
            return false;
        }
    }

    /** Current pass enum, or null outside VR / when unavailable. */
    Object currentPass() {
        if (!resolve()) {
            return null;
        }
        try {
            return getCurrentRenderPass.invoke(rendering());
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail("getCurrentRenderPass", e);
            return null;
        }
    }

    String currentPassName() {
        Object pass = currentPass();
        return pass == null ? (loaded ? "NONE" : "NO_VIVECRAFT") : ((Enum<?>) pass).name();
    }

    boolean isVanillaRenderPass() {
        if (!resolve()) {
            return true;
        }
        try {
            return (Boolean) isVanillaRenderPass.invoke(rendering());
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail("isVanillaRenderPass", e);
            return true;
        }
    }

    boolean isFirstRenderPass() {
        if (!resolve()) {
            return true;
        }
        try {
            return (Boolean) isFirstRenderPass.invoke(rendering());
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail("isFirstRenderPass", e);
            return true;
        }
    }

    Matrix4f renderPassMatrix(Object pass) {
        if (pass == null || !resolve()) {
            return null;
        }
        try {
            return (Matrix4f) getRenderPassMatrix.invoke(rendering(), pass);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not fatal: diagnostics only, and some passes have no matrix.
            return null;
        }
    }

    private Object vrRenderer() throws ReflectiveOperationException {
        if (internalBroken || !loaded) {
            return null;
        }
        if (dataHolderInstance == null) {
            ClassLoader cl = VivecraftBridge.class.getClassLoader();
            Class<?> holder = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR", false, cl);
            dataHolderInstance = holder.getMethod("getInstance");
            vrRendererField = holder.getField("vrRenderer");
            Class<?> renderer = Class.forName("org.vivecraft.client_vr.provider.VRRenderer", false, cl);
            getRenderTextureSizes = renderer.getMethod("getRenderTextureSizes");
            eyeProjField = renderer.getField("eyeProj");
        }
        return vrRendererField.get(dataHolderInstance.invoke(null));
    }

    /** Eye render texture size as "WxH", or null. Internal API, diagnostics only. */
    String eyeTextureSize() {
        try {
            Object r = vrRenderer();
            if (r == null) {
                return null;
            }
            Object size = getRenderTextureSizes.invoke(r);
            return size == null ? null : size.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            internalFail(e);
            return null;
        }
    }

    /** Cached OpenVR eye projections ({@code VRRenderer.eyeProj}), or null. Internal API, diagnostics only. */
    Matrix4f[] eyeProjections() {
        try {
            Object r = vrRenderer();
            return r == null ? null : (Matrix4f[]) eyeProjField.get(r);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            internalFail(e);
            return null;
        }
    }

    private void fail(String what, Throwable e) {
        if (!broken) {
            broken = true;
            LOGGER.warn("[RemixVR] Vivecraft call {} failed; VR support disabled for this session", what, e);
        }
    }

    private void internalFail(Throwable e) {
        if (!internalBroken) {
            internalBroken = true;
            LOGGER.warn("[RemixVR] Vivecraft internals unavailable; eye size/projection diagnostics disabled: {}",
                    e.toString());
        }
    }
}
