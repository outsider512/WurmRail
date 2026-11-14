import com.wurmonline.server.Items;
import com.wurmonline.server.items.*;
import java.util.*;
import org.wurmrail.*;

/** Capacity upgrades never truncate old native water, and old cargo remains unloadable. */
public final class TankCarCapacityRuntimeSmoke {
    private static final long OWNER=998478101L;
    private static final List<Item> created=new ArrayList<Item>();
    private static int assertions;
    private static long refusedTank;
    public static boolean rejectInsertion(Item tank,Item water) {
        if(tank.getWurmId()!=refusedTank)return false;
        refusedTank=0;
        try {Item original=water.getParentOrNull();if(original!=null)original.dropItem(water.getWurmId(),false);}
        catch(Exception error){throw new IllegalStateException(error);}
        return true;
    }
    public static void run()throws Exception {
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            Item car=item(RailwayTankCar.templateId),tank=item(RailwayIndustrialWaterTank.templateId),destination=item(RailwayIndustrialWaterTank.templateId);
            check(car.getContainerVolume()==5000000&&car.getMaxItemWeight()==5000000,"new and existing Tank Car templates expose a5,000kg/5,000L capacity");
            Item legacy=water(10000000);legacy.setParentId(car.getWurmId(),true);((DbItem)car).addItem(legacy,true);
            check(car.getItems().contains(legacy)&&new DbItem(legacy.getWurmId()).getWeightGrams()==10000000,"native loading preserves every litre already present in an old10,000L car");
            check(LiquidTankPolicy.contentsProblem(car)!=null&&LiquidTankPolicy.sourceContentsProblem(car)==null&&LiquidTankPolicy.remainingMillilitres(car)==0,
                    "overfilled legacy cars reject new input but remain valid liquid sources");
            check(!legacy.setWeight(10000001,false)&&legacy.getWeightGrams()==10000000,"native growth cannot add water to an old overcapacity stack");
            LiquidTankTransfers.Result partial=LiquidTankTransfers.transfer(car,tank,1000000);
            check(!partial.requiresOperatorIntervention&&partial.transferredMillilitres==1000000&&volume(car)==9000000&&volume(tank)==1000000,
                    "partial unloading conserves water even while the old source remains above its new limit");
            refusedTank=destination.getWurmId();LiquidTankTransfers.Result failed=LiquidTankTransfers.transfer(car,destination,9000000);
            check(failed.requiresOperatorIntervention&&failed.transferredMillilitres==0&&volume(car)==9000000&&volume(destination)==0
                    &&legacy.getParentId()==car.getWurmId()&&new DbItem(legacy.getWurmId()).getParentId()==car.getWurmId(),
                    "a failed whole-item transfer restores the exact old overcapacity item to its native parent");
            LiquidTankTransfers.Result retry=LiquidTankTransfers.transfer(car,destination,9000000);
            check(!retry.requiresOperatorIntervention&&retry.transferredMillilitres==9000000&&car.getItems().isEmpty()&&volume(destination)==9000000
                    &&LiquidTankPolicy.contents(destination).getWurmId()==legacy.getWurmId(),"retry safely unloads all remaining old water without replacing or deleting its item");
            LiquidTankTransfers.Result load=LiquidTankTransfers.transfer(destination,car,9000000);
            check(!load.requiresOperatorIntervention&&load.transferredMillilitres==5000000&&volume(car)==5000000&&volume(destination)==4000000,
                    "stored-water loading respects the new5,000L limit and leaves excess water at source");
            check(LiquidTankPolicy.contentsProblem(car)==null&&LiquidTankTransfers.transfer(tank,car,1).transferredMillilitres==0,
                    "a full5,000L car cannot accept another millilitre");
            check(volume(car)+volume(destination)+volume(tank)==10000000,"capacity migration and transfer failures neither duplicate nor lose old water");
            System.out.println("PASS native Tank Car capacity migration:5,000L metadata, intact old water, strict input, partial and whole-source unloading, failed-parent restoration and exact conservation ("+assertions+" assertions)");
        }finally {
            refusedTank=0;for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static Item item(int template)throws Exception {Item value=ItemFactory.createItem(template,70f,"Capacity migration fixture");value.setLastOwnerId(OWNER);created.add(value);return value;}
    private static Item water(int grams)throws Exception {Item value=item(ItemList.water);value.setSizes(1,1,1);value.setWeight(grams,false);return value;}
    private static long volume(Item tank){return LiquidTankPolicy.storedMillilitres(tank);}
    private static void check(boolean test,String reason){assertions++;if(!test)throw new AssertionError(reason);}
}
