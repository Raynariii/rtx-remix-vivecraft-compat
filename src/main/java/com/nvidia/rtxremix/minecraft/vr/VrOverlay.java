/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 remix-vivecraft-compat contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added in the remix-vivecraft-compat fork; not part of the upstream mod.
 * Derived from com.nvidia.rtxremix.minecraft.gl.TextureOverlay (Apache-2.0).
 */
package com.nvidia.rtxremix.minecraft.vr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * The Remix composite for a Vivecraft eye pass. Upstream draws the Remix image
 * over the whole framebuffer after {@code GameRenderer.renderWorld}; in VR that
 * is after Vivecraft has already drawn its GUI panel, hands and controllers
 * inside {@code WorldRenderer.render}, so they were painted over.
 *
 * <p>This quad sits just in front of the far plane and is depth tested
 * ({@code LEQUAL}, no depth write): it fills every pixel GL left empty and
 * leaves anything GL drew with depth in place. With RTX on GL draws neither
 * terrain nor entities, so what stays is what Vivecraft drew.
 */
final class VrOverlay {

    /** Ortho depth range is [-1000, 1000]; z = -999 lands at window depth 0.9995. */
    private static final float QUAD_Z = -999.0f;

    private final Matrix4f ortho = new Matrix4f();
    private final Matrix4f identity = new Matrix4f();

    void render(int texId) {
        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer fb = client.getFramebuffer();
        if (texId <= 0 || fb == null) {
            return;
        }
        fb.beginWrite(true);

        Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();

        // Unit quad over the whole viewport; beginWrite set the viewport to the eye buffer.
        ortho.setOrtho(0.0f, 1.0f, 1.0f, 0.0f, -1000.0f, 1000.0f);
        RenderSystem.setProjectionMatrix(ortho, VertexSorter.BY_Z);
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().identity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        b.vertex(identity, 0, 0, QUAD_Z).texture(0.0f, 0.0f);
        b.vertex(identity, 0, 1, QUAD_Z).texture(0.0f, 1.0f);
        b.vertex(identity, 1, 1, QUAD_Z).texture(1.0f, 1.0f);
        b.vertex(identity, 1, 0, QUAD_Z).texture(1.0f, 0.0f);
        BufferRenderer.drawWithGlobalProgram(b.end());

        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProjection, VertexSorter.BY_Z);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
