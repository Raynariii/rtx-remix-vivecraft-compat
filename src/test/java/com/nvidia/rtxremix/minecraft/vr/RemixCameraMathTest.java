/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 remix-vivecraft-compat contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added in the remix-vivecraft-compat fork; not part of the upstream mod.
 */
package com.nvidia.rtxremix.minecraft.vr;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixCameraMathTest {

    private static final float EPS = 1e-4f;

    /** Quest Pro-like left eye: wider outward (left) than inward. */
    private static RemixCameraMath.Tangents leftEye() {
        RemixCameraMath.Tangents t = new RemixCameraMath.Tangents();
        t.left = -1.40f;
        t.right = 0.85f;
        t.bottom = -1.25f;
        t.top = 0.95f;
        return t;
    }

    @Test
    void tangentsRoundTripThroughAnOffAxisFrustum() {
        RemixCameraMath.Tangents in = leftEye();
        Matrix4f p = RemixCameraMath.glFrustum(in, 0.02f, 256f, new Matrix4f());
        assertTrue(RemixCameraMath.isPureOffAxisPerspective(p));
        RemixCameraMath.Tangents out = RemixCameraMath.tangents(p, new RemixCameraMath.Tangents());
        assertEquals(in.left, out.left, EPS);
        assertEquals(in.right, out.right, EPS);
        assertEquals(in.bottom, out.bottom, EPS);
        assertEquals(in.top, out.top, EPS);
    }

    @Test
    void symmetricPerspectiveMatchesJomlPerspective() {
        Matrix4f p = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);
        RemixCameraMath.Tangents t = RemixCameraMath.tangents(p, new RemixCameraMath.Tangents());
        assertEquals(70f, t.fovYDegrees(), 1e-3f);
        assertEquals(16f / 9f, t.aspect(), 1e-4f);
        assertEquals(-t.left, t.right, EPS);
    }

    @Test
    void bobbingOrRollInTheProjectionIsDetectedAsImpure() {
        Matrix4f p = new Matrix4f().perspective(1.2f, 1.5f, 0.05f, 1000f).rotateZ(0.05f);
        assertFalse(RemixCameraMath.isPureOffAxisPerspective(p));
        Matrix4f q = new Matrix4f().perspective(1.2f, 1.5f, 0.05f, 1000f).translate(0.01f, 0.02f, 0f);
        assertFalse(RemixCameraMath.isPureOffAxisPerspective(q));
    }

    /**
     * The converted LH pair must put every world point at the same screen x/y as
     * the GL pair, with positive w (in front) and depth remapped into [0, 1].
     */
    @Test
    void remixPairProjectsLikeTheGlPairWithD3dDepth() {
        float near = 0.05f;
        float far = 1000f;
        Matrix4f projGl = RemixCameraMath.glFrustum(leftEye(), near, far, new Matrix4f());
        // Head pitched down and rolled, at a large world coordinate.
        Matrix4f rot = new Matrix4f().rotateZ(0.3f).rotateX(0.4f).rotateY(1.1f);
        double cx = 12345.25, cy = 70.5, cz = -9876.75;

        Matrix4f viewLh = RemixCameraMath.glViewToRemix(rot, cx, cy, cz, new Matrix4f());
        Matrix4f projLh = RemixCameraMath.glProjectionToRemix(projGl, new Matrix4f());
        Matrix4f viewGl = new Matrix4f(rot).translate((float) -cx, (float) -cy, (float) -cz);

        // Points in front of the camera, expressed in GL view space then moved to world.
        Matrix4f viewGlInv = new Matrix4f(viewGl).invert();
        float[][] viewPoints = {{0, 0, -1}, {0.3f, -0.2f, -2}, {-5, 4, -40}, {0.01f, 0.01f, -0.06f}, {1, 1, -999}};
        for (float[] vp : viewPoints) {
            Vector4f world = viewGlInv.transform(new Vector4f(vp[0], vp[1], vp[2], 1f));

            Vector4f clipGl = projGl.transform(viewGl.transform(new Vector4f(world)));
            Vector4f clipLh = projLh.transform(viewLh.transform(new Vector4f(world)));

            assertTrue(clipLh.w > 0, "w must be positive in front of the camera");
            assertEquals(clipGl.x / clipGl.w, clipLh.x / clipLh.w, 2e-3f);
            assertEquals(clipGl.y / clipGl.w, clipLh.y / clipLh.w, 2e-3f);
            float zGl = clipGl.z / clipGl.w;           // [-1, 1]
            float zLh = clipLh.z / clipLh.w;           // [0, 1]
            assertEquals(zGl * 0.5f + 0.5f, zLh, 2e-3f);
            assertTrue(zLh >= -1e-3f && zLh <= 1 + 1e-3f);
        }
    }

    @Test
    void viewSpaceIsLeftHandedWithZForward() {
        // Identity rotation: GL camera looks down -Z; Remix view must see that point at +Z.
        Matrix4f viewLh = RemixCameraMath.glViewToRemix(new Matrix4f(), 0, 0, 0, new Matrix4f());
        Vector4f p = viewLh.transform(new Vector4f(0, 0, -5, 1));
        assertEquals(5f, p.z, EPS);
        Vector4f r = viewLh.transform(new Vector4f(2, 3, 0, 1));
        assertEquals(2f, r.x, EPS);
        assertEquals(3f, r.y, EPS);
    }

    @Test
    void symmetricRemixProjectionMatchesTheParameterizedD3dForm() {
        // Remix's parameterized path builds a symmetric LH D3D projection:
        // [xs 0 0 0; 0 ys 0 0; 0 0 f/(f-n) -nf/(f-n); 0 0 1 0] (column-vector form).
        float n = 0.05f, f = 1000f, fovY = (float) Math.toRadians(70), aspect = 1.6f;
        Matrix4f lh = RemixCameraMath.glProjectionToRemix(
                new Matrix4f().perspective(fovY, aspect, n, f), new Matrix4f());
        float ys = (float) (1.0 / Math.tan(fovY / 2));
        assertEquals(ys / aspect, lh.m00(), EPS);
        assertEquals(ys, lh.m11(), EPS);
        assertEquals(f / (f - n), lh.m22(), EPS);
        assertEquals(-n * f / (f - n), lh.m32(), EPS);
        assertEquals(1f, lh.m23(), EPS);
        assertEquals(0f, lh.m33(), EPS);
    }

    @Test
    void remixArrayIsColumnMajorUnlessTransposed() {
        Matrix4f m = new Matrix4f().translation(1, 2, 3);
        float[] a = RemixCameraMath.toRemixArray(m, false, new float[16]);
        assertEquals(1f, a[12], EPS);
        assertEquals(2f, a[13], EPS);
        assertEquals(3f, a[14], EPS);
        float[] t = RemixCameraMath.toRemixArray(m, true, new float[16]);
        assertEquals(1f, t[3], EPS);
    }
}
