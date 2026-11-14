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
import org.wurmrail.core.WaterUnloadStops.Operation;

/** Captured Industrial Water Tank choices for the existing route-stop system. */
public final class RailwayWaterUnloadUi {
    private RailwayWaterUnloadUi() { }
    public enum Action {SAVE,REMOVE,REFRESH}
    public interface Api {
        View snapshot(Creature actor,long marker) throws Exception;
        void submit(Creature actor,long marker,Request request);
    }
    public static final class StorageChoice {
        public final long id;public final String label;
        public StorageChoice(long id,String label){if(id<=0)throw new IllegalArgumentException("Choose a water tank.");this.id=id;this.label=label==null?"Industrial Water Tank":label;}
    }
    public static final class View {
        public final String rail,name,status;
        public final long markerId,revision,storageId;
        public final double rateLitresPerSecond;
        public final Operation operation;
        public final List<StorageChoice> storages;
        public final boolean canEdit;
        public View(String rail,long markerId,long revision,String name,long storageId,double rate,String status,List<StorageChoice> storages,boolean canEdit) {
            this(rail,markerId,revision,name,Operation.UNLOAD,storageId,rate,status,storages,canEdit);
        }
        public View(String rail,long markerId,long revision,String name,Operation operation,long storageId,double rate,String status,List<StorageChoice> storages,boolean canEdit) {
            if(rail==null||!rail.matches("[A-Za-z0-9_-]{1,40}")||markerId<=0||revision< -1||!Double.isFinite(rate)||rate<.1||rate>10000
                    ||operation==null||storages==null||storages.size()>512)throw new IllegalArgumentException("Choose current Water freight stop controls.");
            Set<Long> seen=new HashSet<Long>();List<StorageChoice> copy=new ArrayList<StorageChoice>();
            for(StorageChoice choice:storages){if(choice==null||!seen.add(choice.id))throw new IllegalArgumentException("Repeated tank choice.");copy.add(choice);}
            this.rail=rail;this.markerId=markerId;this.revision=revision;this.name=name==null?"":name;this.storageId=storageId;
            rateLitresPerSecond=rate;this.operation=operation;this.status=status==null?"":status;this.storages=Collections.unmodifiableList(copy);this.canEdit=canEdit;
        }
        public boolean configured(){return revision>=0;}
    }
    public static final class Request {
        public final Action action;public final String expectedRail,name;
        public final long expectedMarkerId,revision,storageId;public final double rateLitresPerSecond;public final Operation operation;
        private Request(View view,Action action,String name,Operation operation,long storageId,double rate) {
            this.action=action;expectedRail=view.rail;expectedMarkerId=view.markerId;revision=view.revision;
            this.name=name;this.operation=operation;this.storageId=storageId;rateLitresPerSecond=rate;
        }
        public static Request refresh(View view){return new Request(view,Action.REFRESH,view.name,view.operation,view.storageId,view.rateLitresPerSecond);}
    }
    public static void show(final Creature actor,final long marker,final Api api) throws Exception {
        final View view=api.snapshot(actor,marker);if(view.markerId!=marker)throw new IllegalArgumentException("The track changed. Open its controls again.");
        final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,"Water freight stop","",marker,new ModQuestion() {
            @Override public void sendQuestion(Question question){sendBml(question,700,490,true,true,ModQuestions.getBmlHeaderNoQuestion(question)+content(view)+"}};null;null;}");}
            @Override public void answer(Question question,Properties answers) {
                if(question.getResponder()!=actor||!answered.compareAndSet(false,true))return;
                try{Request request=fromAnswers(view,answers);if(request!=null)api.submit(actor,marker,request);}
                catch(IllegalArgumentException invalid){actor.getCommunicator().sendNormalServerMessage("[Rail] "+invalid.getMessage());api.submit(actor,marker,Request.refresh(view));}
            }
        }).sendQuestion();
    }
    public static String content(View view) {
        StringBuilder b=new StringBuilder();label(b,view.configured()?view.name:"Configure a Water freight stop",true);
        label(b,"Track: "+view.rail,false);label(b,"Status: "+view.status,false);
        label(b,"Add this stop to the normal train route. Water moves between stored water and Tank Cars over time.",false);
        label(b,"LOAD: linked Industrial Water Tank to Tank Cars. UNLOAD: Tank Cars to the linked tank.",false);
        label(b,"The train continues when the source is empty, destination is full, or no compatible water can move.",false);
        if(view.canEdit) {
            b.append("harray{label{text='Operation:'};dropdown{id='operation_choice';default='").append(view.operation.ordinal()).append("';options='LOAD from tank,UNLOAD into tank'};};");
            b.append("harray{label{text='Stop name:'};input{id='unload_name';maxchars='48';text='").append(RailwayBml.text(view.name)).append("'};};");
            int index=0;for(int i=0;i<view.storages.size();i++)if(view.storages.get(i).id==view.storageId)index=i+1;
            b.append("harray{label{text='Industrial Water Tank:'};dropdown{id='storage_choice';default='").append(index).append("';options='Choose tank");
            for(StorageChoice choice:view.storages)b.append(',').append(RailwayBml.option(choice.label));b.append("'};};");
            if(view.storages.isEmpty())label(b,"Place an owned, unlocked Industrial Water Tank within eight metres and refresh.",false);
            else if(view.configured()&&index==0)label(b,"The linked tank is unavailable. Select an accessible nearby tank.",false);
            b.append("harray{label{text='Litres per second:'};input{id='unload_rate';maxchars='12';text='").append(view.rateLitresPerSecond).append("'};};");
            button(b,"save","Save water freight stop");
            if(view.configured()) {
                label(b,"Remove this stop from all train routes before removing it.",false);
                b.append("checkbox{id='confirm_remove';text='Confirm removal of this Water freight stop'};");button(b,"remove","Remove water freight stop");
            }
        } else {label(b,"Operation: "+view.operation,false);label(b,"Only this stop's owner or a GM can change its settings.",false);}
        b.append("harray{");button(b,"refresh","Refresh status");button(b,"close","Close");b.append("};");return b.toString();
    }
    public static Request fromAnswers(View view,Properties answers) {
        if(view==null||answers==null)throw new IllegalArgumentException("Water freight answers are missing.");
        String action=null;for(String button:new String[]{"save","remove","refresh","close"})if("true".equals(answers.getProperty(button))) {
            if(action!=null)throw new IllegalArgumentException("Choose one water freight action.");action=button;
        }
        if(action==null||action.equals("close"))return null;
        if(action.equals("refresh"))return Request.refresh(view);
        if(!view.canEdit)throw new IllegalArgumentException("You cannot configure this Water freight stop.");
        if(action.equals("remove")) {
            if(!view.configured()||!"true".equals(answers.getProperty("confirm_remove")))throw new IllegalArgumentException("Confirm removal first.");
            return new Request(view,Action.REMOVE,view.name,view.operation,view.storageId,view.rateLitresPerSecond);
        }
        String name=answers.getProperty("unload_name","").trim();
        if(name.isEmpty()||name.length()>48)throw new IllegalArgumentException("Enter a stop name from 1 to 48 characters.");
        for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))throw new IllegalArgumentException("Stop names must be on one line.");
        int index;double rate;
        try{index=Integer.parseInt(answers.getProperty("storage_choice","").trim());}catch(NumberFormatException invalid){throw new IllegalArgumentException("Choose an Industrial Water Tank.");}
        if(index<1||index>view.storages.size())throw new IllegalArgumentException("Choose an Industrial Water Tank.");
        try{rate=Double.parseDouble(answers.getProperty("unload_rate","").trim());}catch(NumberFormatException invalid){throw new IllegalArgumentException("Enter a rate from 0.1 to 10000 L/s.");}
        if(!Double.isFinite(rate)||rate<.1||rate>10000)throw new IllegalArgumentException("Enter a rate from 0.1 to 10000 L/s.");
        Operation operation=view.operation;
        if(answers.containsKey("operation_choice")) {
            int selected;
            try{selected=Integer.parseInt(answers.getProperty("operation_choice","").trim());}catch(NumberFormatException invalid){throw new IllegalArgumentException("Choose LOAD or UNLOAD.");}
            if(selected<0||selected>=Operation.values().length)throw new IllegalArgumentException("Choose LOAD or UNLOAD.");
            operation=Operation.values()[selected];
        }
        return new Request(view,Action.SAVE,name,operation,view.storages.get(index-1).id,rate);
    }
    private static void label(StringBuilder b,String text,boolean bold){b.append("label{");if(bold)b.append("type='bold';");b.append("text='").append(RailwayBml.text(text)).append("'};");}
    private static void button(StringBuilder b,String id,String text){b.append("button{id='").append(id).append("';text='").append(RailwayBml.text(text)).append("'};");}
}
