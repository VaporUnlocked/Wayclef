package dev.soprano.gpu;

import java.util.Arrays;

public final class DiscreteComputeTest {
    private static final String SHADER = """
        #version 450
        layout(local_size_x = 64) in;
        layout(set = 0, binding = 0, std430) buffer Storage { int words[]; } data;
        layout(push_constant) uniform Params { int iteration; int increment; } params;
        void main() {
            uint index = gl_GlobalInvocationID.x;
            if (index >= data.words.length()) return;
            data.words[index] += params.iteration + params.increment;
        }
        """;

    public static void main(String[] args) {
        rejects(() -> DiscreteCompute.run("", new int[1], 1, 1, new int[0]));
        rejects(() -> DiscreteCompute.run(SHADER, new int[0], 1, 1, new int[0]));
        rejects(() -> DiscreteCompute.run(SHADER, new int[1], 0, 1, new int[0]));
        rejects(() -> DiscreteCompute.run(SHADER, new int[1], 1, 1_025, new int[0]));
        rejects(() -> DiscreteCompute.run(SHADER, new int[1], 2_000, 1_001, new int[0]));
        rejects(() -> DiscreteCompute.run(SHADER, new int[1], 1, 1, new int[32]));
        System.out.println("Soprano compute dispatch limits passed.");
        if (Arrays.asList(args).contains("--require-gpu")) exerciseDevice();
    }

    private static void exerciseDevice() {
        int[] storage = new int[130];
        for (int index = 0; index < storage.length; index++) storage[index] = index - 50;
        try {
            var first = DiscreteCompute.run(SHADER, storage, 3, 4, new int[] {3});
            var second = DiscreteCompute.run(SHADER, first.storage(), 3, 1, new int[] {-7});
            for (int index = 0; index < storage.length; index++) {
                if (storage[index] != index - 50 || first.storage()[index] != index - 32
                    || second.storage()[index] != index - 39) throw new AssertionError("Incorrect GPU output at word " + index);
            }
            System.out.println("Soprano compute dispatch and readback passed on " + first.deviceName() + ".");
        } finally {
            DiscreteCompute.close();
        }
    }

    private static void rejects(Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid GPU dispatch was accepted.");
    }
}
