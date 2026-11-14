package org.wurmrail;

import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.questions.Question;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;
import org.wurmrail.core.FreightStops.Operation;

/** Freight configuration only. Runtime owns world access, persistence and servicing. */
public final class RailwayFreightUi {
    private static final String[] BUTTONS={"save","remove","refresh","close"};
    private RailwayFreightUi() { }
    public enum Action { SAVE, REMOVE, REFRESH }

    public interface Api {
        View snapshot(Creature actor,long clickedMarkerId) throws Exception;
        /** Queue and revalidate captured rail, marker, revision, storage, reach and permissions. */
        void submit(Creature actor,long clickedMarkerId,Request request);
    }

    public static final class StorageChoice {
        public final long id;
        public final String label;
        public StorageChoice(long id,String label) {
            if(id<=0)throw new IllegalArgumentException("Trackside storage must identify a world item.");
            this.id=id;this.label=label==null?"Storage":label;
        }
    }

    /** Snapshot values and stable storage identities; never live inventories or graph objects. */
    public static final class View {
        public final String rail,name,status;
        public final long markerId,revision,storageId;
        public final Operation operation;
        public final int templateId,coalTemplateId;
        public final double rateKgPerSecond;
        public final List<StorageChoice> storages;
        public final boolean canEdit;
        public View(String rail,long markerId,long revision,String name,Operation operation,
                long storageId,int templateId,double rateKgPerSecond,String status,
                List<StorageChoice> storages,boolean canEdit) {
            if(rail==null||!rail.matches("[A-Za-z0-9_-]{1,40}")||markerId<=0||revision< -1)
                throw new IllegalArgumentException("A current railway identity is required.");
            if(operation==null||templateId<0||!Double.isFinite(rateKgPerSecond)
                    ||rateKgPerSecond<.1||rateKgPerSecond>10000)
                throw new IllegalArgumentException("Invalid freight configuration.");
            if(storages==null||storages.size()>512)throw new IllegalArgumentException("Invalid nearby storage list.");
            Set<Long> ids=new HashSet<Long>();List<StorageChoice> copy=new ArrayList<StorageChoice>();
            for(StorageChoice storage:storages) {
                if(storage==null||!ids.add(storage.id))throw new IllegalArgumentException("Repeated storage identity.");
                copy.add(storage);
            }
            this.rail=rail;this.markerId=markerId;this.revision=revision;
            this.name=name==null?"":name;this.operation=operation;this.storageId=storageId;
            this.templateId=templateId;this.rateKgPerSecond=rateKgPerSecond;
            this.coalTemplateId=CoalItemRegistry.templateId();
            this.status=status==null?"":status;this.storages=Collections.unmodifiableList(copy);this.canEdit=canEdit;
        }
        public boolean configured() { return revision>=0; }
    }

    public static final class Request {
        public final Action action;
        public final String expectedRail,name;
        public final long expectedMarkerId,revision,storageId;
        public final Operation operation;
        public final int templateId;
        public final double rateKgPerSecond;
        private Request(View view,Action action,String name,Operation operation,long storageId,
                int templateId,double rateKgPerSecond) {
            this.action=action;this.expectedRail=view.rail;this.expectedMarkerId=view.markerId;this.revision=view.revision;
            this.name=name;this.operation=operation;this.storageId=storageId;
            this.templateId=templateId;this.rateKgPerSecond=rateKgPerSecond;
        }
        public static Request refresh(View view) {
            return new Request(view,Action.REFRESH,"",view.operation,view.storageId,view.templateId,view.rateKgPerSecond);
        }
    }

