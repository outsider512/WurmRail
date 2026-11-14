package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Inventory-only track payments. Construction and payment run together on the server poll. */
public final class RailwayTrackMaterials {
    private static final Field BANKED_FLAG = bankedFlag();
    private RailwayTrackMaterials() { }

    public static int count(Creature actor) {
        return available(actor).size();
    }

    /** Resolve the complete cost before creating any track; no items are changed yet. */
    public static Payment reserve(Creature actor, int count) {
        if (actor == null) throw new IllegalArgumentException("A railway builder is required.");
        if (count < 1 || count > 100) throw new IllegalArgumentException("Build between 1 and 100 track tiles at a time.");
        if (actor.getPower() >= 2) return new Payment(actor, Collections.<Unit>emptyList(), count);
        List<Item> candidates = available(actor);
        if (candidates.size() < count)
            throw new IllegalArgumentException("Carry " + count + " crafted rail track section"
                    + (count == 1 ? "" : "s") + " to build this railway. Available: " + candidates.size() + ".");
        List<Unit> units = new ArrayList<Unit>(count);
        for (int index = 0; index < count; index++) units.add(new Unit(candidates.get(index)));
        return new Payment(actor, units, count);
    }

    public static final class Payment {
        private final Creature actor;
        private final List<Unit> units;
        private final int count;
        private final List<Unit> consumed = new ArrayList<Unit>();
        private boolean attempted, refunded;

        private Payment(Creature actor, List<Unit> units, int count) {
            this.actor = actor;
            this.units = units;
            this.count = count;
        }

        public float qualityAt(int index) {
            if (index < 0 || index >= count) throw new IllegalArgumentException("Invalid reserved track section index.");
            return units.isEmpty() ? 50.0f : units.get(index).quality;
        }

        /** Call after every requested tile was built successfully; recheck every unit before destroying any. */
        public synchronized void consume() {
            if (attempted || refunded) throw new IllegalStateException("This railway payment has already been used.");
            attempted = true;
            for (Unit unit : units) {
                if (unit.item.getTemplateId() != WurmRailWorld.trackSectionTemplateId || !usable(actor, unit.item))
                    throw new IllegalArgumentException("The reserved track sections moved or became unavailable. No payment was taken.");
            }
            for (Unit unit : units) {
                try {
                    Items.destroyItem(unit.item.getWurmId());
                } finally {
                    // A native failure after deletion must still be refundable.
                    if (!Items.getItemOptional(unit.item.getWurmId()).isPresent()) consumed.add(unit);
                }
                if (Items.getItemOptional(unit.item.getWurmId()).isPresent())
                    throw new IllegalStateException("Unable to consume a railway track section; construction was cancelled.");
            }
        }

        /** Rollback only: restore each unit actually destroyed, at most once even after a partial refund. */
        public synchronized void refund() throws Exception {
            if (refunded) return;
            while (!consumed.isEmpty()) {
                Unit unit = consumed.get(0);
                Item restored = create(actor, unit.quality, unit.material, unit.rarity, unit.creator);
                try {
                    restored.setName(unit.name);
                    restored.setDescription(unit.description);
                    restored.setDamage(unit.damage);
                    deliver(actor, restored);
                } catch (Exception failed) {
                    // Preserve the original refund entry if delivery could not be completed.
                    if (Items.getItemOptional(restored.getWurmId()).isPresent()) Items.destroyItem(restored.getWurmId());
                    throw failed;
                }
                consumed.remove(0);
            }
            refunded = true;
        }
    }

    /** Recover one section, using normal carry/insert limits and the native player-ground drop fallback. */
    public static Item recover(Creature actor, float quality) throws Exception {
        Item recovered = create(actor, quality, (byte)0, (byte)0, actor.getName());
        try {
            deliver(actor, recovered);
            return recovered;
        } catch (Exception failure) {
            // Do not leave an unreachable newly created item when the caller restores the old track.
            if (Items.getItemOptional(recovered.getWurmId()).isPresent()) Items.destroyItem(recovered.getWurmId());
            throw failure;
        }
    }

    private static Item create(Creature actor, float quality, byte material, byte rarity, String creator) throws Exception {
        if (actor == null) throw new IllegalArgumentException("A railway builder is required.");
        if (WurmRailWorld.trackSectionTemplateId == 0) throw new IllegalStateException("Railway track sections are unavailable.");
        float ql = Float.isFinite(quality) ? Math.max(1.0f, Math.min(100.0f, quality)) : 50.0f;
        Item item = ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId, ql, material, rarity, creator);
        item.setLastOwnerId(actor.getWurmId());
        return item;
    }

    private static void deliver(Creature actor, Item item) throws Exception {
        Item inventory = actor.getInventory();
        if (inventory != null && actor.canCarry(item.getFullWeight()) && inventory.testInsertItem(item)
                && inventory.insertItem(item, false, true)) return;
        // Distance zero preserves the player's tile, bridge, floor and cave layer.
        item.putItemInfrontof(actor, 0.0f);
        item.savePosition();
    }

    private static List<Item> available(Creature actor) {
        List<Item> result = new ArrayList<Item>();
        if (actor == null || WurmRailWorld.trackSectionTemplateId == 0 || actor.getInventory() == null) return result;
        List<Item> pending = new ArrayList<Item>();
        Collections.addAll(pending, actor.getInventory().getItemsAsArray());
        Set<Long> seen = new HashSet<Long>();
        for (int index = 0; index < pending.size(); index++) {
            Item item = pending.get(index);
            if (!seen.add(item.getWurmId()) || !usable(actor, item)) continue;
            if (item.getTemplateId() == WurmRailWorld.trackSectionTemplateId) result.add(item);
            Collections.addAll(pending, item.getItemsAsArray());
        }
        return result;
    }

    /** Checks the whole container chain, including locks and transaction state on ancestor containers. */
    private static boolean usable(Creature actor, Item item) {
        if (item == null || Items.getItemOptional(item.getWurmId()).orElse(null) != item) return false;
        Item inventory = actor.getInventory();
        if (inventory == null) return false;
        Set<Long> seen = new HashSet<Long>();
        Item current = item;
        while (current != null && seen.add(current.getWurmId())) {
            if (current.getOwnerId() != actor.getWurmId() || current.isTraded()
                    || current.isMailed() || banked(current)) return false;
            if (current.isLocked() && !current.mayAccessHold(actor)) return false;
            if (current == inventory) return true;
            current = current.getParentOrNull();
        }
        return false;
    }

    private static Field bankedFlag() {
        try {
            Field field = Item.class.getDeclaredField("banked");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to read railway material bank state safely.", failure);
        }
    }

    private static boolean banked(Item item) {
        try {
            // Item.isBanked repairs inconsistent flags; inventory previews must not do that.
            return BANKED_FLAG.getBoolean(item);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read railway material bank state safely.", failure);
        }
    }

    private static final class Unit {
        final Item item;
        final float quality, damage;
        final byte material, rarity;
        final String name, description, creator;

        Unit(Item item) {
            this.item = item;
            quality = item.getQualityLevel();
            damage = item.getDamage();
            material = item.getMaterial();
            rarity = item.getRarity();
            name = item.getName();
            description = item.getDescription();
            creator = item.getCreatorName();
        }
    }
}
