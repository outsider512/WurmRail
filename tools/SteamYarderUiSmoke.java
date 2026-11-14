import com.wurmonline.client.bml.BNode;
import com.wurmonline.client.bml.BParser;
import java.util.*;
import org.wurmrail.RailwaySteamYarder;
import org.wurmrail.RailwaySteamYarder.*;
import org.wurmrail.core.SteamYarders.HarvestAge;

/** Native BML and immutable choice identities; all native tree mutation remains outside this UI. */
public final class SteamYarderUiSmoke {
    private static int assertions;
    private static final String HOSTILE="Timber,Bunk';};button{id='injected';text='bad'};{\\\n";
    public static void main(String[] args)throws Exception {
        List<Choice> bunks=new ArrayList<Choice>(Arrays.asList(new Choice(101,"Mill bunk"),new Choice(902,HOSTILE)));
        List<Choice> species=new ArrayList<Choice>(Arrays.asList(new Choice(0,"Oak"),new Choice(7,HOSTILE),new Choice(23,"Birch")));
        View v=new View(55,7,HOSTILE,true,902,7,HarvestAge.VERY_OLD,HOSTILE,HOSTILE,HOSTILE,30,bunks,species,true);
        bunks.clear();species.clear();
        check(v.bunks.size()==2&&v.speciesChoices.size()==3,"captured bunk/species choices survive source mutation");
        try{v.bunks.clear();throw new AssertionError("mutable bunk choices");}catch(UnsupportedOperationException expected){assertions++;}
        try{v.speciesChoices.clear();throw new AssertionError("mutable species choices");}catch(UnsupportedOperationException expected){assertions++;}
        Map<String,BNode> fields=parse(v);
        check(!fields.containsKey("injected"),"names, woodlot, pending output and status cannot inject native controls");
        check(fields.get("bunk_choice").getAttribute("default").equals("2"),"linked bunk maps to its captured native identity");
        check(fields.get("species_choice").getAttribute("default").equals("1"),"native species ordinal maps through captured choices");
        check(fields.get("age_choice").getAttribute("default").equals("2"),"saved very-old threshold selects the matching harvest age");
        check(fields.get("bunk_choice").getAttribute("options").split(",",-1).length==3&&fields.get("species_choice").getAttribute("options").split(",",-1).length==3,"commas in labels cannot fabricate dropdown identities");
        String body=RailwaySteamYarder.content(v);
        check(body.contains("RUNNING")&&body.contains("Pending output:")&&body.contains("Woodlot:"),"running, managed area and pending native timber are visible");
        check(body.contains("30.0 seconds")&&body.contains("Trees grow through normal Wurm simulation")&&body.contains("Bare ground is not automatically planted"),"natural growth and bounded harvest cadence are explicit");
        Properties p=selection("start","1","2","3");p.setProperty("id","9999");p.setProperty("revision","9999");p.setProperty("bunkId","9999");p.setProperty("species","99");
        Request r=RailwaySteamYarder.fromAnswers(v,p);
        check(r.action==ActionType.START&&r.id==55&&r.revision==7&&r.bunkId==101&&r.species==23&&r.harvestAge==HarvestAge.OVERAGED,"start captures real yarder/revision/bunk/species/age rather than forged answer identities");
        p.setProperty("start","false");p.setProperty("save","true");r=RailwaySteamYarder.fromAnswers(v,p);
        check(r.action==ActionType.SAVE,"save changes configuration without implicitly starting forestry");
        p.setProperty("bunk_choice","0");check(RailwaySteamYarder.fromAnswers(v,p).bunkId==0,"save can explicitly unlink output without inventing storage");
        for(int i=0;i<HarvestAge.values().length;i++)check(RailwaySteamYarder.fromAnswers(v,selection("save","1","0",Integer.toString(i))).harvestAge==HarvestAge.values()[i],"all four age choices preserve their physical threshold identity");
        Properties stop=answers("stop");stop.setProperty("bunk_choice","999999");stop.setProperty("species_choice","999999");stop.setProperty("age_choice","NaN");
        r=RailwaySteamYarder.fromAnswers(v,stop);
        check(r.action==ActionType.STOP&&r.id==55&&r.revision==7&&r.bunkId==902&&r.species==7&&r.harvestAge==HarvestAge.VERY_OLD,"STOP ignores edited fields and preserves the captured revision for stale-request checks");
        Request replay=RailwaySteamYarder.fromAnswers(v,stop);
        check(replay.revision==r.revision&&replay.id==r.id,"replaying old answers cannot invent a newer control revision");
        r=RailwaySteamYarder.fromAnswers(v,answers("refresh"));check(r.action==ActionType.REFRESH&&r.revision==7&&r.id==55,"refresh retains its immutable context");
        check(RailwaySteamYarder.fromAnswers(v,answers("close"))==null&&RailwaySteamYarder.fromAnswers(v,new Properties())==null,"closing or submitting no action cannot mutate forestry");
        for(String key:new String[]{"bunk_choice","species_choice","age_choice"})for(String bad:new String[]{"","-1","999999999999","NaN","1.5","+999"}){Properties invalid=selection("start","1","0","0");invalid.setProperty(key,bad);reject(v,invalid,"invalid captured forestry index: "+key);}
        Properties invalid=selection("start","3","0","0");reject(v,invalid,"out-of-range bunk cannot be selected");
        invalid=selection("start","1","3","0");reject(v,invalid,"out-of-range species cannot be selected");
        invalid=selection("start","1","0","4");reject(v,invalid,"out-of-range harvest age cannot be selected");
        invalid=selection("start","1","0","0");invalid.setProperty("stop","true");reject(v,invalid,"ambiguous start/stop cannot mutate forestry");
        View readOnly=new View(55,8,"Other owner's yarder",false,0,0,HarvestAge.MATURE,"Stopped","81 managed centres","None",30,Collections.<Choice>emptyList(),v.speciesChoices,false);
        fields=parse(readOnly);check(!fields.containsKey("start")&&!fields.containsKey("save")&&!fields.containsKey("stop")&&!fields.containsKey("bunk_choice")&&!fields.containsKey("species_choice")&&!fields.containsKey("age_choice"),"read-only inspection hides every mutation control");
        reject(readOnly,selection("start","0","0","0"),"read-only start is rejected");reject(readOnly,selection("save","0","0","0"),"read-only save is rejected");reject(readOnly,stop,"read-only STOP is rejected");
        check(RailwaySteamYarder.fromAnswers(readOnly,answers("refresh")).action==ActionType.REFRESH,"read-only viewers may refresh status");
        View missing=new View(55,9,"Missing storage",false,999,0,HarvestAge.OLD,"Log Bunk no longer exists.","81 managed centres","None",30,Collections.<Choice>emptyList(),v.speciesChoices,true);
        fields=parse(missing);body=RailwaySteamYarder.content(missing);
        check(fields.get("bunk_choice").getAttribute("default").equals("0")&&body.contains("Place an owned, unlocked Log Bunk")&&body.contains("STOPPED"),"missing storage selects no linked bunk and clearly explains how to resume");
        reject(missing,selection("start","1","0","0"),"missing storage cannot be forged by index");
        duplicateChoices(v,true);duplicateChoices(v,false);
        check(RailwaySteamYarder.fromAnswers(v,selection("save","0","0","0")).species==0,"native species zero remains a valid captured tree choice");
        System.out.println("PASS native Steam Yarder BML: captured identities, all harvest ages, immutable views, stop isolation, read-only access and hostile labels ("+assertions+" assertions)");
    }
    private static void duplicateChoices(View source,boolean bunk){try{new View(55,1,"Bad",false,0,0,HarvestAge.MATURE,"Stopped","Woodlot","None",30,bunk?Arrays.asList(new Choice(1,"A"),new Choice(1,"B")):source.bunks,bunk?source.speciesChoices:Arrays.asList(new Choice(0,"A"),new Choice(0,"B")),true);throw new AssertionError("duplicate forestry identities");}catch(IllegalArgumentException expected){assertions++;}}
    private static Properties answers(String button){Properties p=new Properties();p.setProperty(button,"true");return p;}
    private static Properties selection(String button,String bunk,String species,String age){Properties p=answers(button);p.setProperty("bunk_choice",bunk);p.setProperty("species_choice",species);p.setProperty("age_choice",age);return p;}
    private static void reject(View v,Properties p,String why){try{RailwaySteamYarder.fromAnswers(v,p);throw new AssertionError(why);}catch(IllegalArgumentException expected){assertions++;}}
    private static Map<String,BNode> parse(View v)throws Exception {BNode root=BParser.parse("border{null;null;scroll{vertical='true';horizontal='false';varray{rescale='true';passthrough{id='id';text='42'}"+RailwaySteamYarder.content(v)+"}};null;null;}");Map<String,BNode> fields=new HashMap<String,BNode>();collect(root,fields);check(root.getNodes().length==1&&root.getNodes()[0].getNodes().length==5,"native BML border has five panes");return fields;}
    private static void collect(BNode node,Map<String,BNode> fields){String id=node.getAttribute("id");if(id!=null)check(fields.put(id,node)==null,"unique field "+id);for(BNode child:node.getNodes())collect(child,fields);}
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
}
