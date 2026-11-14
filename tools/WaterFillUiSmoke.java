import com.wurmonline.client.bml.BNode;
import com.wurmonline.client.bml.BParser;
import java.util.*;
import org.wurmrail.RailwayWaterFillUi;
import org.wurmrail.RailwayWaterFillUi.*;

/** Native BML grammar plus captured natural-source/marker/revision form identities. */
public final class WaterFillUiSmoke {
    private static int assertions;
    private static final String HOSTILE="Water,Lake';};button{id='injected';text='bad'};{\\\n";
    public static void main(String[] args)throws Exception {
        List<SourceChoice> sources=new ArrayList<SourceChoice>(Arrays.asList(new SourceChoice(20,30,0,HOSTILE),new SourceChoice(22,30,0,"Lake shore")));
        View view=new View("water_rail",55,7,HOSTILE,22,30,0,100,"Ready "+HOSTILE,sources,true);
        sources.clear();check(view.sources.size()==2,"captured water choices survive mutation of the original nearby-source list");
        try{view.sources.clear();throw new AssertionError("mutable source view");}catch(UnsupportedOperationException expected){assertions++;}
        Map<String,BNode> fields=parse(view);
        check(!fields.containsKey("injected"),"station, source and status labels cannot inject native BML controls");
        check(fields.get("source_choice").getAttribute("options").split(",",-1).length==3,"commas in source labels cannot change captured dropdown indices");
        check(fields.get("source_choice").getAttribute("default").equals("2"),"saved source coordinates select their exact current native index");
        check(fields.containsKey("save")&&fields.containsKey("remove")&&fields.containsKey("confirm_remove"),"configured owner form exposes explicit save and confirmed removal");
        Properties save=save();Request request=RailwayWaterFillUi.fromAnswers(view,save);
        check(request.action==Action.SAVE&&request.sourceX==20&&request.sourceY==30&&request.sourceLayer==0,"save decodes the captured natural water coordinates");
        check(request.expectedRail.equals("water_rail")&&request.expectedMarkerId==55&&request.revision==7,"source selection retains immutable route marker/revision identities");
        check(request.name.equals("Mine Water")&&request.rateLitresPerSecond==100,"readable name and litre rate decode with trimming");
        save.setProperty("sourceX","200");save.setProperty("sourceY","201");save.setProperty("sourceLayer","-1");
        save.setProperty("expectedMarkerId","9999");save.setProperty("expectedRail","forged");save.setProperty("revision","9999");
        request=RailwayWaterFillUi.fromAnswers(view,save);
        check(request.sourceX==20&&request.sourceY==30&&request.sourceLayer==0&&request.expectedMarkerId==55&&request.revision==7,
                "forged physical source and marker identities cannot replace the server's captured choices");
        for(String bad:new String[]{"","-1","0","3","999999999999","NaN","1.5"}) {
            Properties p=copy(save);p.setProperty("source_choice",bad);reject(view,p,"invalid source index");
        }
        for(String bad:new String[]{"","bad","NaN","Infinity","-Infinity","0","-1","10000.1"}) {
            Properties p=copy(save);p.setProperty("water_rate",bad);reject(view,p,"invalid liquid rate");
        }
        for(String valid:new String[]{"0.1","10000","1e2"}) {
            Properties p=copy(save);p.setProperty("water_rate",valid);check(RailwayWaterFillUi.fromAnswers(view,p).rateLitresPerSecond==Double.parseDouble(valid),"inclusive valid liquid rate accepted");
        }
        for(String bad:new String[]{"","   ","two\nlines","bad\tname",String.join("",Collections.nCopies(49,"x"))}) {
            Properties p=copy(save);p.setProperty("water_name",bad);reject(view,p,"invalid station name");
        }
        Properties multiple=copy(save);multiple.setProperty("remove","true");multiple.setProperty("confirm_remove","true");reject(view,multiple,"multiple mutation buttons");
        Properties remove=new Properties();remove.setProperty("remove","true");reject(view,remove,"unconfirmed station removal");
        remove.setProperty("confirm_remove","true");Request removal=RailwayWaterFillUi.fromAnswers(view,remove);
        check(removal.action==Action.REMOVE&&removal.expectedMarkerId==55&&removal.sourceX==22,"removal ignores edited fields and retains its captured station/source identity");
        View readonly=new View("water_rail",55,7,HOSTILE,22,30,0,100,"Ready",view.sources,false);
        fields=parse(readonly);check(!fields.containsKey("save")&&!fields.containsKey("remove")&&!fields.containsKey("source_choice"),"read-only viewers have no station mutation controls");
        reject(readonly,save,"forged readonly save");reject(readonly,remove,"forged readonly removal");
        Properties refresh=new Properties();refresh.setProperty("refresh","true");check(RailwayWaterFillUi.fromAnswers(readonly,refresh).action==Action.REFRESH,"read-only status refresh remains available");
        Properties close=new Properties();close.setProperty("close","true");check(RailwayWaterFillUi.fromAnswers(readonly,close)==null,"closing has no state mutation");
        View absent=new View("water_rail",55,-1,"",-1,-1,0,100,"No nearby water",Collections.<SourceChoice>emptyList(),true);
        fields=parse(absent);check(!fields.containsKey("remove")&&RailwayWaterFillUi.content(absent).contains("No valid nearby natural water"),"new station with no water has an explicit source failure and no removal action");
        reject(absent,save,"cannot invent a source when none was captured");reject(absent,remove,"cannot remove an unconfigured station");
        View missing=new View("water_rail",55,7,"Missing water",99,99,0,100,"Source dry",view.sources,true);
        check(parse(missing).get("source_choice").getAttribute("default").equals("0"),"missing saved source never silently chooses a different lake tile");
        try {new View("water_rail",55,0,"Duplicate",20,30,0,100,"",Arrays.asList(new SourceChoice(20,30,0,"A"),new SourceChoice(20,30,0,"B")),true);throw new AssertionError("duplicate source accepted");}
        catch(IllegalArgumentException expected){assertions++;}
        System.out.println("PASS native Water Fill Station BML: source availability, captured coordinates/marker/revision, rates, immutable choices, ownership and hostile names ("+assertions+" assertions)");
    }
    private static Properties save(){Properties p=new Properties();p.setProperty("save","true");p.setProperty("water_name","  Mine Water  ");p.setProperty("source_choice","1");p.setProperty("water_rate","100");return p;}
    private static Properties copy(Properties source){Properties p=new Properties();p.putAll(source);return p;}
    private static void reject(View view,Properties p,String why){try{RailwayWaterFillUi.fromAnswers(view,p);throw new AssertionError(why);}catch(IllegalArgumentException expected){assertions++;}}
    private static Map<String,BNode> parse(View view)throws Exception {
        BNode root=BParser.parse("border{null;null;scroll{vertical='true';horizontal='false';varray{rescale='true';passthrough{id='id';text='42'}"+RailwayWaterFillUi.content(view)+"}};null;null;}");
        Map<String,BNode> fields=new HashMap<String,BNode>();collect(root,fields);
        check(root.getNodes().length==1&&root.getNodes()[0].getNodes().length==5,"native window border has five panes");return fields;
    }
    private static void collect(BNode node,Map<String,BNode> fields){String id=node.getAttribute("id");if(id!=null)check(fields.put(id,node)==null,"unique BML field "+id);for(BNode child:node.getNodes())collect(child,fields);}
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
}
