package dev.soprano.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.soprano.map.MapVolume;
import dev.soprano.map.VoxelRenderer.Camera;

/** Translates the map camera on world axes, independent of orbit pitch. */
final class MapCameraMotion {
    private static final int FORWARD = 1;
    private static final int BACKWARD = 1 << 1;
    private static final int LEFT = 1 << 2;
    private static final int RIGHT = 1 << 3;
    private static final int UP = 1 << 4;
    private static final int DOWN_LEFT = 1 << 5;
    private static final int DOWN_RIGHT = 1 << 6;
    private static final int BOOST_LEFT = 1 << 7;
    private static final int BOOST_RIGHT = 1 << 8;

    private MapCameraMotion() { }

    static int keyFlag(int key) {
        return switch (key) {
            case InputConstants.KEY_W -> FORWARD;
            case InputConstants.KEY_S -> BACKWARD;
            case InputConstants.KEY_A -> LEFT;
            case InputConstants.KEY_D -> RIGHT;
            case InputConstants.KEY_SPACE -> UP;
            case InputConstants.KEY_LCONTROL -> DOWN_LEFT;
            case InputConstants.KEY_RCONTROL -> DOWN_RIGHT;
            case InputConstants.KEY_LSHIFT -> BOOST_LEFT;
            case InputConstants.KEY_RSHIFT -> BOOST_RIGHT;
            default -> 0;
        };
    }

    static boolean moving(int keys) {
        return (keys & (FORWARD | BACKWARD | LEFT | RIGHT | UP | DOWN_LEFT | DOWN_RIGHT)) != 0;
    }

    static Camera advance(Camera camera, MapVolume bounds, int keys, double blocksPerSecond, double seconds) {
        int forward = axis(keys, FORWARD, BACKWARD);
        int strafe = axis(keys, RIGHT, LEFT);
        int vertical = axis(keys, UP, DOWN_LEFT | DOWN_RIGHT);
        double length = Math.sqrt(forward * forward + strafe * strafe + vertical * vertical);
        if (length == 0 || seconds <= 0) return camera;

        double yaw = Math.toRadians(camera.yaw());
        double boost = (keys & (BOOST_LEFT | BOOST_RIGHT)) != 0 ? 5 : 1;
        double distance = blocksPerSecond * Math.clamp(camera.distance() / 60, .28, 7) * seconds * boost / length;
        double dx = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) * distance;
        double dz = (-strafe * Math.sin(yaw) - forward * Math.cos(yaw)) * distance;
        return translate(camera, bounds, dx, vertical * distance, dz);
    }

    static Camera translate(Camera camera, MapVolume bounds, double dx, double dy, double dz) {
        return new Camera(Math.clamp(camera.x() + dx, bounds.minX(), bounds.maxX()),
            Math.clamp(camera.y() + dy, bounds.minY(), bounds.maxY()),
            Math.clamp(camera.z() + dz, bounds.minZ(), bounds.maxZ()), camera.yaw(), camera.pitch(), camera.distance());
    }

    private static int axis(int keys, int positive, int negative) {
        return ((keys & positive) != 0 ? 1 : 0) - ((keys & negative) != 0 ? 1 : 0);
    }
}
