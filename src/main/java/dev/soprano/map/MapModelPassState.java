package dev.soprano.map;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2fc;

import java.util.List;

/** One model pass aligned to the most recently uploaded map frame. */
public record MapModelPassState(int x1, int y1, Matrix3x2fc pose, ScreenRectangle scissorArea,
                                VoxelRenderer.Frame frame, VoxelRenderer.Camera camera,
                                List<EntityRenderState> models) implements PictureInPictureRenderState {
    @Override public int x0() { return 0; }
    @Override public int y0() { return 0; }
    @Override public float scale() { return 1; }
    @Override public ScreenRectangle bounds() { return scissorArea; }
}
