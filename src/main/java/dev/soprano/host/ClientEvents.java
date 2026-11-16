package dev.soprano.host;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayList;
import java.util.List;

/** Client-thread callbacks shared by both Fabric and Meteor hosts. */
public final class ClientEvents {
    public enum Action { RELEASE, PRESS, REPEAT;
        public static Action fromGlfw(int action) {
            return switch (action) {
                case 0 -> RELEASE;
                case 1 -> PRESS;
                default -> REPEAT;
            };
        }
    }

    public interface Listener {
        default void tickPre() { }
        default void tickPost() { }
        default void disconnected() { }
        default void screenChanged(Screen screen) { }
        default void keyInput(KeyEvent input, Action action) { }
        default void mouseClick(Action action) { }
        default void mouseScroll() { }
        default void packetSending(Connection connection, Packet<?> packet) { }
        default void packetSent(Connection connection, Packet<?> packet) { }
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();

    private ClientEvents() { }

    public static void subscribe(Listener listener) {
        if (!LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void tickPre() { LISTENERS.forEach(Listener::tickPre); }
    public static void tickPost() { LISTENERS.forEach(Listener::tickPost); }
    public static void disconnected() { LISTENERS.forEach(Listener::disconnected); }
    public static void screenChanged(Screen screen) { LISTENERS.forEach(listener -> listener.screenChanged(screen)); }
    public static void keyInput(KeyEvent input, Action action) { LISTENERS.forEach(listener -> listener.keyInput(input, action)); }
    public static void mouseClick(Action action) { LISTENERS.forEach(listener -> listener.mouseClick(action)); }
    public static void mouseScroll() { LISTENERS.forEach(Listener::mouseScroll); }
    public static void packetSending(Connection connection, Packet<?> packet) {
        LISTENERS.forEach(listener -> listener.packetSending(connection, packet));
    }
    public static void packetSent(Connection connection, Packet<?> packet) {
        LISTENERS.forEach(listener -> listener.packetSent(connection, packet));
    }
}
