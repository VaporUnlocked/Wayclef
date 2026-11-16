package dev.soprano.build;

import dev.soprano.build.BuildPlan.Cell;
import dev.soprano.build.ScaffoldLedger.CleanupCheck;
import dev.soprano.build.ScaffoldLedger.CleanupStatus;
import dev.soprano.build.ScaffoldLedger.Observation;

import java.util.Optional;

public final class ScaffoldLedgerTest {
    private static final Cell FIRST = new Cell(2, 70, 2);
    private static final Cell LAST = new Cell(2, 71, 2);
    private static final String STONE = "minecraft:stone";
    private static final String AIR = "minecraft:air";

    public static void main(String[] args) {
        onlyAttributedPlacementOwns();
        cleanupRequiresFreshOwnershipReachAndExit();
        changedOrUnloadedBlocksAreNeverOwned();
    }

    private static void onlyAttributedPlacementOwns() {
        ScaffoldLedger ledger = new ScaffoldLedger();
        assert ledger.beginPlacement(FIRST, new Observation(STONE, 1), STONE).isEmpty();
        var pending = ledger.beginPlacement(FIRST, new Observation(AIR, 1), STONE).orElseThrow();
        ledger.observeBlockChange(FIRST, new Observation(STONE, 2));
        assert !ledger.confirmOwnPlacement(pending, new Observation(STONE, 2));
        assert ledger.ownedCount() == 0;
        pending = ledger.beginPlacement(FIRST, new Observation(AIR, 3), STONE).orElseThrow();
        assert !ledger.confirmOwnPlacement(pending, new Observation(STONE, 3));
        assert ledger.confirmOwnPlacement(pending, new Observation(STONE, 4));
        assert ledger.ownedCount() == 1;
    }

    private static void cleanupRequiresFreshOwnershipReachAndExit() {
        ScaffoldLedger ledger = new ScaffoldLedger();
        own(ledger, FIRST, 1);
        Observation latest = own(ledger, LAST, 3);
        assert ledger.nextCleanupCell().orElseThrow().equals(LAST);
        assert ledger.beginRemoval(check(FIRST, latest, true, true)).status() == CleanupStatus.TARGET_CHANGED;
        assert ledger.beginRemoval(check(LAST, new Observation(STONE, 3), true, true)).status() == CleanupStatus.STALE_OBSERVATION;
        assert ledger.beginRemoval(check(LAST, latest, false, true)).status() == CleanupStatus.OUT_OF_REACH;
        assert ledger.beginRemoval(check(LAST, latest, true, false)).status() == CleanupStatus.EXIT_BLOCKED;
        var removal = ledger.beginRemoval(check(LAST, latest, true, true)).removal().orElseThrow();
        assert ledger.recheckRemoval(removal, check(LAST, latest, true, false)) == CleanupStatus.EXIT_BLOCKED;
        assert ledger.releaseFailedRemoval(removal, Optional.of(latest));
        removal = ledger.beginRemoval(check(LAST, latest, true, true)).removal().orElseThrow();
        assert ledger.confirmOwnRemoval(removal, new Observation(AIR, 5));
        assert ledger.nextCleanupCell().orElseThrow().equals(FIRST);
    }

    private static void changedOrUnloadedBlocksAreNeverOwned() {
        ScaffoldLedger ledger = new ScaffoldLedger();
        own(ledger, FIRST, 10);
        ledger.observeBlockChange(FIRST, new Observation(STONE, 12));
        assert ledger.ownedCount() == 0;
        own(ledger, FIRST, 13);
        var placement = ledger.beginPlacement(LAST, new Observation(AIR, 15), STONE).orElseThrow();
        ledger.loseChunk(0, 0);
        assert ledger.ownedCount() == 0;
        assert !ledger.confirmOwnPlacement(placement, new Observation(STONE, 16));
    }

    private static Observation own(ScaffoldLedger ledger, Cell cell, long before) {
        var pending = ledger.beginPlacement(cell, new Observation(AIR, before), STONE).orElseThrow();
        Observation placed = new Observation(STONE, before + 1);
        assert ledger.confirmOwnPlacement(pending, placed);
        return placed;
    }

    private static CleanupCheck check(Cell cell, Observation state, boolean reachable, boolean exit) {
        return new CleanupCheck(cell, Optional.of(state), reachable, exit);
    }
}
