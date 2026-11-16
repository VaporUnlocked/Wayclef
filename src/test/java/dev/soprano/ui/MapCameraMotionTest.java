package dev.soprano.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.soprano.map.MapMaterial;
import dev.soprano.map.MapVolume;
import dev.soprano.map.VoxelRenderer.Camera;

public final class MapCameraMotionTest {
    private static final double EPSILON = 1e-8;
    private static int checks;

    public static void main(String[] args) {
        MapVolume bounds = new Bounds();
        Camera start = new Camera(0, 64, 0, 38, 82, 60);
        int up = MapCameraMotion.keyFlag(InputConstants.KEY_SPACE);
        int leftControl = MapCameraMotion.keyFlag(InputConstants.KEY_LCONTROL);
        int rightControl = MapCameraMotion.keyFlag(InputConstants.KEY_RCONTROL);
        int leftShift = MapCameraMotion.keyFlag(InputConstants.KEY_LSHIFT);
        int rightShift = MapCameraMotion.keyFlag(InputConstants.KEY_RSHIFT);
        int forward = MapCameraMotion.keyFlag(InputConstants.KEY_W);
        int backward = MapCameraMotion.keyFlag(InputConstants.KEY_S);

        Camera raised = MapCameraMotion.advance(start, bounds, up, 20, .05);
        check(close(raised.x(), 0) && close(raised.z(), 0) && close(raised.y(), 65), "Space moves only on world Y");
        check(raised.pitch() == start.pitch() && raised.yaw() == start.yaw() && raised.distance() == start.distance(),
            "vertical translation preserves orbit");
        Camera lowered = MapCameraMotion.advance(start, bounds, leftControl | rightControl, 20, .05);
        check(close(lowered.x(), 0) && close(lowered.z(), 0) && close(lowered.y(), 63), "either or both Ctrl keys move down once");
        Camera boosted = MapCameraMotion.advance(start, bounds, up | leftShift | rightShift, 20, .05);
        check(close(boosted.y(), 69), "either or both Shift keys apply exactly five times speed");
        Camera diagonal = MapCameraMotion.advance(start, bounds, forward | up | rightShift, 20, .05);
        double horizontal = Math.hypot(diagonal.x() - start.x(), diagonal.z() - start.z());
        check(close(horizontal, 5 / Math.sqrt(2)) && close(diagonal.y() - start.y(), horizontal),
            "combined vertical and horizontal inputs have normalized speed");
        check(MapCameraMotion.advance(start, bounds, forward | backward | up | rightControl, 20, .05).equals(start),
            "opposing directions cancel");
        check(!MapCameraMotion.moving(rightShift) && MapCameraMotion.advance(start, bounds, rightShift, 20, .05).equals(start),
            "Shift alone leaves the camera idle");
        check(close(MapCameraMotion.advance(new Camera(0, 99, 0, 0, 10, 60), bounds, up | leftShift, 20, .05).y(), 100),
            "vertical movement stops at the surveyed bounds");
        System.out.println("Soprano map camera passed " + checks + " checks.");
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
        checks++;
    }

    private static final class Bounds implements MapVolume {
        @Override public int minX() { return -100; }
        @Override public int minY() { return -64; }
        @Override public int minZ() { return -100; }
        @Override public int maxX() { return 100; }
        @Override public int maxY() { return 100; }
        @Override public int maxZ() { return 100; }
        @Override public int blockAt(int x, int y, int z) { return 0; }
        @Override public int sectionState(int x, int y, int z) { return 0; }
        @Override public MapMaterial material(int id) { return null; }
        @Override public long revision() { return 0; }
    }
}
