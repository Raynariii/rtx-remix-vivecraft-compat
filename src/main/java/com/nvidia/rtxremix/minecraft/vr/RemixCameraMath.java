/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 remix-vivecraft-compat contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added in the remix-vivecraft-compat fork; not part of the upstream mod.
 */
package com.nvidia.rtxremix.minecraft.vr;

import org.joml.Matrix4d;
import org.joml.Matrix4f;

/**
 * Converts Minecraft/OpenGL camera matrices into the convention the Remix camera
 * API expects when no {@code CameraInfoParameterizedEXT} is chained.
 *
 * <p>Minecraft, JOML and OpenVR are right-handed (camera looks down -Z) with
 * clip-space depth in [-1, 1]. The Remix parameterized camera builds a
 * left-handed view (x right, y up, z forward) and a D3D projection with depth in
 * [0, 1]. Both conventions share the same memory layout: a JOML column-major
 * array is the D3D row-vector array, so no transpose is needed by default.
 *
 * <pre>
 *   S        = diag(1, 1, -1, 1)                 // RH view space -> LH view space
 *   D        = depth remap z' = 0.5 z + 0.5 w    // [-1,1] -> [0,1]
 *   view_LH  = S * view_GL
 *   proj_LH  = D * proj_GL * S
 * </pre>
 *
 * so {@code proj_LH * view_LH = D * proj_GL * view_GL}: the same clip x, y, w,
 * and depth remapped to [0, 1]. Off-axis (asymmetric) frusta survive unchanged.
 */
public final class RemixCameraMath {

    /** Same planes as the upstream parameterized camera, for comparable output. */
    public static final float REMIX_NEAR = 0.05f;
    public static final float REMIX_FAR = 1000.0f;

    private static final float PURE_EPS = 1.0e-3f;

    private RemixCameraMath() {
    }

    /** Frustum edge tangents of an off-axis perspective: x = tan * (-z_gl) at the edges. */
    public static final class Tangents {
        public float left;
        public float right;
        public float bottom;
        public float top;

        public float fovYDegrees() {
            return (float) Math.toDegrees(Math.atan(top) - Math.atan(bottom));
        }

        public float aspect() {
            return (right - left) / (top - bottom);
        }

        @Override
        public String toString() {
            return String.format("L=%.4f R=%.4f B=%.4f T=%.4f", left, right, bottom, top);
        }
    }

    /**
     * True when {@code p} is an OpenGL off-axis perspective matrix with no extra
     * rotation, translation or shear folded in (view bobbing, hurt tilt and
     * nausea all break this).
     */
    public static boolean isPureOffAxisPerspective(Matrix4f p) {
        return near0(p.m01()) && near0(p.m02()) && near0(p.m03())
                && near0(p.m10()) && near0(p.m12()) && near0(p.m13())
                && near0(p.m30()) && near0(p.m31()) && near0(p.m33())
                && Math.abs(p.m23() + 1.0f) < PURE_EPS
                && p.m00() > 0.0f && p.m11() > 0.0f;
    }

    /** Reads frustum tangents from an OpenGL perspective; only meaningful when {@link #isPureOffAxisPerspective}. */
    public static Tangents tangents(Matrix4f p, Tangents out) {
        out.right = (p.m20() + 1.0f) / p.m00();
        out.left = (p.m20() - 1.0f) / p.m00();
        out.top = (p.m21() + 1.0f) / p.m11();
        out.bottom = (p.m21() - 1.0f) / p.m11();
        return out;
    }

    /** OpenGL off-axis perspective for the given tangents and planes. */
    public static Matrix4f glFrustum(Tangents t, float near, float far, Matrix4f out) {
        return out.setFrustum(t.left * near, t.right * near, t.bottom * near, t.top * near, near, far);
    }

    /** {@code proj_LH = D * proj_GL * S}. */
    public static Matrix4f glProjectionToRemix(Matrix4f projGl, Matrix4f out) {
        Matrix4f d = new Matrix4f(
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 0.5f, 0,
                0, 0, 0.5f, 1); // JOML constructor is column-major: column 3 = (0,0,0.5,1)
        return d.mul(projGl, out).scale(1.0f, 1.0f, -1.0f);
    }

    /**
     * World-to-view for Remix: {@code S * rotation * translate(-cameraPos)}, built
     * in double so large world coordinates keep their precision until the final
     * float conversion.
     */
    public static Matrix4f glViewToRemix(Matrix4f cameraRelativeView, double camX, double camY, double camZ,
                                         Matrix4f out) {
        Matrix4d v = new Matrix4d().scaling(1.0, 1.0, -1.0)
                .mul(new Matrix4d(cameraRelativeView))
                .translate(-camX, -camY, -camZ);
        return out.set(v);
    }

    /** Column-major float[16] in the layout {@code remixapi_CameraInfo.view/projection} copies. */
    public static float[] toRemixArray(Matrix4f m, boolean transpose, float[] out) {
        if (transpose) {
            new Matrix4f(m).transpose().get(out);
        } else {
            m.get(out);
        }
        return out;
    }

    public static String format(Matrix4f m) {
        // Printed row by row, as the matrix reads on paper (rows of the column-vector form).
        return String.format(
                "[%.5f %.5f %.5f %.5f | %.5f %.5f %.5f %.5f | %.5f %.5f %.5f %.5f | %.5f %.5f %.5f %.5f]",
                m.m00(), m.m10(), m.m20(), m.m30(),
                m.m01(), m.m11(), m.m21(), m.m31(),
                m.m02(), m.m12(), m.m22(), m.m32(),
                m.m03(), m.m13(), m.m23(), m.m33());
    }

    private static boolean near0(float v) {
        return Math.abs(v) < PURE_EPS;
    }
}
