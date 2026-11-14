package org.wurmrail;

import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;

/** Exact real fuel families; arbitrary wooden tools, crates and crafted goods are excluded. */
public final class SteamFuelPolicy {
    private SteamFuelPolicy(){ }
    public static boolean wood(int template){return template==ItemList.log||template==ItemList.logHuge||template==ItemList.kindling||template==ItemList.scrapwood;}
    public static boolean allows(int template){return wood(template)||template==ItemList.charcoal||CoalItemRegistry.isCoal(template);}
    public static double energyPerKg(Item item,SteamSupplyConfig config){return CoalItemRegistry.isCoal(item.getTemplateId())?config.coalEnergyPerKg:item.getTemplateId()==ItemList.charcoal?config.charcoalEnergyPerKg:config.woodEnergyPerKg;}
    public static long microPerGram(Item item,SteamSupplyConfig config){return Math.max(1,(long)Math.floor(energyPerKg(item,config)*1000+0.0000001));}
    public static boolean eligible(Item item){return item!=null&&allows(item.getTemplateId())&&item.getAuxData()!=99&&item.getWeightGrams()>0&&!item.isHollow()&&item.getItems().isEmpty()&&!item.isLiquid()&&!item.isBulkItem()&&!item.isInventory()&&!item.isInventoryGroup()&&!item.isBodyPart()&&!item.isVehicle()&&!item.isArtifact()&&!item.isNoTake()&&!item.isNoDrop()&&!item.isNoTrade()&&!item.isPlanted()&&!item.isBanked()&&!item.isMailed()&&!item.isTraded()&&!item.isLocked()&&!item.isSealedByPlayer();}
}
