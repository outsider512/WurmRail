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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.ItemTemplateBuilder;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModAction;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;

/** A placed industrial container with ordinary native item inventories and permissions. */
public final class RailwayLogBunk {
    public static int templateId;
    private static boolean recipesCreated;
    private static final long NO_ID=-10L;
    public interface Api { void open(Creature actor,long bunkId); }
    private RailwayLogBunk() { }

    /** Runs after native templates and pre-init inventory hooks are ready. */
    public static void createTemplate() throws IOException {
        if(templateId!=0)return;
        try {
            int image=ItemTemplateFactory.getInstance().getTemplate(ItemList.chestLarge).getImageNumber();
            templateId=new ItemTemplateBuilder("wurmrail.logbunk.v1")
                    .name("log bunk","log bunks","An industrial timber rack for felled trees and logs. Open it to store real timber; Log bunk status shows contents and remaining capacity.")
                    .size(4).itemTypes(new short[]{ItemTypes.ITEM_TYPE_HOLLOW,ItemTypes.ITEM_TYPE_NOTAKE,
                            ItemTypes.ITEM_TYPE_DECORATION,ItemTypes.ITEM_TYPE_NAMED,ItemTypes.ITEM_TYPE_REPAIRABLE,
                            ItemTypes.ITEM_TYPE_METAL,ItemTypes.ITEM_TYPE_LOCKABLE,
                            ItemTypes.ITEM_TYPE_USES_SPECIFIED_CONTAINER_VOLUME,ItemTypes.ITEM_TYPE_OWNER_MOVEABLE})
                    .imageNumber((short)image).behaviourType((short)1).dimensions(230,360,365)
                    .containerSize(400,400,400).maxItemCount(LogBunkPolicy.MAX_ITEMS)
                    .maxItemWeight(Integer.MAX_VALUE).weightGrams(250000)
                    .material(ItemMaterials.MATERIAL_IRON).modelName("model.wurmrail.logbunk.")
                    .difficulty(35).decayTime(Long.MAX_VALUE).build().getTemplateId();
            LogBunkRegistry.register(templateId);
        } catch(com.wurmonline.server.items.NoSuchTemplateException missing) {
            throw new IOException("The log bunk requires the native large-chest template",missing);
        }
    }

    /** Ordinary ground construction; the heavy NOTAKE result remains a placed container. */
    public static void createRecipes() {
        if(recipesCreated)return;
        if(templateId==0)throw new IllegalStateException("Create the log bunk template first.");
        AdvancedCreationEntry recipe=CreationEntryCreator.createAdvancedEntry(SkillList.CARPENTRY_FINE,
                ItemList.plank,ItemList.ironBand,templateId,true,true,0,false,false,CreationCategories.CONTAINER);
        recipe.setFinalMaterial(ItemMaterials.MATERIAL_IRON);
        recipe.addRequirement(new CreationRequirement(1,ItemList.plank,24,true));
        recipe.addRequirement(new CreationRequirement(2,ItemList.sheetIron,4,true));
        recipe.addRequirement(new CreationRequirement(3,ItemList.ironBand,8,true));
        recipe.addRequirement(new CreationRequirement(4,ItemList.nailsIronLarge,8,true));
        recipesCreated=true;
    }

