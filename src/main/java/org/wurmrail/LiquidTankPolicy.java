package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Native liquid items, one liquid identity at a time. No virtual commodity amounts. */
public final class LiquidTankPolicy {
    public static final int MAX_VOLUME_CC=5000000,MAX_WEIGHT_GRAMS=5000000,MAX_ITEMS=1;
    private static final Set<Integer> SUPPORTED;
    static {
        LinkedHashSet<Integer> liquids=new LinkedHashSet<Integer>();
        liquids.add(ItemList.water);
        // Add future supported native liquid templates here; never infer them from names.
        SUPPORTED=Collections.unmodifiableSet(liquids);
    }
    private LiquidTankPolicy() { }
    public static Set<Integer> supportedTemplateIds() { return SUPPORTED; }
    public static boolean isTankCar(Item item) { return item!=null&&TankCarRegistry.isTankCar(item.getTemplateId()); }
    public static boolean isIndustrialTank(Item item) { return item!=null&&IndustrialWaterTankRegistry.isIndustrialTank(item.getTemplateId()); }
    public static boolean isLiquidTank(Item item) { return isTankCar(item)||isIndustrialTank(item); }
    public static int capacityMillilitres(Item item) { return isIndustrialTank(item)?IndustrialWaterTankRegistry.MAX_VOLUME_CC:MAX_VOLUME_CC; }
    public static int maxWeightGrams(Item item) { return isIndustrialTank(item)?IndustrialWaterTankRegistry.MAX_WEIGHT_GRAMS:MAX_WEIGHT_GRAMS; }
    public static boolean supports(int templateId) { return SUPPORTED.contains(templateId); }
    public static boolean supports(Item tank,int templateId) {
        return isIndustrialTank(tank)?templateId==ItemList.water:supports(templateId);
    }
    public static boolean eligible(Item liquid) {
        return liquid!=null&&liquid.isLiquid()&&supports(liquid.getTemplateId())&&liquid.getWeightGrams()>0
                &&!liquid.isHollow()&&liquid.getItems().isEmpty()&&!liquid.isBulkItem()&&!liquid.isInventory()
                &&!liquid.isBodyPart()&&!liquid.isVehicle()&&!liquid.isArtifact()&&!liquid.isNoTake()
                &&!liquid.isBanked()&&!liquid.isMailed()&&!liquid.isTraded()&&!liquid.isLocked();
    }
    public static String contentsProblem(Item tank) {
        String problem=sourceContentsProblem(tank);
        if(problem!=null)return problem;
        if(storedMillilitres(tank)>maxWeightGrams(tank))return "The liquid tank is over capacity. Unload its existing water before filling.";
        return null;
    }
    /** Old Tank Cars retain all water after a capacity reduction and may still unload it. */
    public static String sourceContentsProblem(Item tank) {
        if(!isLiquidTank(tank))return "Choose a WurmRail liquid tank.";
        if(LiquidTankTransfers.isFaulted(tank))return "This liquid tank is stopped for recovery. Empty and inspect it before clearing its recovery stop.";
        if(tank.getItems().size()>MAX_ITEMS)return "The Tank Car contains more than one liquid stack; inspect it before filling.";
        for(Item liquid:tank.getItems()) {
            if(liquid.getParentId()!=tank.getWurmId()||!eligible(liquid)||!supports(tank,liquid.getTemplateId()))
                return "The Tank Car contains unsupported cargo or invalid liquid links. Empty it before filling.";
        }
        return null;
    }
    public static Item contents(Item tank) { return tank.getItems().isEmpty()?null:tank.getItems().iterator().next(); }
    public static long storedMillilitres(Item tank) {
        long total=0;for(Item liquid:tank.getItems())total+=Math.max(0,liquid.getWeightGrams());return total;
    }
    public static long remainingMillilitres(Item tank) { return Math.max(0,capacityMillilitres(tank)-storedMillilitres(tank)); }
    public static boolean canInsert(Item tank,Item cargo) { return insertionProblem(tank,cargo)==null; }
    /** Native player pours may split an incoming stack or merge into the existing liquid. */
    public static boolean canPour(Item tank,Item cargo) {
        if(!isLiquidTank(tank)||!eligible(cargo)||!supports(tank,cargo.getTemplateId())||contentsProblem(tank)!=null||remainingMillilitres(tank)<=0)return false;
        Item existing=contents(tank);
        return existing==null||sameLiquid(existing,cargo);
    }
    public static boolean sameLiquid(Item first,Item second) {
        return first.getTemplateId()==second.getTemplateId()&&first.getRealTemplateId()==second.getRealTemplateId()
                &&first.getLeftAuxData()==second.getLeftAuxData()&&first.getRarity()==second.getRarity()
                &&first.isSalted()==second.isSalted();
    }
    public static String insertionProblem(Item tank,Item cargo) {
        if(!isLiquidTank(tank))return "Choose a WurmRail liquid tank.";
        if(LiquidTankTransfers.isFaulted(tank))return "This liquid tank is stopped for recovery. Empty it before clearing its recovery stop.";
        if(!eligible(cargo)||!supports(tank,cargo.getTemplateId()))return "This liquid tank accepts supported water only.";
        if(!tank.getItems().isEmpty())return "The Tank Car already contains a liquid. Pour matching water into its existing stack.";
        if(LiquidTankTransfers.isRestoringInsertion(tank,cargo))return null;
        if(cargo.getWeightGrams()>maxWeightGrams(tank))return "The liquid tank's capacity has been reached.";
        return null;
    }
    /** Native pour/fill merges resize an existing liquid without calling insertItem. */
    public static boolean canChangeWeight(Item item,int requestedGrams) {
        if(item==null||item.getParentId()<=0)return true;
        Item parent=Items.getItemOptional(item.getParentId()).orElse(null);
        if(!isLiquidTank(parent))return true;
        if(LiquidTankTransfers.isRestoring(item,requestedGrams))return true;
        if(requestedGrams<0)return false;
        // Permit drinking, pouring out, native emptying, and recovery of old invalid contents.
        if(requestedGrams<=item.getWeightGrams())return true;
        if(LiquidTankTransfers.isFaulted(parent))return false;
        return eligible(item)&&supports(parent,item.getTemplateId())&&parent.getItems().size()==1&&parent.getItems().contains(item)
                &&requestedGrams<=maxWeightGrams(parent);
    }
    public static boolean canChangeTemplate(Item item,int templateId) {
        if(item==null||item.getParentId()<=0)return true;
        Item parent=Items.getItemOptional(item.getParentId()).orElse(null);
        return !isLiquidTank(parent)||templateId==item.getTemplateId();
    }
    public static boolean hasItemSlot(Item tank) { return isLiquidTank(tank)&&!LiquidTankTransfers.isFaulted(tank)&&tank.getItems().isEmpty(); }
    public static String summary(Item tank) {
        if(!isLiquidTank(tank))return "No liquid tank selected.";
        Item liquid=contents(tank);
        return String.format(Locale.ROOT,"Tank: %,.1f / %,.1f litres (%s)",storedMillilitres(tank)/1000.0,
                capacityMillilitres(tank)/1000.0,liquid==null?"empty":liquid.getName());
    }
}
