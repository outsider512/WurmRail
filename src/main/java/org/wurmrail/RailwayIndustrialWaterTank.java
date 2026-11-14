package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.AdvancedCreationEntry;
import com.wurmonline.server.items.CreationCategories;
import com.wurmonline.server.items.CreationEntryCreator;
import com.wurmonline.server.items.CreationRequirement;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.items.ItemTypes;
import com.wurmonline.server.questions.Question;
import com.wurmonline.server.skills.SkillList;
import com.wurmonline.shared.constants.ItemMaterials;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.ItemTemplateBuilder;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModAction;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;

/** Placed native water storage; quantities and properties live in its real liquid item. */
public final class RailwayIndustrialWaterTank {
    public static int templateId;
    private static boolean recipesCreated;
    private static final long NO_ID=-10L;
    public interface Api {
        void open(Creature actor,long tankId);
        /** Existing callbacks remain read-only until the runtime supplies queued recovery. */
        default void recover(Creature actor,long tankId) {open(actor,tankId);}
    }
    public enum StatusAction { REFRESH,RECOVER }
    private RailwayIndustrialWaterTank() { }

    public static void createTemplate() throws IOException {
        if(templateId!=0)return;
        try {
            int image=ItemTemplateFactory.getInstance().getTemplate(ItemList.chestLarge).getImageNumber();
            templateId=new ItemTemplateBuilder("wurmrail.industrialwatertank.v1")
                    .name("industrial water tank","industrial water tanks","A large industrial tank for one compatible water supply. Link a Water Unload Stop to fill it from Tank Cars. Industrial water tank status shows its current water and remaining capacity.")
                    .size(4).itemTypes(new short[]{ItemTypes.ITEM_TYPE_NAMED,ItemTypes.ITEM_TYPE_HOLLOW,
                            ItemTypes.ITEM_TYPE_NOTAKE,ItemTypes.ITEM_TYPE_METAL,ItemTypes.ITEM_TYPE_DECORATION,
                            ItemTypes.ITEM_TYPE_REPAIRABLE,ItemTypes.ITEM_TYPE_LOCKABLE,ItemTypes.ITEM_TYPE_OWNER_MOVEABLE,
                            ItemTypes.ITEM_TYPE_CONTAINER_LIQUID,ItemTypes.ITEM_TYPE_USES_SPECIFIED_CONTAINER_VOLUME})
                    .imageNumber((short)image).behaviourType((short)1).dimensions(460,390,390)
                    .containerSize(500,500,400).maxItemCount(IndustrialWaterTankRegistry.MAX_ITEMS)
                    .maxItemWeight(IndustrialWaterTankRegistry.MAX_WEIGHT_GRAMS).weightGrams(1000000)
                    .material(ItemMaterials.MATERIAL_IRON).modelName("model.wurmrail.industrialwatertank.")
                    .difficulty(40).decayTime(Long.MAX_VALUE).build().getTemplateId();
            IndustrialWaterTankRegistry.register(templateId);
        } catch(com.wurmonline.server.items.NoSuchTemplateException missing) {
            throw new IOException("The Industrial Water Tank requires the native large-chest template",missing);
        }
    }
    public static void createRecipes() {
        if(recipesCreated)return;
        if(templateId==0)throw new IllegalStateException("Create the Industrial Water Tank template first.");
        AdvancedCreationEntry recipe=CreationEntryCreator.createAdvancedEntry(SkillList.SMITHING_BLACKSMITHING,
                ItemList.plank,ItemList.ironBand,templateId,true,true,0,false,false,CreationCategories.CONTAINER);
        recipe.setFinalMaterial(ItemMaterials.MATERIAL_IRON);
        recipe.addRequirement(new CreationRequirement(1,ItemList.plank,24,true));
        recipe.addRequirement(new CreationRequirement(2,ItemList.sheetIron,32,true));
        recipe.addRequirement(new CreationRequirement(3,ItemList.ironBand,16,true));
        recipe.addRequirement(new CreationRequirement(4,ItemList.nailsIronLarge,12,true));
        recipesCreated=true;
    }
    public static boolean isTank(Item item) {
        return item!=null&&IndustrialWaterTankRegistry.isIndustrialTank(item.getTemplateId());
    }
    public static final class View {
        public final String name,liquidLabel,status;
        public final long storedMillilitres,capacityMillilitres;
        public final boolean faulted,empty;
        public View(String name,long storedMillilitres,long capacityMillilitres,String liquidLabel,String status) {
            this(name,storedMillilitres,capacityMillilitres,liquidLabel,status,false,storedMillilitres==0);
        }
        public View(String name,long storedMillilitres,long capacityMillilitres,String liquidLabel,String status,boolean faulted,boolean empty) {
            if(storedMillilitres<0||capacityMillilitres<=0)throw new IllegalArgumentException("Invalid native water quantity.");
            this.name=name==null?"Industrial water tank":name;this.storedMillilitres=storedMillilitres;
            this.capacityMillilitres=capacityMillilitres;this.liquidLabel=liquidLabel==null?"Empty":liquidLabel;
            this.status=status==null?"":status;
            this.faulted=faulted;this.empty=empty;
        }
        public boolean canRecover(){return faulted&&empty&&storedMillilitres==0;}
    }
    /** An explicit, read-only inventory snapshot; there is no world polling or virtual quantity. */
    public static View snapshot(Item tank) {
        if(!isTank(tank))throw new IllegalArgumentException("Choose an Industrial Water Tank.");
        Item liquid=LiquidTankPolicy.contents(tank);
        String problem=LiquidTankPolicy.contentsProblem(tank);
        String label=liquid==null?"Empty":(liquid.isSalted()?"Salt water":"Fresh water")
                +String.format(Locale.ROOT," | Quality: %.2f",liquid.getCurrentQualityLevel());
        String status=problem!=null?problem:tank.isLocked()?"Locked; unlock before automatic unloading or drawing water."
                :tank.isSealedByPlayer()?"Sealed; unseal before automatic unloading or drawing water."
                :"Ready for linked Water Unload Stops and authorized water consumers.";
        return new View(tank.getName(),LiquidTankPolicy.storedMillilitres(tank),LiquidTankPolicy.capacityMillilitres(tank),label,status,
                LiquidTankTransfers.isFaulted(tank),tank.getItems().isEmpty());
    }
    private static boolean nearby(Creature actor,Item tank) {
        return actor!=null&&isTank(tank)&&tank.getParentId()==NO_ID&&tank.getOwnerId()==NO_ID
                &&!tank.isBanked()&&!tank.isMailed()&&!tank.isTraded()
                &&actor.isOnSurface()==tank.isOnSurface()&&actor.getFloorLevel()==tank.getFloorLevel()
                &&actor.getBridgeId()==tank.getBridgeId()
                &&Math.hypot(actor.getPosX()-tank.getPosX(),actor.getPosY()-tank.getPosY())<=8;
    }
    private static Item checked(Creature actor,long id) throws Exception {
        Item tank=Items.getItem(id);
        if(!nearby(actor,tank))throw new IllegalArgumentException("Stand within eight metres of this Industrial Water Tank on the same level.");
        if(actor.getPower()<2&&(!tank.isOwner(actor.getWurmId())||!tank.mayAccessHold(actor)
                ||!Methods.isActionAllowed(actor,Actions.OPEN,tank)))
            throw new IllegalArgumentException("Wurm ownership or permissions deny inspection of this Industrial Water Tank.");
        return tank;
    }
    public static void show(final Creature actor,final long id,final Api api) throws Exception {
        final View view=snapshot(checked(actor,id));final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,"Industrial water tank status","",id,new ModQuestion() {
            @Override public void sendQuestion(Question question) {
                sendBml(question,660,390,true,true,ModQuestions.getBmlHeaderNoQuestion(question)+content(view)+"}};null;null;}");
            }
            @Override public void answer(Question question,Properties answers) {
                if(question.getResponder()!=actor||!answered.compareAndSet(false,true))return;
                try {
                    StatusAction choice=fromAnswers(view,answers);
                    if(choice==StatusAction.REFRESH)api.open(actor,id);
                    else if(choice==StatusAction.RECOVER)api.recover(actor,id);
                } catch(IllegalArgumentException invalid) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] "+invalid.getMessage());api.open(actor,id);
                }
            }
        }).sendQuestion();
    }
    public static String content(View view) {
        StringBuilder b=new StringBuilder();label(b,view.name,true);
        label(b,String.format(Locale.ROOT,"Water: %,.1f / %,.1f litres",view.storedMillilitres/1000.0,view.capacityMillilitres/1000.0),false);
        label(b,String.format(Locale.ROOT,"Remaining capacity: %,.1f litres",Math.max(0,view.capacityMillilitres-view.storedMillilitres)/1000.0),false);
        label(b,"Contents: "+view.liquidLabel,false);label(b,"Status: "+view.status,false);
        label(b,"Link this tank from a nearby Water Unload Stop to receive water from Tank Cars.",false);
        label(b,"Native Open and pouring handle manual access. Solid cargo is rejected.",false);
        label(b,"Water requirements for drills and locomotives remain disabled.",false);
        if(view.canRecover())b.append("button{id='recover';text='Clear empty liquid tank fault'};");
        else if(view.faulted)label(b,"Empty and inspect the retained water before clearing this tank's recovery stop.",false);
        b.append("harray{button{id='refresh';text='Refresh status'};button{id='close';text='Close'};};");return b.toString();
    }
    public static StatusAction fromAnswers(View view,Properties answers) {
        if(view==null||answers==null)throw new IllegalArgumentException("Tank status answers are missing.");
        String choice=null;
        for(String button:new String[]{"refresh","recover","close"})if("true".equals(answers.getProperty(button))) {
            if(choice!=null)throw new IllegalArgumentException("Choose one tank status action at a time.");choice=button;
        }
        if(choice==null||choice.equals("close"))return null;
        if(choice.equals("refresh"))return StatusAction.REFRESH;
        if(!view.canRecover())throw new IllegalArgumentException("Only an empty faulted tank can clear its recovery stop.");
        return StatusAction.RECOVER;
    }
    private static void label(StringBuilder b,String text,boolean bold) {
        b.append("label{");if(bold)b.append("type='bold';");b.append("text='").append(RailwayBml.text(text)).append("'};");
    }
    public static void registerActions(final Api api) {
        if(api==null)throw new IllegalArgumentException("A water tank status callback is required.");
        final ActionEntry entry=ActionEntry.createEntry((short)ModActions.getNextActionId(),"Industrial water tank status","inspecting water tank",new int[]{0});
        ModActions.registerAction(entry);
        ModActions.registerAction(new ModAction() {
            @Override public BehaviourProvider getBehaviourProvider() {
                return new BehaviourProvider() {
                    @Override public List<ActionEntry> getBehavioursFor(Creature actor,Item target) {return nearby(actor,target)?Collections.singletonList(entry):null;}
                    @Override public List<ActionEntry> getBehavioursFor(Creature actor,Item active,Item target){return getBehavioursFor(actor,target);}
                };
            }
            @Override public ActionPerformer getActionPerformer() {
                return new ActionPerformer() {
                    @Override public short getActionId(){return entry.getNumber();}
                    @Override public boolean action(Action action,Creature actor,Item target,short id,float counter) {
                        if(id==entry.getNumber()&&nearby(actor,target))api.open(actor,target.getWurmId());return true;
                    }
                    @Override public boolean action(Action action,Creature actor,Item active,Item target,short id,float counter){return action(action,actor,target,id,counter);}
                };
            }
        });
        final ActionEntry recover=ActionEntry.createEntry((short)ModActions.getNextActionId(),"Clear empty liquid tank fault","clearing empty tank fault",new int[]{0});
        ModActions.registerAction(recover);
        ModActions.registerAction(new ModAction() {
            @Override public BehaviourProvider getBehaviourProvider() {
                return new BehaviourProvider() {
                    @Override public List<ActionEntry> getBehavioursFor(Creature actor,Item target) {
                        return recoveryAvailable(actor,target)?Collections.singletonList(recover):null;
                    }
                    @Override public List<ActionEntry> getBehavioursFor(Creature actor,Item active,Item target){return getBehavioursFor(actor,target);}
                };
            }
            @Override public ActionPerformer getActionPerformer() {
                return new ActionPerformer() {
                    @Override public short getActionId(){return recover.getNumber();}
                    @Override public boolean action(Action action,Creature actor,Item target,short id,float counter) {
                        if(id==recover.getNumber()&&recoveryAvailable(actor,target))api.recover(actor,target.getWurmId());return true;
                    }
                    @Override public boolean action(Action action,Creature actor,Item active,Item target,short id,float counter){return action(action,actor,target,id,counter);}
                };
            }
        });
    }
    private static boolean recoveryAvailable(Creature actor,Item tank) {
        return actor!=null&&LiquidTankPolicy.isLiquidTank(tank)&&LiquidTankTransfers.isFaulted(tank)&&tank.getItems().isEmpty()
                &&MachineStorageLinks.placed(tank)&&actor.isOnSurface()==tank.isOnSurface()
                &&actor.getFloorLevel()==0&&actor.getBridgeId()==NO_ID
                &&(actor.getPower()>=2||tank.isOwner(actor.getWurmId()))
                &&Math.hypot(actor.getPosX()-tank.getPosX(),actor.getPosY()-tank.getPosY())<=8;
    }
}
