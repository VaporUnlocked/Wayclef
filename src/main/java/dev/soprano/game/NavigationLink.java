package dev.soprano.game;

import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;

import java.util.function.BooleanSupplier;

/** Client-thread API for other installed addons to request an owned walking job. */
public final class NavigationLink {
    private static final Navigation.Options WALK_BACK = new Navigation.Options(
        new IncrementalSearch.Config(true, true, 1, 96, 16_000, false), 48, true, true);

    private NavigationLink() { }

    public static boolean begin(Object owner, int x, int y16, int z) {
        return Navigation.get().requestOwned(owner, new Goal(x, y16, z, 0), WALK_BACK);
    }

    public static String state(Object owner) { return Navigation.get().ownedState(owner); }
    public static String status(Object owner) { return Navigation.get().ownedStatus(owner); }
    public static void cancel(Object owner) { Navigation.get().cancelOwned(owner); }
    public static boolean busy() { return ReplaceActivity.get().active() || Navigation.get().movementInUse(); }
    public static void movementGuard(BooleanSupplier guard) { Navigation.get().setMovementGuard(guard); }
}
