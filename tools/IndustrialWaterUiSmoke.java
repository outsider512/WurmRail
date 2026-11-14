import com.wurmonline.client.bml.BNode;
import com.wurmonline.client.bml.BParser;
import java.util.*;
import org.wurmrail.RailwayIndustrialWaterTank;
import org.wurmrail.RailwayWaterUnloadUi;
import org.wurmrail.RailwayWaterUnloadUi.*;

/** Native status/unload grammar and captured destination identities; no inventory mutation. */
public final class IndustrialWaterUiSmoke {
    private static int assertions;
    private static final String HOSTILE="Water,Tank';};button{id='injected';text='bad'};{\\\n";
    public static void main(String[] args)throws Exception {
        statusWindows();unloadWindows();
        System.out.println("PASS native Industrial Water Tank and Water Unload Stop BML: real capacity/status, immutable captured tank identities, rates, confirmation, read-only access and hostile names ("+assertions+" assertions)");
    }
    private static void statusWindows()throws Exception {
        RailwayIndustrialWaterTank.View empty=new RailwayIndustrialWaterTank.View("Industrial water tank",0,100000000,"Empty","Ready");
        Map<String,BNode> fields=parse(RailwayIndustrialWaterTank.content(empty));
        check(fields.keySet().equals(new HashSet<String>(Arrays.asList("id","refresh","close"))),"tank inspection exposes only refresh and close");
        String body=RailwayIndustrialWaterTank.content(empty);
        check(body.contains("Water: 0.0 / 100,000.0 litres")&&body.contains("Remaining capacity: 100,000.0 litres"),"empty tank reports native capacity in litres");
        RailwayIndustrialWaterTank.View partial=new RailwayIndustrialWaterTank.View(HOSTILE,25000000,100000000,HOSTILE,"Ready "+HOSTILE);
        fields=parse(RailwayIndustrialWaterTank.content(partial));check(!fields.containsKey("injected"),"tank name, liquid label and status cannot inject BML");
        body=RailwayIndustrialWaterTank.content(partial);
        check(body.contains("25,000.0 / 100,000.0 litres")&&body.contains("Remaining capacity: 75,000.0 litres"),"partial native water quantity and remaining capacity agree");
        RailwayIndustrialWaterTank.View full=new RailwayIndustrialWaterTank.View("Full tank",100000000,100000000,"Fresh water","Ready");
        parse(RailwayIndustrialWaterTank.content(full));check(RailwayIndustrialWaterTank.content(full).contains("Remaining capacity: 0.0 litres"),"full tank shows no remaining capacity");
        RailwayIndustrialWaterTank.View over=new RailwayIndustrialWaterTank.View("Old invalid tank",100000001,100000000,"Water","Inspect native contents");
        parse(RailwayIndustrialWaterTank.content(over));check(RailwayIndustrialWaterTank.content(over).contains("Remaining capacity: 0.0 litres"),"over-capacity diagnostic never claims negative free water");
        check(body.contains("Water requirements for drills and locomotives remain disabled"),"status does not imply existing equipment now requires water");
        try{new RailwayIndustrialWaterTank.View("bad",-1,1000,"","");throw new AssertionError("negative quantity");}catch(IllegalArgumentException expected){assertions++;}
        try{new RailwayIndustrialWaterTank.View("bad",0,0,"","");throw new AssertionError("zero capacity");}catch(IllegalArgumentException expected){assertions++;}
        RailwayIndustrialWaterTank.View fault=new RailwayIndustrialWaterTank.View("Faulted empty tank",0,100000000,"Empty","Recovery stop",true,true);
        check(parse(RailwayIndustrialWaterTank.content(fault)).containsKey("recover"),"only an empty faulted tank exposes recovery");
        Properties recover=new Properties();recover.setProperty("recover","true");
        check(RailwayIndustrialWaterTank.fromAnswers(fault,recover)==RailwayIndustrialWaterTank.StatusAction.RECOVER,"empty fault recovery decodes an explicit captured action");
        check(!parse(RailwayIndustrialWaterTank.content(empty)).containsKey("recover"),"ordinary empty tank has no unnecessary recovery control");
        try{RailwayIndustrialWaterTank.fromAnswers(empty,recover);throw new AssertionError("forged recovery accepted");}catch(IllegalArgumentException expected){assertions++;}
        RailwayIndustrialWaterTank.View retained=new RailwayIndustrialWaterTank.View("Retained water",1000,100000000,"Water","Recovery stop",true,false);
        check(!parse(RailwayIndustrialWaterTank.content(retained)).containsKey("recover")&&RailwayIndustrialWaterTank.content(retained).contains("Empty and inspect"),"a faulted tank retaining water explains manual inspection without a delete/recover shortcut");
        try{RailwayIndustrialWaterTank.fromAnswers(retained,recover);throw new AssertionError("nonempty recovery accepted");}catch(IllegalArgumentException expected){assertions++;}
        Properties multiple=new Properties();multiple.setProperty("recover","true");multiple.setProperty("refresh","true");
        try{RailwayIndustrialWaterTank.fromAnswers(fault,multiple);throw new AssertionError("ambiguous recovery accepted");}catch(IllegalArgumentException expected){assertions++;}
        Properties refresh=new Properties();refresh.setProperty("refresh","true");
        check(RailwayIndustrialWaterTank.fromAnswers(fault,refresh)==RailwayIndustrialWaterTank.StatusAction.REFRESH,"fault status may refresh without mutation");
        Properties close=new Properties();close.setProperty("close","true");
        check(RailwayIndustrialWaterTank.fromAnswers(fault,close)==null,"closing fault status cannot clear quarantine");
        final int[] defaultCalls={0};RailwayIndustrialWaterTank.Api legacy=(actor,id)->defaultCalls[0]++;
        legacy.recover(null,1);check(defaultCalls[0]==1,"legacy open-only callbacks remain source-compatible and read-only");
    }
    private static void unloadWindows()throws Exception {
        List<StorageChoice> original=new ArrayList<StorageChoice>(Arrays.asList(new StorageChoice(101,HOSTILE),new StorageChoice(202,"Foundry tank")));
        View view=new View("unload_rail",55,7,HOSTILE,202,100,"Ready "+HOSTILE,original,true);
        original.clear();check(view.storages.size()==2,"captured tank choices survive source-list mutation");
        try{view.storages.clear();throw new AssertionError("mutable choices");}catch(UnsupportedOperationException expected){assertions++;}
        Map<String,BNode> fields=parse(RailwayWaterUnloadUi.content(view));
        check(!fields.containsKey("injected"),"stop, tank and status names cannot inject native controls");
        check(fields.get("storage_choice").getAttribute("options").split(",",-1).length==3,"commas in tank names cannot change captured dropdown choices");
        check(fields.get("storage_choice").getAttribute("default").equals("2"),"linked native tank selects its captured identity");
        check(fields.get("operation_choice").getAttribute("default").equals("1"),"legacy unload views retain unloading as their selected operation");
        check(fields.containsKey("save")&&fields.containsKey("remove")&&fields.containsKey("confirm_remove"),"configured owner form has explicit save and confirmed removal");
        Properties save=save();Request request=RailwayWaterUnloadUi.fromAnswers(view,save);
        check(request.action==Action.SAVE&&request.storageId==101&&request.expectedRail.equals("unload_rail")&&request.expectedMarkerId==55&&request.revision==7,"save captures actual station and native destination identities");
        check(request.name.equals("Foundry Water")&&request.rateLitresPerSecond==100,"trimmed station name and litre rate decode");
        check(request.operation==org.wurmrail.core.WaterUnloadStops.Operation.UNLOAD,"legacy answers preserve their captured unloading operation");
        Properties loading=copy(save);loading.setProperty("operation_choice","0");
        check(RailwayWaterUnloadUi.fromAnswers(view,loading).operation==org.wurmrail.core.WaterUnloadStops.Operation.LOAD,"stored-water loading is selected explicitly");
        for(String bad:new String[]{"","-1","2","NaN","0.5"}){Properties p=copy(save);p.setProperty("operation_choice",bad);reject(view,p,"invalid liquid freight operation");}
        save.setProperty("storageId","9999");save.setProperty("expectedRail","forged");save.setProperty("expectedMarkerId","9999");save.setProperty("revision","9999");
        request=RailwayWaterUnloadUi.fromAnswers(view,save);
        check(request.storageId==101&&request.expectedRail.equals("unload_rail")&&request.expectedMarkerId==55&&request.revision==7,"forged answer IDs cannot replace captured destination/marker/revision");
        for(String bad:new String[]{"","-1","0","3","999999999999","NaN","1.5"}){Properties p=copy(save);p.setProperty("storage_choice",bad);reject(view,p,"invalid tank index");}
        for(String bad:new String[]{"","bad","NaN","Infinity","-Infinity","0","-1","10000.1"}){Properties p=copy(save);p.setProperty("unload_rate",bad);reject(view,p,"invalid litre rate");}
        for(String valid:new String[]{"0.1","10000","1e2"}){Properties p=copy(save);p.setProperty("unload_rate",valid);check(RailwayWaterUnloadUi.fromAnswers(view,p).rateLitresPerSecond==Double.parseDouble(valid),"inclusive valid transfer rate");}
        for(String bad:new String[]{"","  ","two\nlines","bad\tname",String.join("",Collections.nCopies(49,"x"))}){Properties p=copy(save);p.setProperty("unload_name",bad);reject(view,p,"invalid stop name");}
        Properties multiple=copy(save);multiple.setProperty("remove","true");multiple.setProperty("confirm_remove","true");reject(view,multiple,"multiple mutation buttons");
        Properties remove=new Properties();remove.setProperty("remove","true");reject(view,remove,"unconfirmed removal");
        remove.setProperty("confirm_remove","true");remove.setProperty("storage_choice","999999");remove.setProperty("unload_rate","NaN");remove.setProperty("operation_choice","999999");
        Request removal=RailwayWaterUnloadUi.fromAnswers(view,remove);
        check(removal.action==Action.REMOVE&&removal.storageId==202&&removal.expectedMarkerId==55,"removal ignores edited destination fields and keeps captured identity");
        View readonly=new View("unload_rail",55,7,HOSTILE,202,100,"Ready",view.storages,false);
        fields=parse(RailwayWaterUnloadUi.content(readonly));check(!fields.containsKey("save")&&!fields.containsKey("remove")&&!fields.containsKey("storage_choice"),"read-only view hides mutation controls");
        reject(readonly,save,"forged read-only save");reject(readonly,remove,"forged read-only removal");
        Properties refresh=new Properties();refresh.setProperty("refresh","true");check(RailwayWaterUnloadUi.fromAnswers(readonly,refresh).action==Action.REFRESH,"read-only refresh remains allowed");
        Properties close=new Properties();close.setProperty("close","true");check(RailwayWaterUnloadUi.fromAnswers(readonly,close)==null,"close does not mutate station settings");
        View absent=new View("unload_rail",55,-1,"",0,100,"No tank",Collections.<StorageChoice>emptyList(),true);
        fields=parse(RailwayWaterUnloadUi.content(absent));check(!fields.containsKey("remove")&&RailwayWaterUnloadUi.content(absent).contains("Place an owned, unlocked Industrial Water Tank"),"unconfigured station explains missing storage without a remove action");
        reject(absent,save,"cannot invent missing tank");reject(absent,remove,"cannot remove nonexistent definition");
        View missing=new View("unload_rail",55,7,"Missing tank",999,100,"Missing",view.storages,true);
        check(parse(RailwayWaterUnloadUi.content(missing)).get("storage_choice").getAttribute("default").equals("0"),"unavailable saved tank never silently selects another destination");
        try{new View("unload_rail",55,7,"duplicate",101,100,"",Arrays.asList(new StorageChoice(101,"A"),new StorageChoice(101,"B")),true);throw new AssertionError("duplicate tank choices");}catch(IllegalArgumentException expected){assertions++;}
    }
    private static Properties save(){Properties p=new Properties();p.setProperty("save","true");p.setProperty("unload_name","  Foundry Water  ");p.setProperty("storage_choice","1");p.setProperty("unload_rate","100");return p;}
    private static Properties copy(Properties source){Properties p=new Properties();p.putAll(source);return p;}
    private static void reject(View view,Properties p,String why){try{RailwayWaterUnloadUi.fromAnswers(view,p);throw new AssertionError(why);}catch(IllegalArgumentException expected){assertions++;}}
    private static Map<String,BNode> parse(String body)throws Exception {
        BNode root=BParser.parse("border{null;null;scroll{vertical='true';horizontal='false';varray{rescale='true';passthrough{id='id';text='42'}"+body+"}};null;null;}");
        Map<String,BNode> fields=new HashMap<String,BNode>();collect(root,fields);check(root.getNodes().length==1&&root.getNodes()[0].getNodes().length==5,"native BML border has five panes");return fields;
    }
    private static void collect(BNode node,Map<String,BNode> fields){String id=node.getAttribute("id");if(id!=null)check(fields.put(id,node)==null,"unique field "+id);for(BNode child:node.getNodes())collect(child,fields);}
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
}
