import com.wurmonline.client.bml.BNode;
import com.wurmonline.client.bml.BParser;
import java.util.*;
import org.wurmrail.RailwaySteamRockDrill;
import org.wurmrail.RailwaySteamRockDrill.ActionType;
import org.wurmrail.RailwaySteamRockDrill.Request;
import org.wurmrail.RailwaySteamRockDrill.StorageChoice;
import org.wurmrail.RailwaySteamRockDrill.View;

/** Native BML parsing and captured item/face identity checks for drill controls. */
public final class SteamRockDrillUiSmoke {
    private static int assertions;
    private static final String HOSTILE="Drill';};button{id='injected';text='bad'};{\\\n";
    public static void main(String[] args) throws Exception {
        List<StorageChoice> hoppers=new ArrayList<StorageChoice>(Arrays.asList(new StorageChoice(101,"Mine hopper"),new StorageChoice(902,HOSTILE)));
        List<String> details=new ArrayList<String>(Arrays.asList("Iron vein","Rock","Copper vein","Open tunnel; no mine face"));
        View view=new View(55,7,HOSTILE,true,902,HOSTILE,9,7,0,30,
                "Waiting for output: "+HOSTILE,"Rock shards: 20.0 kg",hoppers,details,true);
        hoppers.clear();details.clear();
        check(view.hoppers.size()==2&&view.faceDetails.size()==4,"captured choices and face states survive source-list mutation");
        try {view.hoppers.clear();throw new AssertionError("mutable choices");}catch(UnsupportedOperationException expected){assertions++;}
        Map<String,BNode> fields=parse(view);
        check(!fields.containsKey("injected"),"drill, hopper and status names cannot inject native BML controls");
        check(fields.containsKey("hopper_choice")&&fields.get("hopper_choice").getAttribute("default").equals("2"),"the linked hopper maps to its captured dropdown identity");
        check(fields.containsKey("face_0")&&fields.containsKey("face_1")&&fields.containsKey("face_2")&&!fields.containsKey("face_3"),"only mineable cardinal faces have editable checkboxes");
        String body=RailwaySteamRockDrill.content(view);
        check(body.contains("RUNNING")&&body.contains("Selected mining face: North")&&body.contains("Enabled faces: North, West"),"status distinguishes selected and enabled cardinal faces");
        check(body.contains("West: Open tunnel")&&body.contains("currently skipped"),"depleted/unavailable enabled faces remain visible with a skipped explanation");
        check(body.contains("30.0 seconds")&&body.contains("Pending output: Rock shards: 20.0 kg"),"current interval and real retained output are visible");
        Properties selected=answers("start");selected.setProperty("hopper_choice","1");selected.setProperty("face_0","true");selected.setProperty("face_2","true");
        selected.setProperty("machine_id","9999");selected.setProperty("revision","9999");selected.setProperty("hopper_id","9999");
        Request request=RailwaySteamRockDrill.fromAnswers(view,selected);
        check(request.action==ActionType.START&&request.machineId==55&&request.revision==7&&request.hopperId==101&&request.enabledMask==5,
                "start captures actual machine/revision/hopper IDs and absolute N/S face bits instead of forged answer identities");
        selected.setProperty("start","false");selected.setProperty("save","true");
        check(RailwaySteamRockDrill.fromAnswers(view,selected).action==ActionType.SAVE,"save configures without implicitly starting");
        selected.setProperty("hopper_choice","0");
        check(RailwaySteamRockDrill.fromAnswers(view,selected).hopperId==0,"save can unlink output without inventing a hopper identity");
        Properties stop=answers("stop");stop.setProperty("hopper_choice","999999");stop.setProperty("face_3","true");
        request=RailwaySteamRockDrill.fromAnswers(view,stop);
        check(request.action==ActionType.STOP&&request.hopperId==902&&request.enabledMask==9,"STOP ignores stale or forged configuration selections");
        request=RailwaySteamRockDrill.fromAnswers(view,answers("refresh"));
        check(request.action==ActionType.REFRESH&&request.machineId==55&&request.revision==7,"refresh keeps immutable context");
        check(RailwaySteamRockDrill.fromAnswers(view,answers("close"))==null,"close has no machine mutation");
        Properties invalid=answers("start");invalid.setProperty("hopper_choice","1");invalid.setProperty("face_3","true");reject(view,invalid,"unavailable face cannot be forged");
        invalid=answers("start");invalid.setProperty("hopper_choice","99");reject(view,invalid,"hopper index outside the captured choices is rejected");
        invalid=answers("start");invalid.setProperty("hopper_choice","1");invalid.setProperty("face_0","yes");reject(view,invalid,"malformed checkbox values are rejected");
        invalid=answers("start");invalid.setProperty("stop","true");reject(view,invalid,"multiple button actions cannot be submitted together");
        View readOnly=new View(55,8,"Other owner's drill",false,0,"None",0,0,-1,30,"Stopped","None",Collections.<StorageChoice>emptyList(),Arrays.asList("Open tunnel","Open tunnel","Open tunnel","Open tunnel"),false);
        fields=parse(readOnly);check(!fields.containsKey("start")&&!fields.containsKey("save")&&!fields.containsKey("stop")&&!fields.containsKey("hopper_choice"),"read-only inspection does not expose editing fields");
        reject(readOnly,answers("stop"),"read-only snapshots reject forged STOP");
        check(RailwaySteamRockDrill.fromAnswers(readOnly,answers("refresh")).action==ActionType.REFRESH,"read-only inspection can refresh status");
        View unavailable=new View(55,9,"Drill",false,902,"Missing hopper",0,0,-1,30,"The linked hopper no longer exists.","None",Collections.<StorageChoice>emptyList(),Arrays.asList("No wall","No wall","No wall","No wall"),true);
        parse(unavailable);body=RailwaySteamRockDrill.content(unavailable);
        check(body.contains("No compatible nearby Bulk Hopper")&&body.contains("Selected mining face: None")&&body.contains("STOPPED"),"unavailable output/no-face state is clear without hiding STOP");
        System.out.println("PASS native Steam Rock Drill BML: cardinal availability, captured hopper/machine/revision identities, immutable views, stop isolation, read-only access and hostile names ("+assertions+" assertions)");
    }
    private static Properties answers(String button){Properties p=new Properties();p.setProperty(button,"true");return p;}
    private static void reject(View view,Properties answers,String message){try{RailwaySteamRockDrill.fromAnswers(view,answers);throw new AssertionError(message);}catch(IllegalArgumentException expected){assertions++;}}
    private static Map<String,BNode> parse(View view) throws Exception {
        BNode parsed=BParser.parse("border{null;null;scroll{vertical='true';horizontal='false';varray{rescale='true';passthrough{id='id';text='42'}"+RailwaySteamRockDrill.content(view)+"}};null;null;}");
        check(parsed.getNodes().length==1&&parsed.getNodes()[0].getNodes().length==5,"native border has all five panes");
        Map<String,BNode> fields=new HashMap<String,BNode>();collect(parsed,fields);return fields;
    }
    private static void collect(BNode node,Map<String,BNode> fields) {
        String id=node.getAttribute("id");if(id!=null)check(fields.put(id,node)==null,"unique field "+id);
        for(BNode child:node.getNodes())collect(child,fields);
    }
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
