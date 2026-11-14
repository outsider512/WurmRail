import com.wurmonline.client.bml.BNode;
import com.wurmonline.client.bml.BParser;
import java.util.*;
import org.wurmrail.RailwaySteamWaterPump;
import org.wurmrail.RailwaySteamWaterPump.*;

/** Real shipped-client parser plus captured object/source identities. */
public final class SteamWaterPumpUiSmoke {
    private static int assertions;private static final String HOSTILE="Tank,Water';};button{id='injected';text='bad'};{\\\n";
    public static void main(String[] args)throws Exception {
        List<TankChoice> tanks=new ArrayList<TankChoice>(Arrays.asList(new TankChoice(101,"Owned tank"),new TankChoice(902,HOSTILE)));
        List<SourceChoice> sources=new ArrayList<SourceChoice>(Arrays.asList(new SourceChoice(40,50,0,"Fresh water"),new SourceChoice(42,50,-1,HOSTILE)));
        View v=new View(55,7,HOSTILE,true,902,42,50,-1,HOSTILE,HOSTILE,HOSTILE,100,tanks,sources,true);tanks.clear();sources.clear();
        check(v.tanks.size()==2&&v.sources.size()==2,"source/tank choices are captured before asynchronous answers");
        boolean immutable=false;try{v.tanks.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"tank choices are immutable");immutable=false;try{v.sources.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"source choices are immutable");
        Map<String,BNode> fields=parse(v);check(!fields.containsKey("injected"),"hostile names and status cannot create controls");check("2".equals(fields.get("tank_choice").getAttribute("default"))&&"2".equals(fields.get("source_choice").getAttribute("default")),"saved tank/source identities choose captured dropdown indices");check(fields.get("source_choice").getAttribute("options").split(",",-1).length==3,"commas cannot fabricate native source identities");
        Properties answer=selection("start","1","2");answer.setProperty("id","999");answer.setProperty("tankId","999");answer.setProperty("revision","999");answer.setProperty("sourceX","999");Request r=RailwaySteamWaterPump.fromAnswers(v,answer);check(r.id==55&&r.revision==7&&r.tankId==101&&r.sourceX==42&&r.sourceY==50&&r.sourceLayer==-1&&r.action==ActionType.START,"start uses captured machine, revision, tank and source instead of forged IDs");
        r=RailwaySteamWaterPump.fromAnswers(v,selection("save","0","0"));check(r.action==ActionType.SAVE&&r.tankId==0&&r.sourceX==-1&&r.sourceY==-1,"save can unlink without creating water or starting the machine");
        Properties stop=selection("stop","999999","NaN");r=RailwaySteamWaterPump.fromAnswers(v,stop);check(r.action==ActionType.STOP&&r.id==55&&r.tankId==902&&r.sourceX==42,"STOP ignores unrelated stale configuration fields");
        for(String key:new String[]{"tank_choice","source_choice"})for(String bad:new String[]{"","-1","3","999999999999","NaN","1.5"}){Properties p=selection("start","1","1");p.setProperty(key,bad);reject(v,p);}
        Properties both=selection("start","1","1");both.setProperty("stop","true");reject(v,both);
        View readOnly=new View(55,8,"Other pump",false,0,-1,-1,0,"Stopped","None","None",100,Collections.<TankChoice>emptyList(),Collections.<SourceChoice>emptyList(),false);fields=parse(readOnly);check(!fields.containsKey("save")&&!fields.containsKey("start")&&!fields.containsKey("stop")&&!fields.containsKey("tank_choice"),"read-only views expose no mutation controls");reject(readOnly,selection("start","0","0"));reject(readOnly,stop);check(RailwaySteamWaterPump.fromAnswers(readOnly,answers("refresh")).action==ActionType.REFRESH,"read-only viewers may refresh status");
        String body=RailwaySteamWaterPump.content(v);check(body.contains("AUTOMATIC")&&body.contains("Linked tank:")&&body.contains("Natural source:")&&body.contains("litres per second"),"power, source, tank and rate are explicit");check(body.contains("resumes when storage becomes available")&&body.contains("Fuel consumption follows litres actually pumped")&&body.contains("No separate boiler-water tank"),"backpressure and testing requirements are clear");
        check(RailwaySteamWaterPump.fromAnswers(v,answers("close"))==null&&RailwaySteamWaterPump.fromAnswers(v,new Properties())==null,"closing changes no machine state");
        duplicate(v,true);duplicate(v,false);System.out.println("PASS native Steam Water Pump BML: immutable tank/source identities, replay context, STOP isolation, hostile labels, read-only access and automatic-wait status ("+assertions+" assertions)");
    }
    private static void duplicate(View v,boolean tank){boolean rejected=false;try{new View(55,1,"Bad",false,0,-1,-1,0,"Stopped","None","None",100,tank?Arrays.asList(new TankChoice(1,"A"),new TankChoice(1,"B")):v.tanks,tank?v.sources:Arrays.asList(new SourceChoice(1,1,0,"A"),new SourceChoice(1,1,0,"B")),true);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"duplicate captured identities are rejected");}
    private static Properties answers(String button){Properties p=new Properties();p.setProperty(button,"true");return p;}
    private static Properties selection(String button,String tank,String source){Properties p=answers(button);p.setProperty("tank_choice",tank);p.setProperty("source_choice",source);return p;}
    private static void reject(View v,Properties p){boolean rejected=false;try{RailwaySteamWaterPump.fromAnswers(v,p);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid or unauthorized answer is rejected");}
    private static Map<String,BNode> parse(View view)throws Exception{BNode root=BParser.parse("border{null;null;scroll{vertical='true';horizontal='false';varray{rescale='true';passthrough{id='id';text='42'}"+RailwaySteamWaterPump.content(view)+"}};null;null;}");Map<String,BNode> result=new HashMap<String,BNode>();walk(root,result);return result;}
    private static void walk(BNode node,Map<String,BNode> fields){String id=node.getAttribute("id");if(id!=null)check(fields.put(id,node)==null,"native field identities are unique");for(BNode child:node.getNodes())walk(child,fields);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
