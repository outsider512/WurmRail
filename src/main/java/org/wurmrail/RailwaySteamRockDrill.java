package org.wurmrail;

import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.ItemTemplateBuilder;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModAction;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;

/** Native placed drill item and captured controls; runtime owns all world mutations. */
public final class RailwaySteamRockDrill {
    public static int templateId;
    public static final int OUTPUT_LIMIT_GRAMS=100000,OUTPUT_VOLUME_CC=100000;
    private static boolean recipesCreated;
    private static final long NO_ID=-10L;
    private static final String[] FACES={"North","East","South","West"};
    private static final String[] BUTTONS={"save","start","stop","refresh","close"};
    private RailwaySteamRockDrill() { }

    public enum Interaction { SHOW, LINK_HOPPER, SELECT_FACES, START, STOP }
    public enum ActionType { SAVE, START, STOP, REFRESH }
    public interface Api {
        View snapshot(Creature actor,long machineId) throws Exception;
        /** Queue and revalidate ownership, reach, revision, exact hopper and face identities. */
        void submit(Creature actor,long machineId,Request request);
        void interact(Creature actor,long machineId,Interaction interaction);
    }

    public static void createTemplate() throws IOException {
        if(templateId!=0)return;
        try {
            int image=ItemTemplateFactory.getInstance().getTemplate(ItemList.pickAxe).getImageNumber();
            templateId=new ItemTemplateBuilder("wurmrail.steamrockdrill.v1")
                    .name("steam rock drill","steam rock drills","An industrial drill for the four adjacent cardinal mine faces. Link fuel, water and an output Bulk Hopper, select faces and start mining. Supplies are consumed only for mining work.")
                    .size(4).itemTypes(new short[]{ItemTypes.ITEM_TYPE_HOLLOW,ItemTypes.ITEM_TYPE_NOTAKE,
                            ItemTypes.ITEM_TYPE_DECORATION,ItemTypes.ITEM_TYPE_NAMED,ItemTypes.ITEM_TYPE_REPAIRABLE,
                            ItemTypes.ITEM_TYPE_METAL,ItemTypes.ITEM_TYPE_LOCKABLE,ItemTypes.ITEM_TYPE_OWNER_MOVEABLE,
                            ItemTypes.ITEM_TYPE_USES_SPECIFIED_CONTAINER_VOLUME})
                    .imageNumber((short)image).behaviourType((short)1).dimensions(190,380,380)
                    .containerSize(50,50,40).maxItemCount(1).maxItemWeight(OUTPUT_LIMIT_GRAMS)
                    .weightGrams(350000).material(ItemMaterials.MATERIAL_IRON)
                    .modelName("model.wurmrail.steamrockdrill.").difficulty(40).decayTime(Long.MAX_VALUE).build().getTemplateId();
            SteamRockDrillRegistry.register(templateId);
        } catch(com.wurmonline.server.items.NoSuchTemplateException missing) {
            throw new IOException("The Steam Rock Drill requires the native pickaxe template",missing);
        }
    }

    public static void createRecipes() {
        if(recipesCreated)return;
        if(templateId==0)throw new IllegalStateException("Create the Steam Rock Drill template first.");
        AdvancedCreationEntry recipe=CreationEntryCreator.createAdvancedEntry(SkillList.SMITHING_BLACKSMITHING,
                ItemList.plank,ItemList.ironBand,templateId,true,true,0,false,false,CreationCategories.TOOLS);
        recipe.setFinalMaterial(ItemMaterials.MATERIAL_IRON);
        recipe.addRequirement(new CreationRequirement(1,ItemList.plank,12,true));
        recipe.addRequirement(new CreationRequirement(2,ItemList.sheetIron,16,true));
        recipe.addRequirement(new CreationRequirement(3,ItemList.ironBand,12,true));
        recipe.addRequirement(new CreationRequirement(4,ItemList.nailsIronLarge,8,true));
        recipesCreated=true;
    }

    public static final class StorageChoice {
        public final long id;
        public final String label;
        public StorageChoice(long id,String label) {
            if(id<=0)throw new IllegalArgumentException("A Bulk Hopper must identify a world item.");
            this.id=id;this.label=label==null?"Bulk Hopper":label;
        }
    }

