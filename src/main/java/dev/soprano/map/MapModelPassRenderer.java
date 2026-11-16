package dev.soprano.map;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.soprano.SopranoClient;
import dev.soprano.mixin.PictureInPictureRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/** Renders vanilla entity geometry using the map camera and the map's terrain depth. */
public final class MapModelPassRenderer extends PictureInPictureRenderer<MapModelPassState> {
    private static final float NEAR = .055f;
    private static final float FAR = 8_192;
    private static final float TERRAIN_TOLERANCE = .025f;

    private final Projection modelProjection = new Projection();
    private final ProjectionMatrixBuffer modelProjectionBuffer = new ProjectionMatrixBuffer("Soprano map model projection");
    private final Set<EntityType<?>> unsupported = new HashSet<>();
    private VoxelRenderer.Frame uploadedFrame;
    private GpuTexture sampledDepth;
    private GpuTextureView sampledDepthView;
    private ByteBuffer depthUpload;

    @Override public Class<MapModelPassState> getRenderStateClass() { return MapModelPassState.class; }
    @Override protected String getTextureLabel() { return "Soprano map entities"; }

    @Override
    protected void renderToTexture(MapModelPassState scene, PoseStack pose, SubmitNodeCollector collector) {
        Minecraft client = Minecraft.getInstance();
        GpuTextureView depthAttachment = ((PictureInPictureRendererAccessor) (PictureInPictureRenderer<?>) this).soprano$modelDepthView();
        writeTerrainDepth(scene.frame(), depthAttachment);

        modelProjection.setupPerspective(FAR, NEAR, (float) VoxelRenderer.FIELD_OF_VIEW,
            scene.frame().width(), scene.frame().height());
        RenderSystem.setProjectionMatrix(modelProjectionBuffer.getBuffer(modelProjection), ProjectionType.PERSPECTIVE);
        client.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

        VoxelRenderer.Camera camera = scene.camera();
        float yaw = (float) Math.toRadians(camera.yaw());
        float pitch = (float) Math.toRadians(camera.pitch());
        Quaternionf viewRotation = new Quaternionf().rotationX(pitch).rotateY(-yaw);
        Vec3 origin = cameraOrigin(camera);
        CameraRenderState state = new CameraRenderState();
        state.pos = origin;
        state.blockPos = BlockPos.containing(origin);
        state.orientation = new Quaternionf(viewRotation).conjugate();
        state.xRot = (float) camera.pitch();
        state.yRot = (float) (180 - camera.yaw());
        state.initialized = true;
        state.depthFar = FAR;

        pose.setIdentity();
        pose.rotate(viewRotation);
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        for (EntityRenderState model : scene.models()) {
            if (unsupported.contains(model.entityType)) continue;
            try {
                dispatcher.submit(model, state, model.x - origin.x, model.y - origin.y, model.z - origin.z, pose, collector);
            } catch (RuntimeException failure) {
                if (unsupported.add(model.entityType))
                    SopranoClient.LOG.warn("Soprano skipped an entity model in its planning map", failure);
                while (!pose.isEmpty()) pose.popPose();
                pose.setIdentity();
                pose.rotate(viewRotation);
            }
        }
    }

    private void writeTerrainDepth(VoxelRenderer.Frame frame, GpuTextureView depthAttachment) {
        int width = frame.width();
        int height = frame.height();
        if (sampledDepth == null || sampledDepth.getWidth(0) != width || sampledDepth.getHeight(0) != height) {
            closeSampledDepth();
            sampledDepth = RenderSystem.getDevice().createTexture(() -> "Soprano terrain depth",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.R32_FLOAT, width, height, 1, 1);
            sampledDepthView = RenderSystem.getDevice().createTextureView(sampledDepth);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (uploadedFrame != frame) {
            int bytes = width * height * Float.BYTES;
            if (depthUpload == null || depthUpload.capacity() < bytes) {
                if (depthUpload != null) MemoryUtil.memFree(depthUpload);
                depthUpload = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
            }
            depthUpload.clear();
            // The vanilla depth blit uses normalized UVs. Upload at the terrain's resolution and
            // let nearest sampling align it with the terrain blit in the larger model target.
            for (int y = height - 1; y >= 0; y--) {
                for (int x = 0; x < width; x++) {
                    depthUpload.putFloat(windowDepth(frame.entityOcclusionAt(x, y)));
                }
            }
            depthUpload.flip();
            encoder.writeToTexture(sampledDepth, depthUpload, 0, 0, 0, 0, width, height);
            uploadedFrame = frame;
        }
        RenderPassDescriptor pass = RenderPassDescriptor.builder(() -> "Soprano terrain occlusion")
            .withDepthAttachment(depthAttachment).build();
        try (RenderPass depthPass = encoder.createRenderPass(pass)) {
            RenderSystem.bindDefaultUniforms(depthPass);
            depthPass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH));
            depthPass.setUniform("InSampler", sampledDepthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            depthPass.draw(3, 1, 0, 0);
        }
    }

    private static float windowDepth(float distance) {
        if (distance >= FAR) return 0;
        float depth = Math.max(NEAR, distance + TERRAIN_TOLERANCE);
        return NEAR * (FAR / depth - 1) / (FAR - NEAR);
    }

    static Vec3 cameraOrigin(VoxelRenderer.Camera camera) {
        double yaw = Math.toRadians(camera.yaw());
        double pitch = Math.toRadians(camera.pitch());
        double horizontal = camera.distance() * Math.cos(pitch);
        return new Vec3(camera.x() + Math.sin(yaw) * horizontal, camera.y() + Math.sin(pitch) * camera.distance(),
            camera.z() + Math.cos(yaw) * horizontal);
    }

    private void closeSampledDepth() {
        if (sampledDepthView != null) sampledDepthView.close();
        if (sampledDepth != null) sampledDepth.close();
        sampledDepthView = null;
        sampledDepth = null;
        uploadedFrame = null;
    }

    @Override public void close() {
        try {
            closeSampledDepth();
            if (depthUpload != null) {
                MemoryUtil.memFree(depthUpload);
                depthUpload = null;
            }
            modelProjectionBuffer.close();
        } finally {
            super.close();
        }
    }
}
