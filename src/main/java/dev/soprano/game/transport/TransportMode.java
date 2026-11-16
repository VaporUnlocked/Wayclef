package dev.soprano.game.transport;

/** The player's chosen movement mode for a navigation request. */
public enum TransportMode {
    AUTO("Auto"),
    WALK("Walk"),
    ELYTRA("Elytra"),
    BOAT("Boat"),
    MOUNT("Ground mount"),
    RAIL("Minecart"),
    STRIDER("Strider"),
    SWIM("Swim"),
    CUSHION("Cushion");

    private final String display;

    TransportMode(String display) { this.display = display; }
    @Override public String toString() { return display; }
}