    /** No live native item or machine-state references escape into a window. */
    public static final class View {
        public final long machineId,revision,hopperId;
        public final String name,hopperLabel,status,pendingOutput;
        public final boolean running,canControl;
        public final int enabledMask,availableMask,currentFace;
        public final double intervalSeconds;
        public final List<StorageChoice> hoppers;
        public final List<String> faceDetails;
        public final SteamSupplyUi.View supplies;
        public View(long machineId,long revision,String name,boolean running,long hopperId,String hopperLabel,
                int enabledMask,int availableMask,int currentFace,double intervalSeconds,String status,String pendingOutput,
                List<StorageChoice> hoppers,List<String> faceDetails,boolean canControl) {
            this(machineId,revision,name,running,hopperId,hopperLabel,enabledMask,availableMask,currentFace,intervalSeconds,status,pendingOutput,hoppers,faceDetails,canControl,SteamSupplyUi.View.empty(true));
        }
        public View(long machineId,long revision,String name,boolean running,long hopperId,String hopperLabel,
                int enabledMask,int availableMask,int currentFace,double intervalSeconds,String status,String pendingOutput,
                List<StorageChoice> hoppers,List<String> faceDetails,boolean canControl,SteamSupplyUi.View supplies) {
            if(machineId<=0||revision< -1||enabledMask<0||enabledMask>15||availableMask<0||availableMask>15
                    ||currentFace< -1||currentFace>3||!Double.isFinite(intervalSeconds)||intervalSeconds<=0)
                throw new IllegalArgumentException("A current Steam Rock Drill identity and face configuration are required.");
            if(hoppers==null||hoppers.size()>512||faceDetails==null||faceDetails.size()!=4)
                throw new IllegalArgumentException("Invalid nearby hopper or cardinal face list.");
            Set<Long> seen=new HashSet<Long>();List<StorageChoice> storageCopy=new ArrayList<StorageChoice>();
            for(StorageChoice hopper:hoppers) {
                if(hopper==null||!seen.add(hopper.id))throw new IllegalArgumentException("Repeated Bulk Hopper identity.");
                storageCopy.add(hopper);
            }
            List<String> faces=new ArrayList<String>();for(String detail:faceDetails)faces.add(detail==null?"":detail);
            this.machineId=machineId;this.revision=revision;this.name=name==null?"Steam rock drill":name;
            this.running=running;this.hopperId=hopperId;this.hopperLabel=hopperLabel==null?"None":hopperLabel;
            this.enabledMask=enabledMask;this.availableMask=availableMask;this.currentFace=currentFace;
            this.intervalSeconds=intervalSeconds;this.status=status==null?"":status;this.pendingOutput=pendingOutput==null?"None":pendingOutput;
            this.hoppers=Collections.unmodifiableList(storageCopy);this.faceDetails=Collections.unmodifiableList(faces);this.canControl=canControl;
            if(supplies==null||!supplies.waterRequired)throw new IllegalArgumentException("The drill requires fuel and water supply controls.");this.supplies=supplies;
        }
    }

    public static final class Request {
        public final ActionType action;
        public final long machineId,revision,hopperId;
        public final int enabledMask;
        public final SteamSupplyUi.Selection supplies;
        private Request(View view,ActionType action,long hopperId,int enabledMask) {
            this(view,action,hopperId,enabledMask,SteamSupplyUi.unchanged(view.supplies));
        }
        private Request(View view,ActionType action,long hopperId,int enabledMask,SteamSupplyUi.Selection supplies) {
            this.action=action;this.machineId=view.machineId;this.revision=view.revision;
            this.hopperId=hopperId;this.enabledMask=enabledMask;
            this.supplies=supplies;
        }
        public static Request refresh(View view) { return new Request(view,ActionType.REFRESH,view.hopperId,view.enabledMask); }
    }

