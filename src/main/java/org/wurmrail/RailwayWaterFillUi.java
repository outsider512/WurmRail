package org.wurmrail;

import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.questions.Question;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;

/** Captured natural-source choices; all mutations are queued and independently revalidated. */
public final class RailwayWaterFillUi {
    private RailwayWaterFillUi() { }
    public enum Action { SAVE,REMOVE,REFRESH }
    public interface Api {
        View snapshot(Creature actor,long markerId) throws Exception;
        void submit(Creature actor,long markerId,Request request);
    }
    public static final class SourceChoice {
        public final int x,y,layer;
        public final String label;
        public SourceChoice(int x,int y,int layer,String label) {
            if(x<0||y<0||layer!=0&&layer!=-1)throw new IllegalArgumentException("Choose a valid natural water source.");
            this.x=x;this.y=y;this.layer=layer;this.label=label==null?"Water at "+x+", "+y:label;
        }
    }
    public static final class View {
        public final String rail,name,status;
        public final long markerId,revision;
        public final int sourceX,sourceY,sourceLayer;
        public final double rateLitresPerSecond;
        public final List<SourceChoice> sources;
        public final boolean canEdit;
        public View(String rail,long markerId,long revision,String name,int sourceX,int sourceY,int sourceLayer,
                double rate,String status,List<SourceChoice> sources,boolean canEdit) {
            if(rail==null||!rail.matches("[A-Za-z0-9_-]{1,40}")||markerId<=0||revision< -1
                    ||!Double.isFinite(rate)||rate<.1||rate>10000||sources==null||sources.size()>100)
                throw new IllegalArgumentException("Choose this water fill station's current controls.");
            Set<String> seen=new HashSet<String>();List<SourceChoice> copy=new ArrayList<SourceChoice>();
            for(SourceChoice source:sources) {
                if(source==null||!seen.add(source.x+":"+source.y+":"+source.layer))
                    throw new IllegalArgumentException("Repeated natural water source.");
                copy.add(source);
            }
            this.rail=rail;this.markerId=markerId;this.revision=revision;this.name=name==null?"":name;
            this.sourceX=sourceX;this.sourceY=sourceY;this.sourceLayer=sourceLayer;
            this.rateLitresPerSecond=rate;this.status=status==null?"":status;
            this.sources=Collections.unmodifiableList(copy);this.canEdit=canEdit;
        }
        public boolean configured(){return revision>=0;}
    }
    public static final class Request {
        public final Action action;
        public final String expectedRail,name;
        public final long expectedMarkerId,revision;
        public final int sourceX,sourceY,sourceLayer;
        public final double rateLitresPerSecond;
        private Request(View view,Action action,String name,int x,int y,int layer,double rate) {
            this.action=action;this.expectedRail=view.rail;this.expectedMarkerId=view.markerId;this.revision=view.revision;
            this.name=name;this.sourceX=x;this.sourceY=y;this.sourceLayer=layer;this.rateLitresPerSecond=rate;
        }
        public static Request refresh(View view) {
            return new Request(view,Action.REFRESH,view.name,view.sourceX,view.sourceY,view.sourceLayer,view.rateLitresPerSecond);
        }
    }
    public static void show(final Creature actor,final long markerId,final Api api) throws Exception {
        final View view=api.snapshot(actor,markerId);
        if(view.markerId!=markerId)throw new IllegalArgumentException("This track changed. Open its water fill controls again.");
        final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,"Water fill station","",markerId,new ModQuestion() {
            @Override public void sendQuestion(Question question) {
                sendBml(question,700,500,true,true,ModQuestions.getBmlHeaderNoQuestion(question)+content(view)+"}};null;null;}");
            }
            @Override public void answer(Question question,Properties answers) {
                if(question.getResponder()!=actor||!answered.compareAndSet(false,true))return;
                try { Request request=fromAnswers(view,answers);if(request!=null)api.submit(actor,markerId,request); }
                catch(IllegalArgumentException invalid) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] "+invalid.getMessage());
                    api.submit(actor,markerId,Request.refresh(view));
                }
            }
        }).sendQuestion();
    }
    public static String content(View view) {
        StringBuilder b=new StringBuilder();heading(b,view.configured()?view.name:"Configure a Water Fill Station");
        label(b,"Track: "+view.rail);label(b,"Status: "+view.status);
        label(b,"Add this station to a train's normal route. It fills compatible Tank Cars, then the train continues.");
        label(b,"Natural water must remain within eight metres on the same level.");
        label(b,"One transfer rate is shared across the train; ordinary cargo wagons are skipped.");
        if(view.canEdit) {
            b.append("harray{label{text='Station name:'};input{id='water_name';maxchars='48';text='")
                    .append(RailwayBml.text(view.name)).append("'};};");
            b.append("harray{label{text='Natural source:'};dropdown{id='source_choice';default='")
                    .append(sourceIndex(view)).append("';options='Choose water source");
            for(SourceChoice source:view.sources)b.append(',').append(RailwayBml.option(source.label));
            b.append("'};};");
            if(view.sources.isEmpty())label(b,"No valid nearby natural water was found. Build beside suitable water, then refresh.");
            else if(view.configured()&&sourceIndex(view)==0)label(b,"The saved source is unavailable. Choose another valid water source.");
            b.append("harray{label{text='Litres per second:'};input{id='water_rate';maxchars='12';text='")
                    .append(String.format(Locale.ROOT,"%s",view.rateLitresPerSecond)).append("'};};");
            button(b,"save","Save water fill station");
            if(view.configured()) {
                heading(b,"Remove station");label(b,"Remove this stop from every train route before removing it.");
                b.append("checkbox{id='confirm_remove';text='Confirm removal of this Water Fill Station'};");
                button(b,"remove","Remove water fill station");
            }
        } else label(b,"Only this station's owner or a GM can change its settings.");
        b.append("harray{");button(b,"refresh","Refresh status");button(b,"close","Close");b.append("};");return b.toString();
    }
    public static Request fromAnswers(View view,Properties answers) {
        if(view==null||answers==null)throw new IllegalArgumentException("Water fill station answers are missing.");
        String action=null;
        for(String button:new String[]{"save","remove","refresh","close"})if("true".equals(answers.getProperty(button))) {
            if(action!=null)throw new IllegalArgumentException("Choose one water fill action at a time.");action=button;
        }
        if(action==null||action.equals("close"))return null;
        if(action.equals("refresh"))return Request.refresh(view);
        if(!view.canEdit)throw new IllegalArgumentException("You cannot configure this Water Fill Station.");
        if(action.equals("remove")) {
            if(!view.configured()||!"true".equals(answers.getProperty("confirm_remove")))
                throw new IllegalArgumentException("Confirm removal of this configured Water Fill Station first.");
            return new Request(view,Action.REMOVE,view.name,view.sourceX,view.sourceY,view.sourceLayer,view.rateLitresPerSecond);
        }
        String name=answers.getProperty("water_name","").trim();
        if(name.isEmpty()||name.length()>48)throw new IllegalArgumentException("Enter a station name from 1 to 48 characters.");
        for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))throw new IllegalArgumentException("Station names must be on one line.");
        int source;
        try {source=Integer.parseInt(answers.getProperty("source_choice","").trim());}
        catch(NumberFormatException invalid) {throw new IllegalArgumentException("Choose a nearby natural water source.");}
        if(source<1||source>view.sources.size())throw new IllegalArgumentException("Choose a nearby natural water source.");
        double rate;
        try {rate=Double.parseDouble(answers.getProperty("water_rate","").trim());}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("Enter a fill rate from 0.1 to 10000 litres per second.");}
        if(!Double.isFinite(rate)||rate<.1||rate>10000)throw new IllegalArgumentException("Enter a fill rate from 0.1 to 10000 litres per second.");
        SourceChoice selected=view.sources.get(source-1);
        return new Request(view,Action.SAVE,name,selected.x,selected.y,selected.layer,rate);
    }
    private static int sourceIndex(View view) {
        for(int i=0;i<view.sources.size();i++) {SourceChoice source=view.sources.get(i);
            if(source.x==view.sourceX&&source.y==view.sourceY&&source.layer==view.sourceLayer)return i+1;}
        return 0;
    }
    private static void label(StringBuilder b,String text){b.append("label{text='").append(RailwayBml.text(text)).append("'};");}
    private static void heading(StringBuilder b,String text){b.append("label{type='bold';text='").append(RailwayBml.text(text)).append("'};");}
    private static void button(StringBuilder b,String id,String text){b.append("button{id='").append(id).append("';text='").append(RailwayBml.text(text)).append("'};");}
}
