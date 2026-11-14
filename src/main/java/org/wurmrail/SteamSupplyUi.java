package org.wurmrail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Captured supply identities shared by the three machine windows. */
public final class SteamSupplyUi {
    private SteamSupplyUi(){ }
    public static final class Choice {
        public final long id;
        public final String label;
        public Choice(long id,String label){if(id<=0)throw new IllegalArgumentException("Choose a real supply container.");this.id=id;this.label=label==null?"Storage":label;}
    }
    public static final class View {
        public final long revision,fuelId,waterId;
        public final boolean waterRequired;
        public final String status,buffers;
        public final List<Choice> fuels,waters;
        public View(long revision,long fuelId,long waterId,boolean waterRequired,String status,String buffers,List<Choice> fuels,List<Choice> waters) {
            if(revision< -1||fuelId<0||waterId<0||!waterRequired&&waterId!=0)throw new IllegalArgumentException("Choose current steam supply controls.");
            this.revision=revision;this.fuelId=fuelId;this.waterId=waterId;this.waterRequired=waterRequired;
            this.status=status==null?"":status;this.buffers=buffers==null?"":buffers;this.fuels=freeze(fuels);this.waters=freeze(waters);
        }
        public static View empty(boolean water){return new View(-1,0,0,water,"Select supply storage before operating.","",Collections.<Choice>emptyList(),Collections.<Choice>emptyList());}
        private static List<Choice> freeze(List<Choice> source){if(source==null||source.size()>512)throw new IllegalArgumentException("Invalid supply storage list.");Set<Long> seen=new HashSet<Long>();for(Choice c:source)if(c==null||!seen.add(c.id))throw new IllegalArgumentException("Repeated supply container.");return Collections.unmodifiableList(new ArrayList<Choice>(source));}
    }
    public static final class Selection {
        public final long revision,fuelId,waterId;
        public Selection(long revision,long fuelId,long waterId){if(revision< -1||fuelId<0||waterId<0)throw new IllegalArgumentException("Choose valid steam supply links.");this.revision=revision;this.fuelId=fuelId;this.waterId=waterId;}
    }
    public static Selection unchanged(View view){return new Selection(view.revision,view.fuelId,view.waterId);}
    public static Selection fromAnswers(View view,Properties answers) {
        if(view==null||answers==null)throw new IllegalArgumentException("Steam supply answers are missing.");
        // Older machine forms can still preserve their captured supply links.
        if(!answers.containsKey("steam_fuel_choice")&&!answers.containsKey("steam_water_choice"))return unchanged(view);
        int fuel=index(answers,"steam_fuel_choice",view.fuels.size()+1);
        int water=view.waterRequired?index(answers,"steam_water_choice",view.waters.size()+1):0;
        return new Selection(view.revision,fuel==0?0:view.fuels.get(fuel-1).id,water==0?0:view.waters.get(water-1).id);
    }
    public static String content(View view,boolean canControl) {
        StringBuilder b=new StringBuilder();label(b,"Steam supplies",true);
        if(!view.status.isEmpty())label(b,view.status,false);
        if(!view.buffers.isEmpty())label(b,view.buffers,false);
        if(canControl){dropdown(b,"steam_fuel_choice","Fuel Supply",view.fuels,view.fuelId);if(view.waterRequired)dropdown(b,"steam_water_choice","Water Supply",view.waters,view.waterId);}
        else {label(b,"Fuel Supply: "+name(view.fuels,view.fuelId),false);if(view.waterRequired)label(b,"Water Supply: "+name(view.waters,view.waterId),false);}
        label(b,"Fuel uses wood, charcoal or Coal. Supplies are consumed for completed work; idle waiting is free.",false);
        if(!view.waterRequired)label(b,"This pump uses its natural source for boiler water; no separate Water Supply is required.",false);
        return b.toString();
    }
    private static int index(Properties answers,String key,int count){try{int value=Integer.parseInt(answers.getProperty(key,"").trim());if(value>=0&&value<count)return value;}catch(NumberFormatException invalid){ }throw new IllegalArgumentException("Choose valid steam supply storage.");}
    private static String name(List<Choice> choices,long selected){if(selected==0)return "None";for(Choice c:choices)if(c.id==selected)return c.label;return "Unavailable storage ["+selected+"]";}
    private static void dropdown(StringBuilder b,String id,String title,List<Choice> choices,long selected){int index=0;for(int i=0;i<choices.size();i++)if(choices.get(i).id==selected)index=i+1;b.append("harray{label{text='").append(title).append("'};dropdown{id='").append(id).append("';default='").append(index).append("';options='No linked supply");for(Choice c:choices)b.append(',').append(RailwayBml.option(c.label));b.append("'};};");}
    private static void label(StringBuilder b,String text,boolean bold){String rest=text;while(rest.length()>94){int split=rest.lastIndexOf(' ',94);if(split<20)split=94;appendLabel(b,rest.substring(0,split),bold);rest=rest.substring(split).trim();}appendLabel(b,rest,bold);}
    private static void appendLabel(StringBuilder b,String text,boolean bold){b.append("label{");if(bold)b.append("type='bold';");b.append("text='").append(RailwayBml.text(text)).append("'};");}
}
