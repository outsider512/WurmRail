package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.items.RuneUtilities;
import java.util.Set;

/** Central timber-only whitelist, shared by player insertion, forestry and freight. */
public final class LogBunkPolicy {
    public static final long MAX_WEIGHT_GRAMS=100000000L;
    public static final int MAX_VOLUME_CC=64000000,MAX_ITEMS=4096;
    private LogBunkPolicy() { }
    public static boolean isBunk(Item item){return item!=null&&LogBunkRegistry.isBunk(item.getTemplateId());}
    public static boolean allows(int template){return RawTimberCargo.allows(template);}
    public static Set<Integer> allowedTemplateIds(){return RawTimberCargo.allowedTemplateIds();}
    public static boolean eligible(Item item){return RawTimberCargo.eligible(item);}
    public static boolean canInsert(Item bunk,Item cargo){return insertionProblem(bunk,cargo)==null;}
    public static String insertionProblem(Item bunk,Item cargo) {
        if(!isBunk(bunk))return "Choose a Log Bunk.";
        if(!eligible(cargo))return "Log Bunks accept only felled trees and logs.";
        if(bunk==cargo||bunk.getItems().contains(cargo))return "That timber is already in this Log Bunk.";
        if(bunk.getItems().size()>=MAX_ITEMS)return "The Log Bunk's item limit has been reached.";
        long grams=cargo.getWeightGrams(),volume=cargo.getVolume();
        for(Item existing:bunk.getItems()) {
            if(existing.getParentId()!=bunk.getWurmId()||!eligible(existing))return "The Log Bunk contains invalid timber links; inspect it before loading.";
            grams+=existing.getWeightGrams();volume+=existing.getVolume();
        }
        if(grams>MAX_WEIGHT_GRAMS)return "The Log Bunk's weight capacity has been reached.";
        if(volume>MAX_VOLUME_CC)return "The Log Bunk's volume capacity has been reached.";
        return null;
    }
    public static long storedWeightGrams(Item bunk){long grams=0;for(Item item:bunk.getItems())grams+=Math.max(0,item.getFullWeight());return grams;}
    public static long remainingWeightGrams(Item bunk){return Math.max(0,MAX_WEIGHT_GRAMS-storedWeightGrams(bunk));}
    public static long storedVolume(Item bunk){long volume=0;for(Item item:bunk.getItems())volume+=Math.max(0,item.getVolume());return volume;}
    public static long remainingVolume(Item bunk){return Math.max(0,(long)MAX_VOLUME_CC-storedVolume(bunk));}
    public static boolean hasItemSlot(Item bunk){return isBunk(bunk)&&bunk.getItems().size()<MAX_ITEMS;}
    /** Timber templates are not native combineable items, so a weight edit does not enlarge their dimensions. */
    public static boolean canChangeWeight(Item item,int requested) {
        if(item==null||item.getParentId()<=0)return true;
        Item parent=Items.getItemOptional(item.getParentId()).orElse(null);
        if(!isBunk(parent))return true;
        if(requested<=item.getWeightGrams(false))return requested>=0;
        if(!eligible(item)||!parent.getItems().contains(item))return false;
        long weight=item.getSpellEffects()==null?requested:(int)((float)requested*item.getSpellEffects().getRuneEffect(RuneUtilities.ModifierEffect.ENCH_WEIGHT));
        for(Item other:parent.getItems())if(other!=item) {
            if(other.getParentId()!=parent.getWurmId()||!eligible(other))return false;
            weight+=other.getWeightGrams();
        }
        return weight<=MAX_WEIGHT_GRAMS&&storedVolume(parent)<=MAX_VOLUME_CC;
    }
    /** Native logs have fixed dimensions, but deliberate resizing must also respect volume. */
    public static boolean canResize(Item item,int x,int y,int z) {
        if(item==null||item.getParentId()<=0)return true;
        Item parent=Items.getItemOptional(item.getParentId()).orElse(null);
        if(!isBunk(parent))return true;
        if(x<1||y<1||z<1)return false;
        double modifier=item.getSpellEffects()==null?1:item.getSpellEffects().getRuneEffect(RuneUtilities.ModifierEffect.ENCH_VOLUME);
        double prospective=(double)x*y*z*modifier;
        if(prospective<=item.getVolume())return true;
        return eligible(item)&&parent.getItems().contains(item)&&prospective<=MAX_VOLUME_CC
                &&storedVolume(parent)-item.getVolume()+prospective<=MAX_VOLUME_CC;
    }
    public static boolean canChangeTemplate(Item item,int template) {
        if(item==null||item.getParentId()<=0)return true;
        Item parent=Items.getItemOptional(item.getParentId()).orElse(null);
        if(!isBunk(parent)||template==item.getTemplateId())return true;
        if(!allows(template))return false;
        try {
            long prospective=Math.max(item.getVolume(),ItemTemplateFactory.getInstance().getTemplate(template).getVolume());
            return storedVolume(parent)-item.getVolume()+prospective<=MAX_VOLUME_CC;
        } catch(com.wurmonline.server.items.NoSuchTemplateException missing){return false;}
    }
}