    public static void show(final Creature actor,final long machineId,final Api api) throws Exception {
        if(actor==null||api==null||machineId<=0)throw new IllegalArgumentException("Choose a nearby Steam Rock Drill.");
        final View view=api.snapshot(actor,machineId);
        if(view.machineId!=machineId)throw new IllegalArgumentException("The drill changed. Open its controls again.");
        final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,"Steam rock drill","",machineId,new ModQuestion() {
            @Override public void sendQuestion(Question question) {
                sendBml(question,720,760,true,true,ModQuestions.getBmlHeaderNoQuestion(question)+content(view)+"}};null;null;}");
            }
            @Override public void answer(Question question,Properties answers) {
                if(question.getResponder()!=actor||!answered.compareAndSet(false,true))return;
                try {
                    Request request=fromAnswers(view,answers);
                    if(request!=null)api.submit(actor,machineId,request);
                } catch(IllegalArgumentException invalid) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] "+invalid.getMessage());
                    api.submit(actor,machineId,Request.refresh(view));
                }
            }
        }).sendQuestion();
    }

    public static String content(View view) {
        StringBuilder b=new StringBuilder();heading(b,view.name);
        label(b,"State: "+(view.running?"RUNNING":"STOPPED"));
        label(b,"Status: "+view.status);
        label(b,"Linked Bulk Hopper: "+view.hopperLabel);
        label(b,"Selected mining face: "+(view.currentFace<0?"None":FACES[view.currentFace]));
        List<String> enabled=new ArrayList<String>();for(int face=0;face<4;face++)if((view.enabledMask&(1<<face))!=0)enabled.add(FACES[face]);
        label(b,"Enabled faces: "+(enabled.isEmpty()?"None":String.join(", ",enabled)));
        label(b,String.format(Locale.ROOT,"Mining interval: %.1f seconds per action. Enabled available faces are visited in N, E, S, W order.",view.intervalSeconds));
        label(b,"Pending output: "+view.pendingOutput);
        b.append(SteamSupplyUi.content(view.supplies,view.canControl));
        heading(b,"Output Bulk Hopper");
        if(view.canControl) {
            b.append("harray{label{text='Hopper:'};dropdown{id='hopper_choice';default='").append(hopperIndex(view))
                    .append("';options='No linked hopper");
            for(StorageChoice hopper:view.hoppers)b.append(',').append(RailwayBml.option(hopper.label));
            b.append("'};};");
            if(view.hoppers.isEmpty())label(b,"No compatible nearby Bulk Hopper is available. Place an owned, unlocked hopper nearby and refresh.");
            else if(view.hopperId>0&&hopperIndex(view)==0)label(b,"The linked hopper is unavailable. Select an accessible nearby Bulk Hopper.");
        }
        heading(b,"Adjacent cardinal mine faces");
        for(int face=0;face<4;face++) {
            boolean available=(view.availableMask&(1<<face))!=0,selected=(view.enabledMask&(1<<face))!=0;
            if(view.canControl&&available)b.append("checkbox{id='face_").append(face).append("';selected='").append(selected)
                    .append("';text='").append(RailwayBml.text(FACES[face]+": "+view.faceDetails.get(face))).append("'};");
            else label(b,FACES[face]+": "+view.faceDetails.get(face)+(selected?" (enabled; currently skipped)":""));
        }
        label(b,"Only the four directly adjacent walls are mined. Diagonal faces and tunnel advancement are excluded.");
        label(b,"Output goes to the linked hopper. A blocked output stops the drill. Open the drill only to recover retained output.");
        label(b,"Fuel and water are consumed for mining work. No pressure, startup heating or maintenance is simulated.");
        b.append("harray{");
        if(view.canControl) {button(b,"save","Save settings");button(b,"start","Start mining");button(b,"stop","Stop mining");}
        button(b,"refresh","Refresh status");button(b,"close","Close");b.append("};");
        return b.toString();
    }

    public static Request fromAnswers(View view,Properties answers) {
        if(view==null||answers==null)throw new IllegalArgumentException("Drill answers are missing.");
        String action=null;
        for(String button:BUTTONS)if("true".equals(answers.getProperty(button))) {
            if(action!=null)throw new IllegalArgumentException("Choose one drill action at a time.");action=button;
        }
        if(action==null||action.equals("close"))return null;
        if(action.equals("refresh"))return Request.refresh(view);
        if(!view.canControl)throw new IllegalArgumentException("You do not have permission to control this drill.");
        if(action.equals("stop"))return new Request(view,ActionType.STOP,view.hopperId,view.enabledMask);
        int choice;
        try { choice=Integer.parseInt(answers.getProperty("hopper_choice","").trim()); }
        catch(NumberFormatException invalid) {throw new IllegalArgumentException("Choose a nearby Bulk Hopper.");}
        if(choice<0||choice>view.hoppers.size())throw new IllegalArgumentException("Choose a nearby Bulk Hopper.");
        int enabled=0;
        for(int face=0;face<4;face++) {
            String value=answers.getProperty("face_"+face,"false");
            if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("Choose valid cardinal mining faces.");
            if(value.equals("true")) {
                if((view.availableMask&(1<<face))==0)throw new IllegalArgumentException(FACES[face]+" is no longer an available mine face. Refresh the drill.");
                enabled|=1<<face;
            }
        }
        return new Request(view,action.equals("start")?ActionType.START:ActionType.SAVE,
                choice==0?0:view.hoppers.get(choice-1).id,enabled,SteamSupplyUi.fromAnswers(view.supplies,answers));
    }
    private static int hopperIndex(View view) {for(int i=0;i<view.hoppers.size();i++)if(view.hoppers.get(i).id==view.hopperId)return i+1;return 0;}
    private static void label(StringBuilder b,String text) {
        // Native labels do not wrap, so long stop reasons must remain readable.
        String rest=text==null?"":text;
        while(rest.length()>94) {
            int split=rest.lastIndexOf(' ',94);if(split<20)split=94;
            b.append("label{text='").append(RailwayBml.text(rest.substring(0,split))).append("'};");
            rest=rest.substring(split).trim();
        }
        b.append("label{text='").append(RailwayBml.text(rest)).append("'};");
    }
    private static void heading(StringBuilder b,String text) {b.append("label{type='bold';text='").append(RailwayBml.text(text)).append("'};");}
    private static void button(StringBuilder b,String id,String text) {b.append("button{id='").append(id).append("';text='").append(RailwayBml.text(text)).append("'};");}
    public static boolean isDrill(Item item) {return item!=null&&SteamRockDrillRegistry.isDrill(item.getTemplateId());}
    private static boolean nearby(Creature actor,Item drill) {
        return actor!=null&&isDrill(drill)&&drill.getParentId()==NO_ID&&drill.getOwnerId()==NO_ID
                &&!drill.isBanked()&&!drill.isMailed()&&!drill.isTraded()
                &&actor.isOnSurface()==drill.isOnSurface()&&actor.getFloorLevel()==drill.getFloorLevel()
                &&actor.getBridgeId()==drill.getBridgeId()&&Math.hypot(actor.getPosX()-drill.getPosX(),actor.getPosY()-drill.getPosY())<=8;
    }
    public static void registerActions(final Api api) {
        if(api==null)throw new IllegalArgumentException("A drill control callback is required.");
        final List<ActionEntry> entries=new ArrayList<ActionEntry>();
        for(String label:new String[]{"Steam rock drill status","Select drill Bulk Hopper","Select drill mining faces","Start drill mining","Stop drill mining"}) {
            ActionEntry entry=ActionEntry.createEntry((short)ModActions.getNextActionId(),label,"operating steam rock drill",new int[]{0});
            ModActions.registerAction(entry);entries.add(entry);
        }
        final List<ActionEntry> immutable=Collections.unmodifiableList(entries);
        final Interaction[] interactions={Interaction.SHOW,Interaction.LINK_HOPPER,Interaction.SELECT_FACES,Interaction.START,Interaction.STOP};
        for(int i=0;i<entries.size();i++) {
            final ActionEntry entry=entries.get(i);final Interaction interaction=interactions[i];
            ModActions.registerAction(new ModAction() {
                @Override public BehaviourProvider getBehaviourProvider() {
                    return new BehaviourProvider() {
                        @Override public List<ActionEntry> getBehavioursFor(Creature actor,Item target) {
                            return entry==immutable.get(0)&&nearby(actor,target)?immutable:null;
                        }
                        @Override public List<ActionEntry> getBehavioursFor(Creature actor,Item active,Item target){return getBehavioursFor(actor,target);}
                    };
                }
                @Override public ActionPerformer getActionPerformer() {
                    return new ActionPerformer() {
                        @Override public short getActionId(){return entry.getNumber();}
                        @Override public boolean action(Action action,Creature actor,Item target,short id,float counter) {
                            if(id==entry.getNumber()&&nearby(actor,target))api.interact(actor,target.getWurmId(),interaction);return true;
                        }
                        @Override public boolean action(Action action,Creature actor,Item active,Item target,short id,float counter){return action(action,actor,target,id,counter);}
                    };
                }
            });
        }
    }
}
