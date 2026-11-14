import com.wurmonline.client.bml.BNode;
import com.wurmonline.client.bml.BParser;
import java.util.*;
import org.wurmrail.RailwayFreightUi;
import org.wurmrail.RailwayFreightUi.*;
import org.wurmrail.core.FreightStops.Operation;

/** Native freight BML parsing and adversarial captured form-answer checks. */
public final class RailwayFreightUiSmoke {
    private static int assertions;
    private static final String HOSTILE="Mine,Loader;\"'};button{id='injected';text='bad'};{\\\n";
    public static void main(String[] args) throws Exception {
        List<StorageChoice> stores=Arrays.asList(new StorageChoice(41,HOSTILE),new StorageChoice(42,"Ore chest"));
        View view=new View("rail_a",22,7,HOSTILE,Operation.UNLOAD,42,46,100,"Idle "+HOSTILE,stores,true);
        Map<String,BNode> bml=parse(view);
        check(!bml.containsKey("injected"),"all display strings are escaped for native grammar");
        check(bml.get("storage_choice").getAttribute("options").split(",",-1).length==3,"comma-bearing storage labels cannot alter native indices");
        check("2".equals(bml.get("storage_choice").getAttribute("default")),"selected storage uses captured stable identity");
        check(bml.containsKey("save")&&bml.containsKey("remove")&&bml.containsKey("confirm_remove"),"configured owner form exposes changes and confirmation");
        Properties save=save();
        Request request=RailwayFreightUi.fromAnswers(view,save);
        check(request.action==Action.SAVE&&request.storageId==41&&request.templateId==46&&request.operation==Operation.LOAD,"save decodes native indices into the captured IDs and exact filter");
        check(request.expectedRail.equals("rail_a")&&request.expectedMarkerId==22&&request.revision==7,"request retains captured revision and track identity");
        check(request.name.equals("Mine Loader")&&request.rateKgPerSecond==100,"name and rate decode");
        save.setProperty("expectedRail","forged");save.setProperty("expectedMarkerId","999");save.setProperty("storageId","999");save.setProperty("revision","999");
        request=RailwayFreightUi.fromAnswers(view,save);
        check(request.expectedMarkerId==22&&request.storageId==41&&request.revision==7&&request.expectedRail.equals("rail_a"),"forged identity inputs cannot replace captured server data");
        save.setProperty("filter_choice","0");save.setProperty("template_id","invalid");
        check(RailwayFreightUi.fromAnswers(view,save).templateId==0,"Any cargo ignores template field");
        save=save();
        for(String key:new String[]{"operation_choice","storage_choice","filter_choice"})for(String bad:new String[]{"", "-1", "999999999999", "999", "NaN"}) {
            Properties p=copy(save);p.setProperty(key,bad);reject(view,p,"invalid dropdown "+key+"="+bad);
        }
        Properties p=copy(save);p.setProperty("storage_choice","0");reject(view,p,"placeholder is not storage");
        for(String bad:new String[]{"NaN","Infinity","-Infinity","0","-1","10000.1","","bad"}) {
            p=copy(save);p.setProperty("transfer_rate",bad);reject(view,p,"invalid rate "+bad);
        }
        for(String rate:new String[]{"0.1","10000","1e2"}) {
            p=copy(save);p.setProperty("transfer_rate",rate);check(RailwayFreightUi.fromAnswers(view,p).rateKgPerSecond==Double.parseDouble(rate),"inclusive valid transfer rates");
        }
        for(String bad:new String[]{"0","-1","2147483648","bad",""}) {
            p=copy(save);p.setProperty("template_id",bad);reject(view,p,"invalid exact template");
        }
        for(String bad:new String[]{"", "   ", "line\nbreak", "012345678901234567890123456789012345678901234567890"}) {
            p=copy(save);p.setProperty("freight_name",bad);reject(view,p,"invalid name");
        }
        p=copy(save);p.setProperty("remove","true");p.setProperty("confirm_remove","true");reject(view,p,"multiple buttons");
        Properties remove=new Properties();remove.setProperty("remove","true");reject(view,remove,"unconfirmed removal");
        remove.setProperty("confirm_remove","true");check(RailwayFreightUi.fromAnswers(view,remove).action==Action.REMOVE,"confirmed removal retains captured identity");
        View readonly=new View("rail_a",22,7,HOSTILE,Operation.UNLOAD,42,46,100,"Idle",stores,false);
        bml=parse(readonly);check(!bml.containsKey("save")&&!bml.containsKey("remove")&&!bml.containsKey("freight_name"),"read-only form exposes no editing controls");
        reject(readonly,save,"read-only forged save");reject(readonly,remove,"read-only forged remove");
        p=new Properties();p.setProperty("refresh","true");check(RailwayFreightUi.fromAnswers(readonly,p).action==Action.REFRESH,"read-only refresh remains available");
        p=new Properties();p.setProperty("close","true");check(RailwayFreightUi.fromAnswers(readonly,p)==null,"close has no mutation");
        View absent=new View("rail_a",22,-1,"",Operation.LOAD,-10,0,100,"No freight stop",Collections.<StorageChoice>emptyList(),true);
        bml=parse(absent);check(!bml.containsKey("save")&&!bml.containsKey("remove")&&bml.containsKey("refresh"),"empty-storage setup cannot offer save or remove");
        reject(absent,save,"no captured storage");reject(absent,remove,"no saved stop");
        View missing=new View("rail_a",22,7,"Missing storage",Operation.LOAD,999,0,100,"Missing",stores,true);
        check("0".equals(parse(missing).get("storage_choice").getAttribute("default")),"unavailable associated storage never silently chooses another container");
        System.out.println("PASS native freight BML and captured answer decoding: "+assertions+" assertions");
    }
    private static Properties save() {Properties p=new Properties();p.setProperty("save","true");p.setProperty("freight_name","Mine Loader");p.setProperty("operation_choice","0");p.setProperty("storage_choice","1");p.setProperty("filter_choice","1");p.setProperty("template_id","46");p.setProperty("transfer_rate","100");return p;}
    private static Properties copy(Properties p) {Properties result=new Properties();result.putAll(p);return result;}
    private static void reject(View v,Properties p,String reason) {try {RailwayFreightUi.fromAnswers(v,p);throw new AssertionError("accepted "+reason);}catch(IllegalArgumentException expected){assertions++;}}
    private static Map<String,BNode> parse(View v) throws Exception {
        BNode root=BParser.parse("border{null;null;scroll{vertical='true';horizontal='false';varray{rescale='true';passthrough{id='id';text='42'}"+RailwayFreightUi.content(v)+"}};null;null;}");
        Map<String,BNode> fields=new HashMap<String,BNode>();collect(root,fields);
        check(root.getNodes().length==1&&root.getNodes()[0].getNodes().length==5,"native border has five panes");return fields;
    }
    private static void collect(BNode n,Map<String,BNode> fields) {String id=n.getAttribute("id");if(id!=null)check(fields.put(id,n)==null,"unique field ID "+id);for(BNode child:n.getNodes())collect(child,fields);}
    private static void check(boolean condition,String why) {assertions++;if(!condition)throw new AssertionError(why);}
}
