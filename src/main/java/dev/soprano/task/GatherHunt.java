package dev.soprano.task;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.game.ToolDurability;
import dev.soprano.inventory.InventoryJobs;
import dev.soprano.mixin.MobBucketItemAccessor;
import dev.soprano.path.Node;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.AddValue;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Selects mob targets and checks vanilla interactions from the local player's body. */
final class GatherHunt {
    private static final double REACH_MARGIN = .08;
    private static final int REPEAT_HIT_DELAY = 10;
    private static final float NORMAL_CHARGE = .91f;
    private static final float MIN_FINISHING_CHARGE = .5f;
    private static final double[] AIM_HEIGHTS = {.5, .75, .25};
    private static final TagKey<Enchantment> SMELTS_LOOT =
        TagKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace("smelts_loot"));

    private final ClientLevel level;
    private final LocalPlayer player;
    private final MultiPlayerGameMode mode;
    private final BlockPos source;
    private final UUID sourceMob;
    private final JobSpec.HuntLoot huntLoot;
    private final Item wanted;
    private final Item captureResult;
    private final Item captureContainer;
    private final Set<EntityType<?>> types = new HashSet<>();
    private final Set<UUID> ignored = new HashSet<>();
    private UUID lastStrike;
    private int lastStrikeTick;

    GatherHunt(JobSpec spec, ClientLevel level, LocalPlayer player, MultiPlayerGameMode mode) {
        this.level = level;
        this.player = player;
        this.mode = mode;
        source = spec.target();
        sourceMob = spec.entityId();
        huntLoot = spec.huntLoot();
        wanted = InventoryJobs.item(spec.ids().getFirst());
        if (spec.kind() == JobSpec.Kind.CAPTURE) {
            captureResult = wanted;
            captureContainer = captureIngredient(captureResult);
            if (captureContainer == null) throw new IllegalArgumentException("This item cannot be gathered by using a bucket on a mob.");
            if (captureResult == Items.MILK_BUCKET) types.addAll(Set.of(EntityTypes.COW, EntityTypes.MOOSHROOM, EntityTypes.GOAT));
            else types.add(((MobBucketItemAccessor) captureResult).soprano$type());
        } else {
            captureResult = captureContainer = null;
            for (String id : spec.ids().subList(1, spec.ids().size())) {
                types.add(BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown mob type: " + id)));
            }
        }
    }

    static AABB loadedMobBounds(ClientLevel level, LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        int chunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        int x = feet.getX() >> 4, z = feet.getZ() >> 4;
        return new AABB((x - chunks) * 16.0, level.getMinY(), (z - chunks) * 16.0,
            (x + chunks + 1) * 16.0, level.getMaxY() + 1.0, (z + chunks + 1) * 16.0);
    }

    static Item captureIngredient(Item result) {
        if (result == Items.MILK_BUCKET) return Items.BUCKET;
        if (!(result instanceof MobBucketItem bucket)) return null;
        var fluid = bucket.getContent();
        if (fluid.defaultFluidState().isEmpty()) return Items.BUCKET;
        Item container = fluid.getBucket();
        return container == Items.AIR || container == result ? null : container;
    }

    Mob nearest() {
        return level.getEntitiesOfClass(Mob.class, loadedMobBounds(level, player), this::eligible).stream()
            .min(Comparator.<Mob>comparingDouble(mob -> captureResult == null ? source.distToCenterSqr(mob.position()) : mob.distanceToSqr(player))
                .thenComparingDouble(mob -> mob.distanceToSqr(player)).thenComparingInt(Entity::getId))
            .orElse(null);
    }

    boolean eligible(Mob mob) {
        if (mob == null || mob.level() != level || !mob.isAlive() || mob.isRemoved() || !mob.isPickable()
            || sourceMob != null && !sourceMob.equals(mob.getUUID())
            || !types.contains(mob.getType()) || ignored.contains(mob.getUUID())
            || !level.hasChunk(mob.getBlockX() >> 4, mob.getBlockZ() >> 4)
            || !level.getWorldBorder().isWithinBounds(mob.blockPosition())) return false;
        if (captureResult == null) return GatherSources.stillProduces(mob, player, wanted, huntLoot);
        return !mob.isPassenger() && !mob.isVehicle() && canFillFrom(mob, captureContainer.getDefaultInstance());
    }

    boolean shearing() { return huntLoot == JobSpec.HuntLoot.SHEAR; }

    static int shearingToolSlot(LocalPlayer player) {
        var inventory = player.getInventory();
        if (usableShears(inventory.getSelectedItem())) return inventory.getSelectedSlot();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (usableShears(inventory.getItem(slot))) return slot;
        }
        return -1;
    }

    static boolean usableShears(ItemStack stack) {
        return stack.is(Items.SHEARS) && ToolDurability.pauseReason(stack, 1, ToolDurability.configuredReserve()) == null;
    }

    private boolean canFillFrom(Mob mob, ItemStack held) {
        if (captureResult == Items.MILK_BUCKET) return !mob.isBaby() && held.is(Items.BUCKET);
        return mob instanceof Bucketable bucketable && bucketable.getBucketItemStack().is(captureResult)
            && bucketable.canBePickedUpWithBucket(held);
    }

    void ignore(Mob mob) {
        if (mob != null) ignored.add(mob.getUUID());
    }

    List<Node> approaches(Mob mob) {
        if (!eligible(mob)) return List.of();
        return approaches(level, player, mob, reach(), eye -> aimPoint(mob, eye) != null);
    }

    static List<Node> interactionApproaches(ClientLevel level, LocalPlayer player, Mob mob) {
        double reach = player.entityInteractionRange();
        return approaches(level, player, mob, reach, eye -> interactionHit(level, player, mob, eye) != null);
    }

    private static List<Node> approaches(ClientLevel level, LocalPlayer player, Mob mob, double reach,
                                         java.util.function.Predicate<Vec3> canReach) {
        var terrain = new MinecraftTerrain(level, player);
        var near = mob.blockPosition();
        int radius = (int) Math.clamp(Math.ceil(reach), 1, 8);
        int vertical = (radius + 1) * 16;
        int targetY16 = near.getY() * 16;
        double eyeHeight = player.getEyeHeight(Pose.STANDING);
        var candidates = new ArrayList<Node>();
        terrain.beginSearchSlice();
        try {
            for (int x = near.getX() - radius; x <= near.getX() + radius; x++) {
                for (int z = near.getZ() - radius; z <= near.getZ() + radius; z++) {
                    if (!Node.validColumn(x) || !Node.validColumn(z)) continue;
                    for (Node stand : terrain.floors(x, z, targetY16 - vertical, targetY16 + vertical)) {
                        if (!terrain.canFinish(stand)) continue;
                        Vec3 eye = new Vec3(stand.centerX(), stand.y() + eyeHeight, stand.centerZ());
                        if (canReach.test(eye)) candidates.add(stand);
                    }
                }
            }
        } finally {
            terrain.endSearchSlice();
        }
        Vec3 feet = player.position();
        var closest = candidates.stream().distinct().sorted(Comparator.<Node>comparingDouble(
            stand -> feet.distanceToSqr(stand.centerX(), stand.y(), stand.centerZ())).thenComparingInt(Node::y16)
            .thenComparingInt(Node::x).thenComparingInt(Node::z)).toList();
        var approaches = new LinkedHashSet<Node>();
        var sides = new HashSet<Integer>();
        for (Node stand : closest) {
            int dx = stand.x() - near.getX(), dz = stand.z() - near.getZ();
            int side = Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) : 2 * Integer.signum(dz);
            if (sides.add(side)) approaches.add(stand);
        }
        approaches.addAll(closest);
        return approaches.stream().limit(12).toList();
    }

    int weaponSlot(Mob mob) {
        int reserve = ToolDurability.configuredReserve();
        boolean safeSweep = safeSwordSweep(mob);
        var inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        int best = -1;
        double bestTime = Double.POSITIVE_INFINITY;
        double bestDamage = 0;
        double closeRange = player.entityInteractionRange() + 1;
        boolean close = mob.distanceToSqr(player) <= closeRange * closeRange;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() && !Inventory.isHotbarSlot(slot)) continue;
            if (!matchesRequestedLoot(stack) || !usableWeapon(stack, reserve, safeSweep)) continue;
            var attributes = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
            double damage = attributes.compute(Attributes.ATTACK_DAMAGE, player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), EquipmentSlot.MAINHAND);
            double speed = attributes.compute(Attributes.ATTACK_SPEED, player.getAttributeBaseValue(Attributes.ATTACK_SPEED), EquipmentSlot.MAINHAND);
            if (damage <= 0 || speed <= 0) continue;
            double fullChargeTicks = 20.0 / speed;
            damage += weaponDamageBonus(stack, mob);
            double hits = Math.ceil((mob.getHealth() + mob.getAbsorptionAmount()) / damage);
            double time = (hits - 1) * Math.max(REPEAT_HIT_DELAY, fullChargeTicks);
            if (close) time += fullChargeTicks * (slot == selected ? 1 - player.getAttackStrengthScale(.5f) : 1);
            if (time < bestTime || time == bestTime && (damage > bestDamage || damage == bestDamage && slot == selected)) {
                best = slot;
                bestTime = time;
                bestDamage = damage;
            }
        }
        return best;
    }

    private static double weaponDamageBonus(ItemStack stack, Mob mob) {
        double bonus = 0;
        var enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var entry : enchantments.entrySet()) {
            var holder = entry.getKey();
            if (!holder.value().matchingSlot(EquipmentSlot.MAINHAND)) continue;
            boolean sensitive = holder.is(Enchantments.SMITE) && mob.typeHolder().is(EntityTypeTags.SENSITIVE_TO_SMITE)
                || holder.is(Enchantments.BANE_OF_ARTHROPODS) && mob.typeHolder().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)
                || holder.is(Enchantments.IMPALING) && mob.typeHolder().is(EntityTypeTags.SENSITIVE_TO_IMPALING);
            for (var effect : holder.value().getEffects(EnchantmentEffectComponents.DAMAGE)) {
                if (effect.effect() instanceof AddValue add && (effect.requirements().isEmpty() || sensitive)) {
                    bonus += add.value().calculate(entry.getIntValue());
                }
            }
        }
        return bonus;
    }

    private static boolean usableWeapon(ItemStack stack, int reserve, boolean safeSweep) {
        var weapon = stack.get(DataComponents.WEAPON);
        int wear = weapon == null ? 1 : weapon.itemDamagePerAttack();
        return (safeSweep || !stack.is(ItemTags.SWORDS)) && ToolDurability.pauseReason(stack, wear, reserve) == null;
    }

    boolean heldWeaponUsable(Mob target) {
        ItemStack held = player.getMainHandItem();
        boolean safeSweep = !held.is(ItemTags.SWORDS) || safeSwordSweep(target);
        return matchesRequestedLoot(held) && usableWeapon(held, ToolDurability.configuredReserve(), safeSweep);
    }

    static boolean cooksLoot(ItemStack weapon) {
        return weapon.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).keySet().stream()
            .anyMatch(enchantment -> enchantment.is(Enchantments.FIRE_ASPECT) || enchantment.is(SMELTS_LOOT));
    }

    static boolean hasCookingWeapon(LocalPlayer player) {
        int reserve = ToolDurability.configuredReserve();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (cooksLoot(stack) && usableWeapon(stack, reserve, true)) return true;
        }
        return false;
    }

    private boolean matchesRequestedLoot(ItemStack weapon) {
        return switch (huntLoot) {
            case ANY -> true;
            case RAW -> !cooksLoot(weapon);
            case COOKED -> cooksLoot(weapon);
            case SHEAR -> false;
        };
    }

    Vec3 aimPoint(Mob mob, Vec3 eye) {
        if (!eligible(mob)) return null;
        AABB box = mob.getBoundingBox();
        Vec3 center = box.getCenter();
        double reach = reach();
        double minimumReach = minimumReach();
        double nearestDistance = box.distanceToSqr(eye);
        if (reach <= 0 || nearestDistance < minimumReach * minimumReach || nearestDistance >= reach * reach) return null;
        for (double height : AIM_HEIGHTS) {
            Vec3 point = new Vec3(center.x, box.minY + box.getYsize() * height, center.z);
            Vec3 direction = point.subtract(eye);
            double distance = direction.length();
            Vec3 end = distance > reach ? eye.add(direction.scale(reach / distance)) : point;
            EntityHitResult hit = entityAt(eye, end);
            if (hit != null && hit.getEntity() == mob) return point;
        }
        return null;
    }

    boolean strike(Mob mob) {
        ItemStack held = player.getMainHandItem();
        if (captureResult != null || !eligible(mob) || player.isUsingItem() || player.cannotAttackWithItem(held, 0)
            || mob.hurtTime > 0 || mob.getUUID().equals(lastStrike) && player.tickCount - lastStrikeTick < REPEAT_HIT_DELAY
            || !charged(mob)) return false;
        if (targeted(mob) == null || !player.isWithinAttackRange(held, mob.getBoundingBox(), 0)) return false;
        if (!heldWeaponUsable(mob)) return false;
        mode.attack(player, mob);
        player.swing(InteractionHand.MAIN_HAND, held.getAttackAnimation(), false);
        lastStrike = mob.getUUID();
        lastStrikeTick = player.tickCount;
        return true;
    }

    private boolean charged(Mob mob) {
        float charge = player.getAttackStrengthScale(.5f);
        if (charge >= NORMAL_CHARGE) return true;
        if (charge < MIN_FINISHING_CHARGE || mob.getArmorValue() != 0) return false;
        double damage = player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (.2 + .8 * charge * charge);
        return damage > mob.getHealth() + mob.getAbsorptionAmount();
    }

    boolean capture(Mob mob) {
        ItemStack held = player.getMainHandItem();
        if (!eligible(mob) || player.isUsingItem()) return false;
        if (shearing() ? !usableShears(held)
            : captureResult == null || !held.is(captureContainer) || !canFillFrom(mob, held)) return false;
        EntityHitResult hit = targeted(mob);
        if (hit == null) return false;
        var interaction = mode.interact(player, mob, hit, InteractionHand.MAIN_HAND);
        if (interaction.consumesAction()) player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        return true;
    }

    private EntityHitResult targeted(Mob mob) {
        double reach = reach();
        if (reach <= 0) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(reach));
        EntityHitResult hit = entityAt(eye, end);
        return hit != null && hit.getEntity() == mob ? hit : null;
    }

    private double reach() {
        double range = captureResult == null && !shearing() ? player.getAttackRangeWith(player.getMainHandItem()).effectiveMaxRange(player)
            : player.entityInteractionRange();
        return range - REACH_MARGIN;
    }

    private double minimumReach() {
        if (captureResult != null || shearing()) return 0;
        var range = player.getAttackRangeWith(player.getMainHandItem());
        return Math.max(0, range.effectiveMinRange(player) - range.hitboxMargin());
    }

    private boolean safeSwordSweep(Mob target) {
        if (!player.onGround()) return true;
        AABB sweep = target.getBoundingBox().inflate(1, .25, 1);
        return level.getEntitiesOfClass(LivingEntity.class, sweep, entity -> entity != player && entity != target
            && entity.isAlive() && !entity.isSpectator()).isEmpty();
    }

    private EntityHitResult entityAt(Vec3 eye, Vec3 end) {
        return entityAt(level, player, eye, end);
    }

    static EntityHitResult interactionHit(ClientLevel level, LocalPlayer player, Mob mob, Vec3 eye) {
        if (!mob.isAlive() || mob.isRemoved() || !mob.isPickable()) return null;
        double reach = player.entityInteractionRange() - REACH_MARGIN;
        AABB box = mob.getBoundingBox();
        if (reach <= 0 || box.distanceToSqr(eye) >= reach * reach) return null;
        Vec3 center = box.getCenter();
        for (double height : AIM_HEIGHTS) {
            Vec3 point = new Vec3(center.x, box.minY + box.getYsize() * height, center.z);
            Vec3 offset = point.subtract(eye);
            Vec3 end = offset.lengthSqr() > reach * reach ? eye.add(offset.normalize().scale(reach)) : point;
            EntityHitResult hit = entityAt(level, player, eye, end);
            if (hit != null && hit.getEntity() == mob) return hit;
        }
        return null;
    }

    private static EntityHitResult entityAt(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 end) {
        var block = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 limit = block.getType() == HitResult.Type.BLOCK ? block.getLocation() : end;
        return ProjectileUtil.getEntityHitResult(player, eye, limit, new AABB(eye, limit).inflate(1),
            entity -> entity.isPickable() && !entity.isSpectator(), eye.distanceToSqr(limit));
    }
}
