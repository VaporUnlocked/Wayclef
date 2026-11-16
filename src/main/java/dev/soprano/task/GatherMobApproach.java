package dev.soprano.task;

import dev.soprano.game.Navigation;
import dev.soprano.path.Goal;
import dev.soprano.path.Node;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;

/** Walks to a verified ordinary interaction stance for a live mob. */
final class GatherMobApproach {
    private final ClientLevel level;
    private final LocalPlayer player;
    private final Mob mob;
    private final Object owner;
    private final Navigation.Options walk;
    private BlockPos targetPosition;
    private List<Node> stands = List.of();
    private int next;
    private boolean requested;

    GatherMobApproach(ClientLevel level, LocalPlayer player, Mob mob, Object owner, Navigation.Options walk) {
        this.level = level;
        this.player = player;
        this.mob = mob;
        this.owner = owner;
        this.walk = walk;
    }

    EntityHitResult tick() {
        if (!mob.isAlive() || mob.isRemoved() || !WorldJob.loaded(level, mob.blockPosition()))
            throw new IllegalStateException("The selected mob left loaded terrain.");
        var hit = player.onGround() ? GatherHunt.interactionHit(level, player, mob, player.getEyePosition()) : null;
        if (hit != null) {
            cancel();
            return hit;
        }
        var navigation = Navigation.get();
        var here = mob.blockPosition();
        if (targetPosition == null || targetPosition.distSqr(here) >= 4) {
            cancel();
            targetPosition = here.immutable();
            stands = GatherHunt.interactionApproaches(level, player, mob);
            next = 0;
        } else if (requested) {
            switch (navigation.ownedState(owner)) {
                case "ARRIVED", "FAILED", "STOPPED", "PAUSED", "IDLE" -> requested = false;
                default -> { return null; }
            }
        }
        if (next >= stands.size()) throw new IllegalStateException("No verified route reaches the selected mob.");
        Node stand = stands.get(next++);
        if (!navigation.requestOwned(owner, new Goal(stand.x(), stand.y16(), stand.z(), 0), walk))
            throw new IllegalStateException("Another feature controls movement.");
        requested = true;
        return null;
    }

    void cancel() {
        if (requested) Navigation.get().cancelOwned(owner);
        requested = false;
    }

    void suspend() {
        cancel();
        targetPosition = null;
    }
}
