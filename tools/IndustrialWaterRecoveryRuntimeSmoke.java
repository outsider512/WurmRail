import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.questions.Question;
import com.wurmonline.server.questions.Questions;
import com.wurmonline.shared.constants.ItemMaterials;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;
import org.wurmrail.*;

/** Actual native question replay and empty-only recovery visibility, with real liquid items. */
public final class IndustrialWaterRecoveryRuntimeSmoke {
    private static int assertions;
    private IndustrialWaterRecoveryRuntimeSmoke() { }
    public static void run()throws Exception {
        final TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998991617L,true,2);
        Item tank=null,car=null,water=null;
        final int[] recovered={0},refreshed={0};
        try {
            tank=ItemFactory.createItem(RailwayIndustrialWaterTank.templateId,50f,actor.getPosX(),actor.getPosY(),0,true,ItemMaterials.MATERIAL_IRON,-10L,"UI recovery fixture");
            tank.setLastOwnerId(actor.getWurmId());tank.setAuxData((byte)99);
            final RailwayIndustrialWaterTank.Api api=new RailwayIndustrialWaterTank.Api() {
                @Override public void open(Creature who,long id){refreshed[0]++;}
                @Override public void recover(Creature who,long id) {
                    LiquidTankTransfers.clearFault(who,Items.getItemOptional(id).orElse(null));recovered[0]++;
                }
            };
            check(RailwayIndustrialWaterTank.snapshot(tank).canRecover(),"actual faulted empty industrial inventory exposes captured recovery");
            RailwayIndustrialWaterTank.show(actor,tank.getWurmId(),api);Question question=latest(actor);
            Properties answer=new Properties();answer.setProperty("recover","true");question.answer(answer);question.answer(answer);
            check(recovered[0]==1&&!LiquidTankTransfers.isFaulted(tank)&&tank.getItems().isEmpty(),"replayed native recovery answer clears only once and never creates/deletes water");
            check(!RailwayIndustrialWaterTank.snapshot(tank).canRecover(),"healthy native tank immediately hides recovery");
            water=ItemFactory.createItem(ItemList.water,53f,ItemMaterials.MATERIAL_WATER,(byte)0,"UI recovery fixture");
            water.setWeight(1000,false);check(tank.insertItem(water,false,true),"real native water can populate the recovered tank");
            tank.setAuxData((byte)99);
            check(!RailwayIndustrialWaterTank.snapshot(tank).canRecover(),"a fault with retained real water cannot expose recovery");
            RailwayIndustrialWaterTank.show(actor,tank.getWurmId(),api);latest(actor).answer(answer);
            check(recovered[0]==1&&refreshed[0]==1&&LiquidTankTransfers.isFaulted(tank)
                    &&water.getParentId()==tank.getWurmId()&&water.getWeightGrams()==1000,"forged nonempty recovery is rejected before backend and preserves exact liquid");
            Method visible=RailwayIndustrialWaterTank.class.getDeclaredMethod("recoveryAvailable",Creature.class,Item.class);visible.setAccessible(true);
            check(!(Boolean)visible.invoke(null,actor,tank),"native context action is hidden for a nonempty quarantined tank");
            car=WurmRailWorld.spawnTankCar(actor,actor.getPosX()+4,actor.getPosY(),0);car.setAuxData((byte)99);
            check((Boolean)visible.invoke(null,actor,car),"native context recovery also supports an empty faulted Tank Car");
            car.setAuxData((byte)0);check(!(Boolean)visible.invoke(null,actor,car),"healthy Tank Car does not get an unnecessary recovery action");
            System.out.println("PASS native liquid recovery UI: actual status question replay, empty-only fault clearing, retained-water protection and Tank Car context visibility ("+assertions+" assertions)");
        } finally {
            for(Item item:new Item[]{water,tank,car,actor.inventory})if(item!=null&&Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
        }
    }
    @SuppressWarnings("unchecked") private static Question latest(Creature actor)throws Exception {
        Field field=Questions.class.getDeclaredField("questions");field.setAccessible(true);Question latest=null;
        for(Question question:((Map<Integer,Question>)field.get(null)).values())if(question.getResponder()==actor&&(latest==null||question.getId()>latest.getId()))latest=question;
        if(latest==null)throw new AssertionError("No native recovery question was registered");return latest;
    }
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
}
