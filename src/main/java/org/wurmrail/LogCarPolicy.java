package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.items.RuneUtilities;
import java.util.Set;
import java.util.Locale;

/** Timber-only rolling-stock capacity; raw cargo classification is shared with Log Bunks. */
public final class LogCarPolicy {
    public static final long MAX_WEIGHT_GRAMS=20000000L;
    public static final int MAX_VOLUME_CC=24000000,MAX_ITEMS=4096;
    private LogCarPolicy() { }
    public static boolean isCar(Item item){return item!=null&&LogCarRegistry.isLogCar(item.getTemplateId());}
    public static boolean isLogCar(Item item){return isCar(item);}
    public static boolean allows(int template){return RawTimberCargo.allows(template);}
    public static Set<Integer> allowedTemplateIds(){return RawTimberCargo.allowedTemplateIds();}
    public static boolean eligible(Item item){return RawTimberCargo.eligible(item);}
    public static String summary(Item car) {
        if(!isCar(car))return "Choose a Log Car.";
        return String.format(Locale.ROOT,"Log Car timber: %,.1f / %,.1f kg; %,.1f / %,.1f litres; %,d stacks. Logs and felled trees only.",
                storedWeightGrams(car)/1000.0,MAX_WEIGHT_GRAMS/1000.0,storedVolume(car)/1000.0,MAX_VOLUME_CC/1000.0,car.getItems().size());
    }
    public static boolean canInsert(Item car,Item cargo){return insertionProblem(car,cargo)==null;}
    public static String insertionProblem(Item car,Item cargo) {
        if(!isCar(car))return "Choose a Log Car.";
        if(!eligible(cargo))return "Log Cars accept only felled trees and logs.";
        if(car==cargo||car.getItems().contains(cargo))return "That timber is already in this Log Car.";
        if(car.getItems().size()>=MAX_ITEMS)return "The Log Car's item limit has been reached.";
        long grams=cargo.getWeightGrams(),volume=cargo.getVolume();
        for(Item existing:car.getItems()) {
            if(existing.getParentId()!=car.getWurmId()||!eligible(existing))return "The Log Car contains invalid timber links; inspect it before loading.";
            grams+=existing.getWeightGrams();volume+=existing.getVolume();
        }
        if(grams>MAX_WEIGHT_GRAMS)return "The Log Car's weight capacity has been reached.";
        if(volume>MAX_VOLUME_CC)return "The Log Car's volume capacity has been reached.";
        return null;
    }
    public static long storedWeightGrams(Item car){long grams=0;for(Item item:car.getItems())grams+=Math.max(0,item.getFullWeight());return grams;}
    public static long remainingWeightGrams(Item car){return Math.max(0,MAX_WEIGHT_GRAMS-storedWeightGrams(car));}
    public static long storedVolume(Item car){long volume=0;for(Item item:car.getItems())volume+=Math.max(0,item.getVolume());return volume;}
    public static long remainingVolume(Item car){return Math.max(0,(long)MAX_VOLUME_CC-storedVolume(car));}
    public static boolean hasItemSlot(Item car){return isCar(car)&&car.getItems().size()<MAX_ITEMS;}
    /** Timber templates are not native combineable items, so a weight edit does not enlarge their dimensions. */
    public static boolean canChangeWeight(Item item,int requested) {
        if(item==null||item.getParentId()<=0)return true;
        Item parent=Items.getItemOptional(item.getParentId()).orElse(null);
        if(!isCar(parent))return true;
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
        if(!isCar(parent))return true;
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
        if(!isCar(parent)||template==item.getTemplateId())return true;
        if(!allows(template))return false;
        try {
            long prospective=Math.max(item.getVolume(),ItemTemplateFactory.getInstance().getTemplate(template).getVolume());
            return storedVolume(parent)-item.getVolume()+prospective<=MAX_VOLUME_CC;
        } catch(com.wurmonline.server.items.NoSuchTemplateException missing){return false;}
    }
}
