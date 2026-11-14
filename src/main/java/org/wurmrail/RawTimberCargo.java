package org.wurmrail;

import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shared raw round-timber classification; storage and rolling stock own their capacity rules. */
public final class RawTimberCargo {
    private static final Set<Integer> ALLOWED=Collections.unmodifiableSet(new LinkedHashSet<Integer>(Arrays.asList(ItemList.log,ItemList.logHuge)));
    private RawTimberCargo() { }
    public static boolean allows(int template){return ALLOWED.contains(template);}
    public static Set<Integer> allowedTemplateIds(){return ALLOWED;}
    public static boolean eligible(Item item) {
        return item!=null&&allows(item.getTemplateId())&&item.getAuxData()!=99&&item.getWeightGrams()>0&&item.getVolume()>0
                &&!item.isHollow()&&item.getItems().isEmpty()&&!item.isLiquid()&&!item.isBulkItem()
                &&!item.isInventory()&&!item.isBodyPart()&&!item.isVehicle()&&!item.isArtifact()
                &&!item.isNoTake()&&!item.isNoDrop()&&!item.isNoTrade()&&!item.isPlanted()
                &&!item.isBanked()&&!item.isMailed()&&!item.isTraded()&&!item.isLocked()&&!item.isSealedByPlayer();
    }
}
