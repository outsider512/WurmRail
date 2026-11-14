import com.wurmonline.client.bml.BNode;
import com.wurmonline.client.bml.BParser;
import java.util.*;
import org.wurmrail.RailwayBulkHopper;
import org.wurmrail.RailwayBulkHopper.Row;
import org.wurmrail.RailwayBulkHopper.View;

/** Parse real hopper status output with the shipped client, including hostile item names. */
public final class BulkHopperUiSmoke {
    private static int assertions;
    private static final String HOSTILE="Ore';};button{id='injected';text='bad'};{\\\n";
    public static void main(String[] args) throws Exception {
        View empty=new View("Bulk hopper",0,100000000,0,64000000,0,
                Collections.<Row>emptyList(),Arrays.asList("iron ore","coal","rock shards"));
        Map<String,BNode> fields=parse(empty);
        check(fields.keySet().equals(new HashSet<String>(Arrays.asList("id","refresh","close"))),"inspection has only read-only refresh and close controls");
        check(RailwayBulkHopper.content(empty).contains("Remaining: 100,000.0 kg"),"empty hopper reports all weight capacity remaining");
        check(RailwayBulkHopper.content(empty).contains("64,000.0 litres"),"volume is shown in litres rather than native cubic centimetres");
        check(RailwayBulkHopper.content(empty).contains("Item stacks: 0 / 4096"),"real item stack capacity is visible");
        List<Row> resources=new ArrayList<Row>();resources.add(new Row(HOSTILE,2,500000));resources.add(new Row("coal",1,20000));
        List<String> allowed=new ArrayList<String>(Arrays.asList(HOSTILE,"coal","dirt","sand"));
        View mixed=new View(HOSTILE,520000,99480000,1000000,63000000,3,resources,allowed);
        resources.clear();allowed.clear();
        check(mixed.contents.size()==2&&mixed.allowed.size()==4,"snapshot rows and whitelist are independent immutable copies");
        check(!parse(mixed).containsKey("injected"),"names and resource labels cannot inject BML controls");
        String body=RailwayBulkHopper.content(mixed);
        check(body.contains("520.0 / 100,000.0 kg")&&body.contains("99,480.0 kg"),"current and remaining actual weight agree");
        check(body.contains("2 items, 500.0 kg")&&body.contains("coal: 1 items, 20.0 kg"),"grouped contents retain item counts and weights");
        View full=new View("Full hopper",100000000,0,64000000,0,4096,Arrays.asList(new Row("iron ore",4096,100000000)),Arrays.asList("iron ore"));
        parse(full);check(RailwayBulkHopper.content(full).contains("Remaining: 0.0 kg"),"full hopper reports zero remaining weight");
        List<Row> excessive=new ArrayList<Row>();for(int i=0;i<80;i++)excessive.add(new Row("Resource "+i,1,1));
        View bounded=new View("Diagnostic contents",80,99999920,80,63999920,80,excessive,Arrays.asList("iron ore"));
        parse(bounded);String output=RailwayBulkHopper.content(bounded);
        check(output.contains("Additional resource types")&&!output.contains("Resource 50:"),"diagnostic status stays bounded while native Open retains complete contents");
        System.out.println("PASS native bulk hopper status BML: empty/full weights and volumes, grouped contents, bounded output, hostile names and immutable read-only snapshots ("+assertions+" assertions)");
    }
    private static Map<String,BNode> parse(View view) throws Exception {
        BNode parsed=BParser.parse("border{null;null;scroll{vertical='true';horizontal='false';varray{rescale='true';passthrough{id='id';text='42'}"+RailwayBulkHopper.content(view)+"}};null;null;}");
        check(parsed.getNodes().length==1&&parsed.getNodes()[0].getNodes().length==5,"native border has all five panes");
        Map<String,BNode> fields=new HashMap<String,BNode>();collect(parsed,fields);return fields;
    }
    private static void collect(BNode node,Map<String,BNode> fields) {
        String id=node.getAttribute("id");if(id!=null)check(fields.put(id,node)==null,"unique field "+id);
        for(BNode child:node.getNodes())collect(child,fields);
    }
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
