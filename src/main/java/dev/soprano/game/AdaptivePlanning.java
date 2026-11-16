package dev.soprano.game;

/** Bounds a route's search slices using recent cost and client frame rate. */
final class AdaptivePlanning {
    record Slice(int nodes, long nanos) { }

    private static final int TARGET_FPS = 60;
    private static final long MIN_SLICE_NANOS = 250_000;
    private double nanosPerNode;

    Slice slice(int nodeLimit, int millisLimit, boolean adaptive, int fps, int frameLimit) {
        long timeLimit = millisLimit * 1_000_000L;
        if (!adaptive) return new Slice(nodeLimit, timeLimit);

        int target = frameLimit > 0 ? Math.min(TARGET_FPS, frameLimit) : TARGET_FPS;
        double headroom = fps > 0 ? Math.clamp((double) fps / target, 0.25, 1.0) : 1.0;
        long nanos = Math.min(timeLimit, Math.max(MIN_SLICE_NANOS, Math.round(timeLimit * headroom)));
        int nodes = Math.max(1, (int) (nodeLimit * headroom));
        if (nanosPerNode > 0) nodes = Math.max(1, Math.min(nodes, (int) (nanos / nanosPerNode)));
        return new Slice(nodes, nanos);
    }

    void observe(long elapsedNanos, int expandedNodes) {
        if (elapsedNanos <= 0 || expandedNodes <= 0) return;
        double measured = (double) elapsedNanos / expandedNodes;
        nanosPerNode = nanosPerNode == 0 ? measured : nanosPerNode * 0.75 + measured * 0.25;
    }

    void reset() { nanosPerNode = 0; }
}
