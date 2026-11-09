/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 remix-vivecraft-compat contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added in the remix-vivecraft-compat fork; not part of the upstream mod.
 */
package com.nvidia.rtxremix.minecraft.vr;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** {@code config/remix-vivecraft-compat.properties}; hotkey changes are saved back. */
final class VrCompatConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("RemixVR");
    private static final String FILE = "remix-vivecraft-compat.properties";

    enum CameraMode {
        /** Pass matrices only in Vivecraft VR passes; upstream camera everywhere else. */
        AUTO,
        /** Upstream camera always (yaw/pitch + desktop FOV). */
        ORIGINAL,
        /** Pass matrices for every world pass, VR or not. */
        PASS_MATRICES;

        CameraMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    enum ProjectionSource {
        /** Rebuild from the pass frustum tangents with upstream near/far (0.05 / 1000). */
        REBUILD,
        /** Pass the converted pass projection as is (Vivecraft's near/far). */
        RAW
    }

    boolean enabled = true;
    CameraMode cameraMode = CameraMode.AUTO;
    ProjectionSource projection = ProjectionSource.REBUILD;
    boolean transposeMatrices = false;
    /** End a Remix frame after every VR eye pass but the last, so each eye gets its own render. */
    boolean stereoPresent = true;
    /** In VR passes, composite the Remix image only where GL drew nothing (keeps Vivecraft GUI/hands). */
    boolean overlayDepthMask = true;
    /** One summary line per pass every N presented frames; 0 disables. */
    int logEveryFrames = 600;
    /** Log every frame for this many frames after the first VR frame (catch startup). */
    int logFirstVrFrames = 3;

    private Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE);
    }

    void load() {
        Path p = path();
        if (!Files.isRegularFile(p)) {
            save();
            return;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            LOGGER.warn("[RemixVR] Could not read {}: {}", p, e.toString());
            return;
        }
        enabled = Boolean.parseBoolean(props.getProperty("enabled", String.valueOf(enabled)));
        cameraMode = parseEnum(CameraMode.class, props.getProperty("cameraMode"), cameraMode);
        projection = parseEnum(ProjectionSource.class, props.getProperty("projection"), projection);
        transposeMatrices = Boolean.parseBoolean(
                props.getProperty("transposeMatrices", String.valueOf(transposeMatrices)));
        stereoPresent = Boolean.parseBoolean(props.getProperty("stereoPresent", String.valueOf(stereoPresent)));
        overlayDepthMask = Boolean.parseBoolean(
                props.getProperty("overlayDepthMask", String.valueOf(overlayDepthMask)));
        logEveryFrames = parseInt(props.getProperty("logEveryFrames"), logEveryFrames);
        logFirstVrFrames = parseInt(props.getProperty("logFirstVrFrames"), logFirstVrFrames);
        save(); // writes keys added since the file was created
    }

    void save() {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                w.write("# remix-vivecraft-compat\n");
                w.write("# enabled: false = behave exactly like upstream\n");
                w.write("enabled=" + enabled + "\n");
                w.write("# cameraMode: AUTO (pass matrices in Vivecraft VR passes) | ORIGINAL | PASS_MATRICES\n");
                w.write("cameraMode=" + cameraMode + "\n");
                w.write("# projection: REBUILD (pass frustum, near 0.05 / far 1000) | RAW (pass projection as is)\n");
                w.write("projection=" + projection + "\n");
                w.write("# transposeMatrices: diagnostic only, flips the matrix layout handed to Remix\n");
                w.write("transposeMatrices=" + transposeMatrices + "\n");
                w.write("# stereoPresent: one Remix frame per VR eye (false = both eyes get the first eye's image)\n");
                w.write("stereoPresent=" + stereoPresent + "\n");
                w.write("# overlayDepthMask: VR passes keep what Vivecraft drew (GUI, hands) over the Remix image\n");
                w.write("overlayDepthMask=" + overlayDepthMask + "\n");
                w.write("# logEveryFrames: one [RemixVR] line per pass every N frames, 0 = off\n");
                w.write("logEveryFrames=" + logEveryFrames + "\n");
                w.write("# logFirstVrFrames: log every pass of the first N VR frames\n");
                w.write("logFirstVrFrames=" + logFirstVrFrames + "\n");
            }
        } catch (IOException e) {
            LOGGER.warn("[RemixVR] Could not write {}: {}", p, e.toString());
        }
    }

    @Override
    public String toString() {
        return "enabled=" + enabled + " cameraMode=" + cameraMode + " projection=" + projection
                + " transposeMatrices=" + transposeMatrices + " stereoPresent=" + stereoPresent
                + " overlayDepthMask=" + overlayDepthMask + " logEveryFrames=" + logEveryFrames
                + " logFirstVrFrames=" + logFirstVrFrames;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[RemixVR] Unknown {} '{}', using {}", type.getSimpleName(), value, fallback);
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