    public static final class Row {
        public final String name;
        public final int count;
        public final long grams;
        public Row(String name,int count,long grams){this.name=name;this.count=count;this.grams=grams;}
    }
    public static final class View {
        public final String name;
        public final long weightGrams,remainingWeightGrams,volume,remainingVolume;
        public final int itemCount;
        public final List<Row> contents;
        public final List<String> allowed;
        public View(String name,long weight,long remainingWeight,long volume,long remainingVolume,int count,List<Row> rows,List<String> allowed) {
            this.name=name;weightGrams=weight;remainingWeightGrams=remainingWeight;this.volume=volume;this.remainingVolume=remainingVolume;
            itemCount=count;contents=Collections.unmodifiableList(new ArrayList<Row>(rows));this.allowed=Collections.unmodifiableList(new ArrayList<String>(allowed));
        }
    }
    /** Bounded inventory read on explicit inspection, never a periodic world scan. */
    public static View snapshot(Item hopper) {
        if(!LogBunkPolicy.isBunk(hopper))throw new IllegalArgumentException("Choose a log bunk.");
        Map<Integer,long[]> quantities=new LinkedHashMap<Integer,long[]>();
        for(Item item:hopper.getItems()) {
            long[] entry=quantities.get(item.getTemplateId());
            if(entry==null){entry=new long[2];quantities.put(item.getTemplateId(),entry);}
            entry[0]++;entry[1]+=Math.max(0,item.getFullWeight());
        }
        List<Row> rows=new ArrayList<Row>();
        for(Map.Entry<Integer,long[]> entry:quantities.entrySet()) {
            String name=templateName(entry.getKey());long[] quantity=entry.getValue();
            rows.add(new Row(name,(int)Math.min(Integer.MAX_VALUE,quantity[0]),quantity[1]));
        }
        rows.sort(Comparator.comparing(row->row.name));
        List<String> allowed=new ArrayList<String>();for(int id:LogBunkPolicy.allowedTemplateIds())allowed.add(templateName(id));
        Collections.sort(allowed);
        return new View(hopper.getName(),LogBunkPolicy.storedWeightGrams(hopper),LogBunkPolicy.remainingWeightGrams(hopper),
                LogBunkPolicy.storedVolume(hopper),LogBunkPolicy.remainingVolume(hopper),hopper.getItems().size(),rows,allowed);
    }
    private static String templateName(int id) {
        try{return ItemTemplateFactory.getInstance().getTemplate(id).getName();}
        catch(com.wurmonline.server.items.NoSuchTemplateException missing){return "Item template "+id;}
    }
    private static Item checked(Creature actor,long id) throws Exception {
        Item hopper=Items.getItem(id);
        if(!nearby(actor,hopper))throw new IllegalArgumentException("Stand within eight metres of this log bunk on the same level.");
        if(actor.getPower()<2) {
            if(!hopper.isOwner(actor.getWurmId())||!hopper.mayAccessHold(actor)
                    ||!Methods.isActionAllowed(actor,Actions.OPEN,hopper))
                throw new IllegalArgumentException("The Log Bunk's ownership or Wurm permissions deny inspection.");
            if(hopper.isLocked())throw new IllegalArgumentException("Unlock the log bunk before inspecting its contents.");
        }
        return hopper;
    }
    private static boolean nearby(Creature actor,Item hopper) {
        return actor!=null&&LogBunkPolicy.isBunk(hopper)&&hopper.getParentId()==NO_ID&&hopper.getOwnerId()==NO_ID
                &&!hopper.isBanked()&&!hopper.isMailed()&&!hopper.isTraded()
                &&actor.isOnSurface()==hopper.isOnSurface()&&actor.getFloorLevel()==hopper.getFloorLevel()
                &&actor.getBridgeId()==hopper.getBridgeId()
                &&Math.hypot(actor.getPosX()-hopper.getPosX(),actor.getPosY()-hopper.getPosY())<=8;
    }
    public static void show(final Creature actor,final long id,final Api api) throws Exception {
        final View view=snapshot(checked(actor,id));final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,"Log bunk status","",id,new ModQuestion() {
            @Override public void sendQuestion(Question question) {
                sendBml(question,680,560,true,true,ModQuestions.getBmlHeaderNoQuestion(question)+content(view)+"}};null;null;}");
            }
            @Override public void answer(Question question,Properties answer) {
                if(question.getResponder()==actor&&answered.compareAndSet(false,true)&&answer!=null
                        &&"true".equals(answer.getProperty("refresh"))&&!"true".equals(answer.getProperty("close")))api.open(actor,id);
            }
        }).sendQuestion();
    }
    public static String content(View view) {
        StringBuilder b=new StringBuilder();label(b,view.name,true);
        label(b,String.format(Locale.ROOT,"Stored weight: %,.1f / %,.1f kg | Remaining: %,.1f kg",view.weightGrams/1000.0,
                LogBunkPolicy.MAX_WEIGHT_GRAMS/1000.0,view.remainingWeightGrams/1000.0),false);
        label(b,String.format(Locale.ROOT,"Stored volume: %,.1f / %,.1f litres | Remaining: %,.1f litres",view.volume/1000.0,
                LogBunkPolicy.MAX_VOLUME_CC/1000.0,view.remainingVolume/1000.0),false);
        label(b,"Item stacks: "+view.itemCount+" / "+LogBunkPolicy.MAX_ITEMS,false);
        label(b,"Contents",true);
        if(view.contents.isEmpty())label(b,"Empty",false);
        int shown=0;for(Row row:view.contents) {
            if(shown++>=50){label(b,"Additional timber types are available in the native Open window.",false);break;}
            label(b,String.format(Locale.ROOT,"%s: %d items, %,.1f kg",row.name,row.count,row.grams/1000.0),false);
        }
        label(b,"Approved timber",true);
        for(int at=0;at<view.allowed.size();at+=3)label(b,String.join(", ",view.allowed.subList(at,Math.min(view.allowed.size(),at+3))),false);
        label(b,"Open uses the normal Wurm inventory. Freight stops can link to this Log Bunk as storage.",false);
        b.append("harray{button{id='refresh';text='Refresh'};button{id='close';text='Close'};};");return b.toString();
    }
    private static void label(StringBuilder b,String text,boolean bold) {
        b.append("label{");if(bold)b.append("type='bold';");b.append("text='").append(RailwayBml.text(text)).append("'};");
    }
    public static void registerActions(final Api api) {
        if(api==null)throw new IllegalArgumentException("A Log Bunk status callback is required.");
        final ActionEntry entry=ActionEntry.createEntry((short)ModActions.getNextActionId(),"Log bunk status","inspecting log bunk",new int[]{0});
        ModActions.registerAction(entry);
        ModActions.registerAction(new ModAction() {
            @Override public BehaviourProvider getBehaviourProvider() {
                return new BehaviourProvider() {
                    @Override public List<ActionEntry> getBehavioursFor(Creature actor,Item target) {
                        return nearby(actor,target)?Collections.singletonList(entry):null;
                    }
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
    }
}
