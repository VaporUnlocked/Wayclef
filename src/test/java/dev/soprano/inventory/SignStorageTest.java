package dev.soprano.inventory;

import dev.soprano.inventory.SignStorage.Amount;
import dev.soprano.inventory.SignStorage.Container;
import dev.soprano.inventory.SignStorage.Direction;
import dev.soprano.inventory.SignStorage.Front;
import dev.soprano.inventory.SignStorage.Limits;
import dev.soprano.inventory.SignStorage.Position;
import dev.soprano.inventory.SignStorage.Sign;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class SignStorageTest {
    private static final Set<String> IDS = Set.of("minecraft:iron_ingot", "minecraft:bread", "minecraft:stone", "example:widget");
    private static final SignStorage.ItemRegistry ITEMS = new SignStorage.ItemRegistry() {
        @Override public boolean contains(String itemId) { return IDS.contains(itemId); }
        @Override public Collection<String> findDisplayName(String name) {
            return switch (name) {
                case "iron ingot" -> List.of("minecraft:iron_ingot");
                case "bread" -> List.of("minecraft:bread");
                case "ambiguous" -> List.of("minecraft:stone", "example:widget");
                default -> List.of();
            };
        }
    };

    public static void main(String[] args) {
        var box = new Container(new Position(1, 60, 1), Front.NORTH, 5);
        var front = new Sign(new Position(1, 60, 0), List.of("IRON   INGOT", " bread ", "minecraft:stone", "example:widget"));
        var labels = SignStorage.label(List.of(box), List.of(front), ITEMS);
        assert labels.size() == 1 && labels.getFirst().acceptedItems().equals(IDS);
        var above = new Sign(new Position(1, 61, 1), List.of("minecraft:stone"));
        assert SignStorage.label(List.of(box), List.of(front, above), ITEMS).isEmpty();

        var other = new Container(new Position(1, 59, 0), Front.EAST, 7);
        assert SignStorage.label(List.of(box, other), List.of(front), ITEMS).isEmpty();
        assert SignStorage.label(List.of(box), List.of(new Sign(front.position(), List.of("ambiguous"))), ITEMS).isEmpty();

        var plan = SignStorage.plan(Direction.DEPOSIT,
            List.of(new Amount("minecraft:stone", 5), new Amount("example:widget", 2)), labels,
            new Limits(5, 1, 2), (container, direction, id, amount) -> {
                assert container.equals(box) && direction == Direction.DEPOSIT && id.equals("minecraft:stone");
                return 3;
            });
        assert plan.checks() == 1 && plan.transfers().getFirst().amount().count() == 3;
        assert plan.remaining().equals(List.of(new Amount("minecraft:stone", 2), new Amount("example:widget", 2)));
        System.out.println("SignStorageTest passed");
    }
}
