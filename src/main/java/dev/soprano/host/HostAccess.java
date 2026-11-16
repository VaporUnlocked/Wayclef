package dev.soprano.host;

import net.minecraft.client.gui.screens.Screen;

/** Optional host services. The default implementation has no Meteor dependency. */
public interface HostAccess {
    HostAccess STANDALONE = new HostAccess() { };

    default boolean meteor() { return false; }
    default boolean isCompatibleScreen(Screen screen) { return false; }
    default boolean freecamActive() { return false; }
    default String movementConflict() { return null; }
    default void navigationStarted() { }
    default void disconnected() { }
}
