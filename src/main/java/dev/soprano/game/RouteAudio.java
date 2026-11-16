package dev.soprano.game;

import dev.soprano.SopranoClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** Optional client-only audio for navigation state transitions. */
public final class RouteAudio {
    enum Cue { ARRIVED, PAUSED, INPUT_NEEDED }

    private static boolean enabled;
    private static float volume = 0.5f;

    private RouteAudio() { }

    public static void setEnabled(boolean value) { enabled = value; }

    public static void setVolume(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Cue volume must be 0–1.");
        volume = value;
    }

    static void signal(Cue cue) {
        if (!enabled || volume <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        try {
            SoundEvent sound = switch (cue) {
                case ARRIVED -> SoundEvents.NOTE_BLOCK_BELL.value();
                case PAUSED -> SoundEvents.NOTE_BLOCK_BASS.value();
                case INPUT_NEEDED -> SoundEvents.NOTE_BLOCK_PLING.value();
            };
            mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0f, volume));
        } catch (RuntimeException unavailable) {
            SopranoClient.LOG.debug("Could not play the Soprano navigation cue", unavailable);
        }
    }
}
