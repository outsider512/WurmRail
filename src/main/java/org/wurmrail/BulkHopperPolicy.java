package org.wurmrail;

import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Exact loose-resource whitelist and real inventory limits shared by native and freight loading. */
public final class BulkHopperPolicy {
    public static final long MAX_WEIGHT_GRAMS=100000000L;
    public static final int MAX_VOLUME_CC=64000000;
    public static final int MAX_ITEMS=4096;
    // Expand industrial resources here, using explicit native template identities.
    // Never infer approval from an item's name, material, or general combine flag.
    private static final Set<Integer> ALLOWED;
    static {
        LinkedHashSet<Integer> ids=new LinkedHashSet<Integer>();
        Collections.addAll(ids,
                ItemList.ironOre,ItemList.copperOre,ItemList.tinOre,ItemList.zincOre,
                ItemList.leadOre,ItemList.silverOre,ItemList.goldOre,
                ItemList.adamantineOre,ItemList.glimmerSteelOre,
                ItemList.charcoal,ItemList.rock,ItemList.slateShard,ItemList.marbleShard,
                ItemList.sandstone,ItemList.dirtPile,ItemList.sand,ItemList.clay,
                ItemList.peat,ItemList.flint,ItemList.ash,ItemList.rockSalt);
        ALLOWED=Collections.unmodifiableSet(ids);
    }
    private BulkHopperPolicy() { }
    public static boolean isHopper(Item item) { return item!=null&&BulkHopperRegistry.isHopper(item.getTemplateId()); }
    public static boolean allows(int templateId) { return ALLOWED.contains(templateId)||CoalItemRegistry.isCoal(templateId); }
    public static Set<Integer> allowedTemplateIds() {
        int coal=CoalItemRegistry.templateId();
        if(coal<=0)return ALLOWED;
        LinkedHashSet<Integer> supported=new LinkedHashSet<Integer>(ALLOWED);supported.add(coal);
        return Collections.unmodifiableSet(supported);
    }

    /** Cargo remains its original native item, including quality, damage and enchantments. */
    public static boolean eligible(Item item) {
        return item!=null&&allows(item.getTemplateId())&&item.getWeightGrams()>0&&item.getVolume()>0
                &&!item.isHollow()&&item.getItems().isEmpty()&&!item.isLiquid()&&!item.isBulkItem()
                &&!item.isInventory()&&!item.isBodyPart()&&!item.isVehicle()&&!item.isArtifact()
                &&!item.isNoTake()&&!item.isNoDrop()&&!item.isNoTrade()&&!item.isPlanted()
                &&!item.isBanked()&&!item.isMailed()&&!item.isTraded()&&!item.isLocked()&&!item.isSealedByPlayer();
    }
    /** Native reads and the final insertion both ask the same policy before any parent is changed. */
    public static boolean canInsert(Item hopper,Item cargo) { return insertionProblem(hopper,cargo)==null; }
    public static String insertionProblem(Item hopper,Item cargo) {
        if(!isHopper(hopper))return "Choose a Bulk Hopper.";
        if(!eligible(cargo))return "Bulk Hoppers accept only approved loose raw resources.";
        if(hopper==cargo||hopper.getItems().contains(cargo))return "That item is already in this hopper.";
        if(hopper.getItems().size()>=MAX_ITEMS)return "The Bulk Hopper's item limit has been reached.";
        long weight=cargo.getWeightGrams(),volume=cargo.getVolume();
        for(Item existing:hopper.getItems()) {
            if(existing.getParentId()!=hopper.getWurmId()||!eligible(existing))
                return "The hopper contains an invalid resource; unload it before adding more.";
            weight+=existing.getWeightGrams();volume+=existing.getVolume();
            if(weight>MAX_WEIGHT_GRAMS||volume>MAX_VOLUME_CC)break;
        }
        if(weight>MAX_WEIGHT_GRAMS)return "The Bulk Hopper's weight capacity has been reached.";
        if(volume>MAX_VOLUME_CC)return "The Bulk Hopper's volume capacity has been reached.";
        return null;
    }
    public static long storedWeightGrams(Item hopper) {
        long total=0;for(Item item:hopper.getItems())total+=Math.max(0,item.getFullWeight());return total;
    }
    public static long remainingWeightGrams(Item hopper) { return Math.max(0,MAX_WEIGHT_GRAMS-storedWeightGrams(hopper)); }
    public static long storedVolume(Item hopper) {
        long total=0;for(Item item:hopper.getItems())total+=Math.max(0,item.getVolume());return total;
    }
    public static long remainingVolume(Item hopper) { return Math.max(0,(long)MAX_VOLUME_CC-storedVolume(hopper)); }
    public static boolean hasItemSlot(Item hopper) { return isHopper(hopper)&&hopper.getItems().size()<MAX_ITEMS; }
}
