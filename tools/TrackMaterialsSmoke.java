import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.CreatureStatus;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.items.TradingWindow;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.wurmrail.RailwayTrackMaterials;
import org.wurmrail.WurmRailWorld;

/** Real item/inventory/payment checks; called after native discovery and synthetic terrain setup. */
public final class TrackMaterialsSmoke {
    private static int assertions;

    public static void run() throws Exception {
        templateAndRecipe();
        payments();
        inaccessibleInventory();
        groundRecovery();
        System.out.println("PASS crafted railway sections, native inventory cost, payment rollback and ground recovery ("
                + assertions + " assertions)");
    }

    private static void templateAndRecipe() throws Exception {
        ItemTemplate section = ItemTemplateFactory.getInstance().getTemplate(WurmRailWorld.trackSectionTemplateId);
        Item item = ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId, 63f, "Materials fixture");
        require(!section.isNoMove && !item.isNoTake() && item.getWeightGrams() == 20000,
                "sections are carryable twenty kilogram building materials");
        require("model.wurmrail.track.17.".equals(item.getModelName()),
                "portable section keeps exact known model without material suffix");
        com.wurmonline.server.items.AdvancedCreationEntry recipe = com.wurmonline.server.items.CreationMatrix
                .getInstance().getAdvancedCreationEntry(WurmRailWorld.trackSectionTemplateId);
        require(recipe != null && recipe.getRequirements().length == 3, "complete advanced track-section recipe");
        require(org.gotti.wurmunlimited.modsupport.IdFactory.getExistingIdFor("wurmrail.tracksection.v1",
                org.gotti.wurmunlimited.modsupport.IdType.ITEMTEMPLATE) == WurmRailWorld.trackSectionTemplateId,
                "portable section uses persistent new identity");
        Items.destroyItem(item.getWurmId());
    }

    private static void payments() throws Exception {
        InventoryActor actor = actor(710000032L, true, 0);
        Item first = section(actor, 37f), second = section(actor, 68f);
        require(RailwayTrackMaterials.count(actor) == 2, "own accessible inventory is counted");
        expectFailure(() -> RailwayTrackMaterials.reserve(actor, 3), "insufficient cost rejected before mutation");
        require(exists(first) && exists(second) && RailwayTrackMaterials.count(actor) == 2,
                "failed reservation preserves every existing item");
        RailwayTrackMaterials.Payment payment = RailwayTrackMaterials.reserve(actor, 2);
        require(exists(first) && exists(second), "reservation does not consume sections early");
        float q0 = payment.qualityAt(0), q1 = payment.qualityAt(1);
        require((q0 == 37 && q1 == 68) || (q0 == 68 && q1 == 37), "placed track can preserve each section's quality");
        payment.consume();
        require(!exists(first) && !exists(second) && RailwayTrackMaterials.count(actor) == 0,
                "successful construction consumes exactly the reserved units");
        expectFailure(() -> payment.consume(), "payment cannot be replayed");
        payment.refund();
        require(RailwayTrackMaterials.count(actor) == 2, "rollback restores consumed sections");
        payment.refund();
        require(RailwayTrackMaterials.count(actor) == 2, "repeated refund cannot duplicate building materials");
        List<Float> restoredQuality = new ArrayList<Float>();
        for (Item item : actor.inventory.getItemsAsArray()) if (item.getTemplateId() == WurmRailWorld.trackSectionTemplateId)
            restoredQuality.add(item.getQualityLevel());
        require(restoredQuality.contains(37f) && restoredQuality.contains(68f), "rollback preserves original section quality");

        RailwayTrackMaterials.Payment moved = RailwayTrackMaterials.reserve(actor, 2);
        Item removed = actor.inventory.getItemsAsArray()[0];
        actor.inventory.dropItem(removed.getWurmId(), false);
        expectFailure(() -> moved.consume(), "moving a reserved unit rejects the whole payment");
        require(RailwayTrackMaterials.count(actor) == 1 && exists(removed), "pre-consumption revalidation never takes a partial cost");
        moved.refund();
        require(RailwayTrackMaterials.count(actor) == 1, "unconsumed reservation refunds nothing");
        Items.destroyItem(removed.getWurmId());

        InventoryActor gm = actor(710001032L, true, 2);
        RailwayTrackMaterials.Payment free = RailwayTrackMaterials.reserve(gm, 100);
        require(free.qualityAt(99) == 50f, "GM building has explicit default quality");
        free.consume(); free.refund();
        require(RailwayTrackMaterials.count(gm) == 0, "GM free placement and rollback create no materials");
    }

    private static void inaccessibleInventory() throws Exception {
        InventoryActor actor = actor(710002032L, true, 0);
        Item usable = section(actor, 50f), mailed = section(actor, 50f), banked = section(actor, 50f),
                traded = section(actor, 50f), foreign = section(actor, 50f);
        mailed.setMailed(true);
        banked.setBanked(true);
        traded.setTradeWindow((TradingWindow)allocate(TradingWindow.class));
        foreign.setOwnerId(actor.getWurmId() + 256);
        require(RailwayTrackMaterials.count(actor) == 1, "mailed, banked, traded and foreign inventory units are excluded");
        require(exists(usable) && exists(mailed) && exists(banked) && exists(traded) && exists(foreign),
                "inventory scanning changes no transaction or ownership state");
        traded.setTradeWindow(null); mailed.setMailed(false); banked.setBanked(false); foreign.setOwnerId(actor.getWurmId());

        Item pack = ItemFactory.createItem(ItemList.backPack, 50f, "Materials fixture");
        require(actor.inventory.insertItem(pack, true), "nested container inserted");
        Item nested = ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId, 50f, "Materials fixture");
        require(pack.insertItem(nested, true), "track section inserted into nested own container");
        require(RailwayTrackMaterials.count(actor) == 6, "accessible nested own sections are counted");
        pack.setBanked(true);
        require(RailwayTrackMaterials.count(actor) == 5, "banked ancestor hides nested section");
        pack.setBanked(false); pack.setMailed(true);
        require(RailwayTrackMaterials.count(actor) == 5, "mailed ancestor hides nested section");
        pack.setMailed(false); pack.setTradeWindow((TradingWindow)allocate(TradingWindow.class));
        require(RailwayTrackMaterials.count(actor) == 5, "traded ancestor hides nested section");
        pack.setTradeWindow(null);
    }

    private static void groundRecovery() throws Exception {
        InventoryActor actor = actor(710003032L, false, 0);
        Item recovered = RailwayTrackMaterials.recover(actor, 72f);
        require(recovered.getParentId() == -10L && recovered.getOwnerId() == -10L,
                "overburdened builder receives recovered section on the ground");
        require(recovered.isOnSurface() && recovered.getTileX() == actor.getTileX()
                        && recovered.getTileY() == actor.getTileY(), "fallback drop uses the player's exact surface tile");
        require(recovered.getLastOwnerId() == actor.getWurmId() && recovered.getQualityLevel() == 72f,
                "recovery retains builder ownership history and marker quality");
        Items.destroyItem(recovered.getWurmId());
    }

    private static Item section(InventoryActor actor, float quality) throws Exception {
        Item item = ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId, quality, "Materials fixture");
        require(actor.inventory.insertItem(item, true), "native section inventory insertion");
        return item;
    }

    public static InventoryActor actor(long id, boolean carry, int power) throws Exception {
        id = (id & ~255L) | 1L; // Native Server.getCreature resolves this isolated fixture as a creature.
        Creature base = UsabilitySmoke.actor(id, 40 * 4 + 2, 120 * 4 + 2);
        InventoryActor actor = (InventoryActor)allocate(InventoryActor.class);
        Field identity = Creature.class.getDeclaredField("id"); identity.setAccessible(true); identity.setLong(actor, id);
        Field status = Creature.class.getDeclaredField("status"); status.setAccessible(true); status.set(actor, status.get(base));
        Field holder = CreatureStatus.class.getDeclaredField("statusHolder"); holder.setAccessible(true); holder.set(actor.getStatus(), actor);
        actor.inventory = ItemFactory.createInventory(id, (short)0, 50f);
        actor.inventory.setOwnerId(id);
        actor.carry = carry; actor.power = power; actor.vehicle = -10L;
        actor.fixtureCommunicator = new com.wurmonline.server.creatures.CreatureCommunicator(actor);
        // Avoid constructing Server (which launches Steam networking) while keeping
        // native item ownership lookups intact inside this isolated process.
        Field serverInstance = com.wurmonline.server.Server.class.getDeclaredField("instance");
        serverInstance.setAccessible(true);
        if (serverInstance.get(null) == null) serverInstance.set(null, allocate(com.wurmonline.server.Server.class));
        Field creatures = com.wurmonline.server.creatures.Creatures.class.getDeclaredField("creatures");
        creatures.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.Map<Long,Creature> creatureMap = (java.util.Map<Long,Creature>)creatures
                .get(com.wurmonline.server.creatures.Creatures.getInstance());
        creatureMap.put(id, actor);
        return actor;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object unsafe = field.get(null);
        return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    public static final class InventoryActor extends Creature {
        public Item inventory;
        public boolean failDelivery;
        private com.wurmonline.server.creatures.Communicator fixtureCommunicator;
        boolean carry;
        int power;
        private InventoryActor() throws Exception { super(); }
        @Override public Item getInventory() { return inventory; }
        @Override public boolean canCarry(int weight) {
            if(failDelivery) throw new IllegalStateException("Forced native material delivery failure");
            return carry;
        }
        @Override public int getPower() { return power; }
        @Override public String getName() { return "Rail materials fixture"; }
        @Override public com.wurmonline.server.creatures.Communicator getCommunicator() { return fixtureCommunicator; }
        @Override public boolean isAnimal() { return false; }
        @Override public void addCarriedWeight(int weight) { }
        @Override public boolean removeCarriedWeight(int weight) { return true; }
    }

    private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        require(rejected, message);
    }
    private static boolean exists(Item item) { return Items.getItemOptional(item.getWurmId()).orElse(null) == item; }
    private static void require(boolean success, String message) {
        assertions++;
        if (!success) throw new AssertionError(message);
    }
}
