package dev.soprano.task;

import dev.soprano.map.MapVolume;
import net.minecraft.core.BlockPos;

public final class JobSpecTest {
    private static final MapVolume.Box FIELD = new MapVolume.Box(-2, 62, 8, 5, 64, 14);

    private static JobSpec parse(String command, MapVolume.Box region, BlockPos target) {
        return JobSpec.parse(command, region, target, null);
    }

    public static void main(String[] args) {
        JobSpec mine = parse("  MINE    stone,mod:crystal_ore   12 ", FIELD, new BlockPos(3, 66, 4));
        assert mine.kind() == JobSpec.Kind.MINE;
        assert mine.limit() == 12 && mine.target() == null;
        assert mine.ids().getFirst().equals("minecraft:stone") && mine.ids().getLast().equals("mod:crystal_ore");
        assert JobSpec.decode(mine.encode()).equals(mine);

        BlockPos.MutableBlockPos selected = new BlockPos.MutableBlockPos(20, 65, -12);
        JobSpec tunnel = parse("tunnel 3 4 -25 8 120", FIELD, selected);
        selected.set(20, 10, 10);
        assert tunnel.target().equals(new BlockPos(20, 65, -12));
        assert tunnel.region() == null && tunnel.width() == 3 && tunnel.height() == 4;
        assert tunnel.grade() == -25 && tunnel.torchSpacing() == 8;
        assert JobSpec.decode(tunnel.encode()).equals(tunnel);

        JobSpec restock = parse("restock bread=8,mod:widget=3 9", null, null);
        assert restock.amounts().get("minecraft:bread") == 8;
        assert restock.amounts().get("mod:widget") == 3 && restock.limit() == 9;
        assert JobSpec.decode(restock.encode()).equals(restock);

        BlockPos.MutableBlockPos origin = new BlockPos.MutableBlockPos(-17, -63, 19);
        JobSpec nearby = JobSpec.parse("mine diamond_ore,deepslate_diamond_ore all 24", FIELD, selected, origin);
        origin.set(0, 0, 0);
        assert nearby.nearbyRadius() == 24 && nearby.targetsAll();
        assert nearby.region() == null && nearby.target().equals(new BlockPos(-17, -63, 19));
        assert JobSpec.decode(nearby.encode()).equals(nearby);
        assert WorldJob.nearbyArea(nearby.target(), 24, -64, 319).equals(new MapVolume.Box(-41, -64, -5, 7, -39, 43));
        JobSpec crops = JobSpec.parse("crops 20 16", null, null, BlockPos.ZERO);
        assert crops.nearbyRadius() == 16 && JobSpec.decode(crops.encode()).equals(crops);
        JobSpec pickup = JobSpec.parse("collect minecraft:wheat 10 16", null, null, BlockPos.ZERO);
        assert !pickup.targetsAll() && pickup.limit() == 10 && JobSpec.decode(pickup.encode()).equals(pickup);
        JobSpec gather = JobSpec.parse("gather mod:plate 15", null, null, new BlockPos(7, 61, -3));
        assert gather.ids().equals(java.util.List.of("mod:plate")) && gather.limit() == 15;
        assert gather.target().equals(new BlockPos(7, 61, -3)) && JobSpec.decode(gather.encode()).equals(gather);
        JobSpec fill = JobSpec.parse("fillbucket mod:oil_bucket", null, BlockPos.ZERO, null);
        assert fill.nearbyRadius() == 0 && JobSpec.decode(fill.encode()).equals(fill);
        JobSpec capture = JobSpec.parse("capture pufferfish_bucket 24", null, null, BlockPos.ZERO);
        assert capture.nearbyRadius() == 24 && capture.ids().getFirst().equals("minecraft:pufferfish_bucket")
            && JobSpec.decode(capture.encode()).equals(capture);
        JobSpec stonecut = parse("stonecut quartz_block quartz_slab 2", null, BlockPos.ZERO);
        assert stonecut.kind() == JobSpec.Kind.STONECUT && stonecut.limit() == 2;
        assert stonecut.ids().equals(java.util.List.of("minecraft:quartz_block", "minecraft:quartz_slab"))
            && JobSpec.decode(stonecut.encode()).equals(stonecut);
        JobSpec smith = parse("smith netherite_pickaxe 1", null, BlockPos.ZERO);
        assert smith.kind() == JobSpec.Kind.SMITH && smith.target().equals(BlockPos.ZERO)
            && JobSpec.decode(smith.encode()).equals(smith);
        JobSpec campfire = parse("campfire chicken cooked_chicken 4", null, BlockPos.ZERO);
        assert campfire.kind() == JobSpec.Kind.CAMPFIRE && campfire.limit() == 4
            && JobSpec.decode(campfire.encode()).equals(campfire);

        expectInvalid(() -> parse("quarry 2", null, null));
        expectInvalid(() -> parse("tunnel 3 2 -101 5 100", null, BlockPos.ZERO));
        expectInvalid(() -> parse("restock bread=2,minecraft:bread=3", null, null));
        expectInvalid(() -> parse("craft bread 0", null, null));
        expectInvalid(() -> parse("stonecut stone stone 1", null, BlockPos.ZERO));
        expectInvalid(() -> parse("smith netherite_pickaxe 1", null, null));
        expectInvalid(() -> parse("campfire chicken cooked_chicken 5", null, BlockPos.ZERO));
        expectInvalid(() -> JobSpec.parse("mine stone all 33", null, null, BlockPos.ZERO));
        expectInvalid(() -> JobSpec.decode("collect wheat all 16||"));
        expectInvalid(() -> JobSpec.decode("return||1,2"));
        expectInvalid(() -> parse("crops 4", new MapVolume.Box(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0), null));
        System.out.println("JobSpecTest passed");
    }

    private static void expectInvalid(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected an invalid job to be rejected");
        } catch (IllegalArgumentException expected) { }
    }
}
