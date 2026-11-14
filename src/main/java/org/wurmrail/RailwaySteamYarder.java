package org.wurmrail;

import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.*;
import com.wurmonline.server.questions.Question;
import com.wurmonline.server.skills.SkillList;
import com.wurmonline.shared.constants.ItemMaterials;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.ItemTemplateBuilder;
import org.gotti.wurmunlimited.modsupport.actions.*;
import org.gotti.wurmunlimited.modsupport.questions.*;
import org.wurmrail.core.SteamYarders.HarvestAge;

/** Placed forestry machine and captured controls; native forestry is a separate adapter. */
public final class RailwaySteamYarder {
    public static int templateId;
    private static boolean recipesCreated;
    private RailwaySteamYarder() { }
    public enum Interaction {SHOW,START,STOP}
    public enum ActionType {SAVE,START,STOP,REFRESH}
    public interface Api {
        View snapshot(Creature actor,long id)throws Exception;
        void submit(Creature actor,long id,Request request);
        void interact(Creature actor,long id,Interaction interaction);
    }
    public static void createTemplate()throws IOException {
        if(templateId!=0)return;
        try {
            int image=ItemTemplateFactory.getInstance().getTemplate(ItemList.wagon).getImageNumber();
            templateId=new ItemTemplateBuilder("wurmrail.steamyarder.v1")
                    .name("steam yarder","steam yarders","A forestry machine managing trees in a five-tile circular woodlot. Select a Log Bunk, replant species and harvest age. Fuel and water are required for work; no sprouts are required yet.")
                    .size(4).itemTypes(new short[]{ItemTypes.ITEM_TYPE_HOLLOW,ItemTypes.ITEM_TYPE_NOTAKE,ItemTypes.ITEM_TYPE_NAMED,
                            ItemTypes.ITEM_TYPE_METAL,ItemTypes.ITEM_TYPE_DECORATION,ItemTypes.ITEM_TYPE_REPAIRABLE,
                            ItemTypes.ITEM_TYPE_LOCKABLE,ItemTypes.ITEM_TYPE_OWNER_MOVEABLE,ItemTypes.ITEM_TYPE_USES_SPECIFIED_CONTAINER_VOLUME})
                    .imageNumber((short)image).behaviourType((short)1).dimensions(325,340,360)
                    .containerSize(400,400,400).maxItemCount(1).maxItemWeight(100000000).weightGrams(400000)
                    .material(ItemMaterials.MATERIAL_IRON).modelName("model.wurmrail.steamyarder.").difficulty(40).decayTime(Long.MAX_VALUE).build().getTemplateId();
            SteamYarderRegistry.register(templateId);
        }catch(NoSuchTemplateException missing){throw new IOException("The Steam Yarder requires native wagon metadata",missing);}
    }
    public static void createRecipes() {
        if(recipesCreated)return;if(templateId==0)throw new IllegalStateException("Create the Yarder template first.");
        AdvancedCreationEntry recipe=CreationEntryCreator.createAdvancedEntry(SkillList.SMITHING_BLACKSMITHING,ItemList.plank,ItemList.ironBand,templateId,true,true,0,false,false,CreationCategories.TOOLS);
        recipe.setFinalMaterial(ItemMaterials.MATERIAL_IRON);
        recipe.addRequirement(new CreationRequirement(1,ItemList.plank,20,true));recipe.addRequirement(new CreationRequirement(2,ItemList.sheetIron,16,true));
        recipe.addRequirement(new CreationRequirement(3,ItemList.ironBand,12,true));recipe.addRequirement(new CreationRequirement(4,ItemList.nailsIronLarge,8,true));recipesCreated=true;
    }
    public static boolean isYarder(Item item){return item!=null&&SteamYarderRegistry.isYarder(item.getTemplateId());}
    public static final class Choice {
        public final long id;public final String label;
        public Choice(long id,String label){if(id<0)throw new IllegalArgumentException("Choose a valid forestry option.");this.id=id;this.label=label==null?"":label;}
    }
    public static final class View {
        public final long id,revision,bunkId;
        public final int species;public final HarvestAge harvestAge;
        public final boolean running,canControl;
        public final String name,status,woodlot,pending;
        public final double interval;
        public final List<Choice> bunks,speciesChoices;
        public final SteamSupplyUi.View supplies;
        public View(long id,long revision,String name,boolean running,long bunkId,int species,HarvestAge age,String status,String woodlot,String pending,double interval,List<Choice>bunks,List<Choice>speciesChoices,boolean canControl) {
            this(id,revision,name,running,bunkId,species,age,status,woodlot,pending,interval,bunks,speciesChoices,canControl,SteamSupplyUi.View.empty(true));
        }
        public View(long id,long revision,String name,boolean running,long bunkId,int species,HarvestAge age,String status,String woodlot,String pending,double interval,List<Choice>bunks,List<Choice>speciesChoices,boolean canControl,SteamSupplyUi.View supplies) {
            if(id<=0||revision< -1||age==null||!Double.isFinite(interval)||interval<=0||bunks==null||bunks.size()>512||speciesChoices==null||speciesChoices.isEmpty()||speciesChoices.size()>256)
                throw new IllegalArgumentException("Choose current Steam Yarder controls.");
            this.id=id;this.revision=revision;this.name=name==null?"Steam Yarder":name;this.running=running;this.bunkId=bunkId;this.species=species;harvestAge=age;
            this.status=status==null?"":status;this.woodlot=woodlot==null?"":woodlot;this.pending=pending==null?"None":pending;this.interval=interval;this.canControl=canControl;
            this.bunks=freeze(bunks);this.speciesChoices=freeze(speciesChoices);
            if(supplies==null||!supplies.waterRequired)throw new IllegalArgumentException("The Yarder requires fuel and water supply controls.");this.supplies=supplies;
        }
        private static List<Choice> freeze(List<Choice> source){Set<Long>seen=new HashSet<Long>();for(Choice c:source)if(c==null||!seen.add(c.id))throw new IllegalArgumentException("Repeated forestry choice.");return Collections.unmodifiableList(new ArrayList<Choice>(source));}
    }
    public static final class Request {
        public final ActionType action;public final long id,revision,bunkId;public final int species;public final HarvestAge harvestAge;
        public final SteamSupplyUi.Selection supplies;
        private Request(View view,ActionType action,long bunkId,int species,HarvestAge age){this(view,action,bunkId,species,age,SteamSupplyUi.unchanged(view.supplies));}
        private Request(View view,ActionType action,long bunkId,int species,HarvestAge age,SteamSupplyUi.Selection supplies){this.action=action;id=view.id;revision=view.revision;this.bunkId=bunkId;this.species=species;harvestAge=age;this.supplies=supplies;}
        public static Request refresh(View view){return new Request(view,ActionType.REFRESH,view.bunkId,view.species,view.harvestAge);}
    }
    public static void show(final Creature actor,final long id,final Api api)throws Exception {
        final View view=api.snapshot(actor,id);if(view.id!=id)throw new IllegalArgumentException("The Yarder changed. Refresh its controls.");final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,"Steam Yarder","",id,new ModQuestion(){
            @Override public void sendQuestion(Question q){sendBml(q,720,740,true,true,ModQuestions.getBmlHeaderNoQuestion(q)+content(view)+"}};null;null;}");}
            @Override public void answer(Question q,Properties answers){if(q.getResponder()!=actor||!answered.compareAndSet(false,true))return;try{Request r=fromAnswers(view,answers);if(r!=null)api.submit(actor,id,r);}catch(IllegalArgumentException invalid){actor.getCommunicator().sendNormalServerMessage("[Rail] "+invalid.getMessage());api.submit(actor,id,Request.refresh(view));}}
        }).sendQuestion();
    }
    public static String content(View v) {
        StringBuilder b=new StringBuilder();label(b,v.name,true);label(b,"State: "+(v.running?"RUNNING":"STOPPED"),false);label(b,"Status: "+v.status,false);
        label(b,"Woodlot: "+v.woodlot,false);label(b,"Pending output: "+v.pending,false);
        label(b,String.format(Locale.ROOT,"One harvest every %.1f seconds. Trees grow through normal Wurm simulation.",v.interval),false);
        label(b,"Only trees inside the five-tile circle are managed. Each cut tile is replanted.",false);
        label(b,"Bare ground is not automatically planted. Protected or obstructed tiles are skipped.",false);
        b.append(SteamSupplyUi.content(v.supplies,v.canControl));
        if(v.canControl) {
            dropdown(b,"bunk_choice","Log Bunk",v.bunks,v.bunkId,true);
            if(v.bunks.isEmpty())label(b,"Place an owned, unlocked Log Bunk within eight metres and refresh.",false);
            dropdown(b,"species_choice","Replant species",v.speciesChoices,v.species,false);
            b.append("harray{label{text='Harvest age:'};dropdown{id='age_choice';default='").append(v.harvestAge.ordinal()).append("';options='Mature,Old,Very old,Overaged'};};");
            b.append("harray{");button(b,"save","Save settings");button(b,"start","Start forestry");button(b,"stop","Stop forestry");b.append("};");
        }
        label(b,"Fuel and water are consumed only for forestry work. Waiting for natural tree growth is free; no maintenance or sprouts are required.",false);
        b.append("harray{");button(b,"refresh","Refresh status");button(b,"close","Close");b.append("};");return b.toString();
    }
    public static Request fromAnswers(View v,Properties p) {
        if(v==null||p==null)throw new IllegalArgumentException("Yarder answers are missing.");String action=null;
        for(String key:new String[]{"save","start","stop","refresh","close"})if("true".equals(p.getProperty(key))){if(action!=null)throw new IllegalArgumentException("Choose one forestry action.");action=key;}
        if(action==null||action.equals("close"))return null;if(action.equals("refresh"))return Request.refresh(v);
        if(!v.canControl)throw new IllegalArgumentException("Only the Yarder's owner or a GM may change it.");
        if(action.equals("stop"))return new Request(v,ActionType.STOP,v.bunkId,v.species,v.harvestAge);
        int bunk=index(p,"bunk_choice",v.bunks.size()+1),species=index(p,"species_choice",v.speciesChoices.size()),age=index(p,"age_choice",HarvestAge.values().length);
        return new Request(v,action.equals("start")?ActionType.START:ActionType.SAVE,bunk==0?0:v.bunks.get(bunk-1).id,(int)v.speciesChoices.get(species).id,HarvestAge.values()[age],SteamSupplyUi.fromAnswers(v.supplies,p));
    }
    private static int index(Properties p,String key,int count){try{int i=Integer.parseInt(p.getProperty(key,"").trim());if(i>=0&&i<count)return i;}catch(NumberFormatException invalid){ }throw new IllegalArgumentException("Choose valid forestry settings.");}
    private static void dropdown(StringBuilder b,String id,String title,List<Choice> choices,long selected,boolean none){int at=0;for(int i=0;i<choices.size();i++)if(choices.get(i).id==selected)at=i+(none?1:0);
        b.append("harray{label{text='").append(title).append("'};dropdown{id='").append(id).append("';default='").append(at).append("';options='");if(none)b.append("No linked Log Bunk");
        for(int i=0;i<choices.size();i++){if(none||i>0)b.append(',');b.append(RailwayBml.option(choices.get(i).label));}b.append("'};};");}
    private static void label(StringBuilder b,String text,boolean bold){b.append("label{");if(bold)b.append("type='bold';");b.append("text='").append(RailwayBml.text(text)).append("'};");}
    private static void button(StringBuilder b,String id,String text){b.append("button{id='").append(id).append("';text='").append(RailwayBml.text(text)).append("'};");}
    public static void registerActions(final Api api) {
        final String[] names={"Steam Yarder controls","Start forestry","Stop forestry"};final Interaction[] modes={Interaction.SHOW,Interaction.START,Interaction.STOP};final List<ActionEntry> entries=new ArrayList<ActionEntry>();
        for(String name:names){ActionEntry e=ActionEntry.createEntry((short)ModActions.getNextActionId(),name,"managing woodlot",new int[]{0});ModActions.registerAction(e);entries.add(e);}
        for(int i=0;i<entries.size();i++){final ActionEntry entry=entries.get(i);final Interaction mode=modes[i];ModActions.registerAction(new ModAction(){
            private boolean accepts(Creature p,Item item){return p!=null&&isYarder(item)&&MachineStorageLinks.placed(item)&&p.isOnSurface()==item.isOnSurface()&&p.getFloorLevel()==0&&p.getBridgeId()==-10L&&Math.hypot(p.getPosX()-item.getPosX(),p.getPosY()-item.getPosY())<=8;}
            public BehaviourProvider getBehaviourProvider(){return new BehaviourProvider(){public List<ActionEntry> getBehavioursFor(Creature p,Item item){return entry==entries.get(0)&&accepts(p,item)?Collections.unmodifiableList(entries):null;}public List<ActionEntry> getBehavioursFor(Creature p,Item active,Item item){return getBehavioursFor(p,item);}};}
            public ActionPerformer getActionPerformer(){return new ActionPerformer(){public short getActionId(){return entry.getNumber();}public boolean action(Action a,Creature p,Item item,short id,float counter){if(id==entry.getNumber()&&accepts(p,item))api.interact(p,item.getWurmId(),mode);return true;}public boolean action(Action a,Creature p,Item active,Item item,short id,float counter){return action(a,p,item,id,counter);}};}
        });}
    }
}