    public static void show(final Creature actor,final long clickedMarkerId,final Api api) throws Exception {
        if(actor==null||api==null||clickedMarkerId<=0)throw new IllegalArgumentException("Choose a nearby railway track.");
        final View view=api.snapshot(actor,clickedMarkerId);
        if(view.markerId!=clickedMarkerId)throw new IllegalArgumentException("This track changed. Open its freight window again.");
        final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,"Railway freight stop","",clickedMarkerId,new ModQuestion() {
            @Override public void sendQuestion(Question question) {
                sendBml(question,700,610,true,true,
                        ModQuestions.getBmlHeaderNoQuestion(question)+content(view)+"}};null;null;}");
            }
            @Override public void answer(Question question,Properties answers) {
                if(question.getResponder()!=actor||!answered.compareAndSet(false,true))return;
                try {
                    Request request=fromAnswers(view,answers);
                    if(request!=null)api.submit(actor,clickedMarkerId,request);
                } catch(IllegalArgumentException invalid) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] "+invalid.getMessage());
                    api.submit(actor,clickedMarkerId,Request.refresh(view));
                }
            }
        }).sendQuestion();
    }

    public static String content(View view) {
        StringBuilder b=new StringBuilder();
        heading(b,view.configured()?view.name:"Configure a freight stop");
        label(b,"Track: "+view.rail);
        if(!view.status.isEmpty())label(b,"Status: "+view.status);
        label(b,"Add this stop in Train controls > Set up train route. Arriving trains stop, transfer cargo, then continue.");
        label(b,"LOAD moves storage cargo into attached wagons. UNLOAD moves wagon cargo into storage.");
        label(b,"Log Cars carry logs and felled trees. When attached, they receive timber instead of general wagons.");
        label(b,"Whole items retain their contents and properties. Heavier items take longer at the configured kg/s rate.");
        if(view.canEdit) {
            b.append("harray{label{text='Stop name:'};input{id='freight_name';maxchars='48';text='")
                    .append(RailwayBml.text(view.name)).append("'};};");
            b.append("harray{label{text='Operation:'};dropdown{id='operation_choice';default='")
                    .append(view.operation==Operation.LOAD?0:1).append("';options='LOAD,UNLOAD'};};");
            b.append("harray{label{text='Trackside storage:'};dropdown{id='storage_choice';default='")
                    .append(storageIndex(view)).append("';options='Choose storage");
            for(StorageChoice storage:view.storages)b.append(',').append(RailwayBml.option(storage.label));
            b.append("'};};");
            if(view.storages.isEmpty())label(b,"No eligible storage is nearby. Place accessible storage beside this track and refresh.");
            else if(view.configured()&&storageIndex(view)==0)label(b,"The associated storage is unavailable. Choose accessible storage before saving.");
            b.append("harray{label{text='Cargo filter:'};dropdown{id='filter_choice';default='")
                    .append(view.templateId==0?0:view.coalTemplateId>0&&view.templateId==view.coalTemplateId?2:1)
                    .append("';options='Any eligible cargo,Specific item template");
            if(view.coalTemplateId>0)b.append(",Coal");b.append("'};};");
            b.append("harray{label{text='Item template ID:'};input{id='template_id';maxchars='10';text='")
                    .append(view.templateId).append("'};};");
            label(b,"For a specific item, enter its positive template ID. Any cargo and Coal ignore this field.");
            b.append("harray{label{text='Transfer rate (kg/s):'};input{id='transfer_rate';maxchars='16';text='")
                    .append(view.rateKgPerSecond).append("'};};");
            label(b,"The generic freight platform defaults to 100 kg/s. Servicing ends when no eligible cargo can move.");
            if(!view.storages.isEmpty())button(b,"save",view.configured()?"Save freight stop":"Create freight stop");
            if(view.configured()) {
                b.append("checkbox{id='confirm_remove';selected='false';text='Confirm removal of this freight stop'};");
                button(b,"remove","Remove freight stop");
            }
        } else {
            label(b,"Operation: "+view.operation);
            label(b,"Cargo: "+(view.templateId==0?"Any eligible cargo":view.coalTemplateId>0&&view.templateId==view.coalTemplateId?"Coal":"Item template "+view.templateId));
            label(b,"Transfer rate: "+view.rateKgPerSecond+" kg/s");
            label(b,"Only an authorized builder can change this freight stop.");
        }
        b.append("harray{");button(b,"refresh","Refresh");button(b,"close","Close");b.append("};");
        return b.toString();
    }

    public static Request fromAnswers(View view,Properties answers) {
        if(view==null||answers==null)throw new IllegalArgumentException("Freight answers are missing.");
        String action=null;
        for(String button:BUTTONS)if("true".equals(answers.getProperty(button))) {
            if(action!=null)throw new IllegalArgumentException("Choose one freight action at a time.");
            action=button;
        }
        if(action==null||action.equals("close"))return null;
        if(action.equals("refresh"))return Request.refresh(view);
        if(!view.canEdit)throw new IllegalArgumentException("You do not have permission to configure this freight stop.");
        if(action.equals("remove")) {
            if(!view.configured())throw new IllegalArgumentException("This track has no freight stop to remove.");
            if(!"true".equals(answers.getProperty("confirm_remove")))throw new IllegalArgumentException("Tick the freight stop removal confirmation first.");
            return new Request(view,Action.REMOVE,"",view.operation,view.storageId,view.templateId,view.rateKgPerSecond);
        }
        String name=answers.getProperty("freight_name","").trim();
        if(name.isEmpty()||name.length()>48)throw new IllegalArgumentException("Enter a freight stop name from 1 to 48 characters.");
        for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))throw new IllegalArgumentException("Freight stop names must be on one line.");
        int operation=index(answers,"operation_choice",2,"Choose LOAD or UNLOAD.");
        int storage=index(answers,"storage_choice",view.storages.size()+1,"Choose nearby trackside storage.");
        if(storage==0)throw new IllegalArgumentException("Choose nearby trackside storage.");
        int filter=index(answers,"filter_choice",view.coalTemplateId>0?3:2,"Choose an available cargo filter.");
        int template=filter==2?view.coalTemplateId:0;
        if(filter==1)try {
            template=Integer.parseInt(answers.getProperty("template_id","").trim());
            if(template<=0)throw new NumberFormatException();
        } catch(NumberFormatException invalid) {throw new IllegalArgumentException("Enter a positive item template ID.");}
        double rate;
        try {rate=Double.parseDouble(answers.getProperty("transfer_rate","").trim());}
        catch(NumberFormatException invalid) {throw new IllegalArgumentException("Enter a transfer rate from 0.1 to 10000 kg/s.");}
        if(!Double.isFinite(rate)||rate<.1||rate>10000)throw new IllegalArgumentException("Enter a transfer rate from 0.1 to 10000 kg/s.");
        return new Request(view,Action.SAVE,name,operation==0?Operation.LOAD:Operation.UNLOAD,
                view.storages.get(storage-1).id,template,rate);
    }

    private static int storageIndex(View view) {
        for(int i=0;i<view.storages.size();i++)if(view.storages.get(i).id==view.storageId)return i+1;
        return 0;
    }
    private static int index(Properties p,String key,int count,String error) {
        try {int value=Integer.parseInt(p.getProperty(key,"").trim());if(value>=0&&value<count)return value;}
        catch(NumberFormatException invalid) { }
        throw new IllegalArgumentException(error);
    }
    private static void label(StringBuilder b,String text) {b.append("label{text='").append(RailwayBml.text(text)).append("'};");}
    private static void heading(StringBuilder b,String text) {b.append("label{type='bold';text='").append(RailwayBml.text(text)).append("'};");}
    private static void button(StringBuilder b,String id,String text) {b.append("button{id='").append(id).append("';text='").append(RailwayBml.text(text)).append("'};");}
}
