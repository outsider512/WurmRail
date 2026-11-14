import com.wurmonline.client.bml.*;
import java.util.*;
import org.wurmrail.*;

/** Captured supply identities, native client BML and balancing contract. */
public final class SteamSupplyUiSmoke {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        List<SteamSupplyUi.Choice> fuels=new ArrayList<SteamSupplyUi.Choice>(Arrays.asList(new SteamSupplyUi.Choice(101,"Coal hopper"),new SteamSupplyUi.Choice(201,"Wood';};button{id='injected';text='bad'};{,")));
        SteamSupplyUi.View view=new SteamSupplyUi.View(7,201,301,true,"NO WATER: refill tank.","Paid buffers: 50 energy, 0 litres",fuels,Arrays.asList(new SteamSupplyUi.Choice(301,"Water tank")));
        fuels.clear();Map<String,BNode> fields=parse(view,true);check(!fields.containsKey("injected")&&fields.get("steam_fuel_choice").getAttribute("default").equals("2"),"hostile storage names stay text and selected link uses captured identity");
        Properties answers=new Properties();answers.setProperty("steam_fuel_choice","1");answers.setProperty("steam_water_choice","1");answers.setProperty("fuel_id","999");answers.setProperty("revision","999");
        SteamSupplyUi.Selection selected=SteamSupplyUi.fromAnswers(view,answers);check(selected.fuelId==101&&selected.waterId==301&&selected.revision==7,"form indices resolve only to captured IDs and revisions");
        selected=SteamSupplyUi.fromAnswers(view,new Properties());check(selected.fuelId==201&&selected.waterId==301,"older captured forms preserve links");
        answers.setProperty("steam_fuel_choice","999");try{SteamSupplyUi.fromAnswers(view,answers);throw new AssertionError("forged source index");}catch(IllegalArgumentException expected){assertions++;}
        fields=parse(view,false);check(!fields.containsKey("steam_fuel_choice")&&!fields.containsKey("steam_water_choice"),"inspection has no supply editing controls");
        SteamSupplyUi.View pump=new SteamSupplyUi.View(1,101,0,false,"RUNNING","",Arrays.asList(new SteamSupplyUi.Choice(101,"Fuel")),Collections.<SteamSupplyUi.Choice>emptyList());fields=parse(pump,true);
        check(fields.containsKey("steam_fuel_choice")&&!fields.containsKey("steam_water_choice")&&SteamSupplyUi.content(pump,true).contains("natural source for boiler water"),"pump supplies are explicitly fuel-only");
        SteamSupplyConfig defaults=SteamSupplyConfig.defaults();check(defaults.woodEnergyPerKg==1&&defaults.charcoalEnergyPerKg==3&&defaults.coalEnergyPerKg==6&&defaults.drillWaterMillilitres==10000&&defaults.yarderWaterMillilitres==5000&&defaults.pumpEnergyPerLitre==.02,"default fuel grades and work costs match documented balance");
        check(defaults.fuelBufferMicro==60000000&&defaults.waterBufferMillilitres==100000&&defaults.linkMetres==16,"modest buffers and several-tile links match defaults");
        Properties balance=new Properties();balance.setProperty("steamCoalEnergyPerKg","9");balance.setProperty("steamFuelBufferEnergy","120");balance.setProperty("steamDrillWaterLitresPerAction","2.5");SteamSupplyConfig custom=SteamSupplyConfig.fromProperties(balance);
        check(custom.coalEnergyPerKg==9&&custom.fuelBufferMicro==120000000&&custom.drillWaterMillilitres==2500&&custom.woodEnergyPerKg==1,"individual configuration overrides preserve other defaults");
        balance.setProperty("steamCoalEnergyPerKg","NaN");try{SteamSupplyConfig.fromProperties(balance);throw new AssertionError("nonfinite config");}catch(IllegalArgumentException expected){assertions++;}
        System.out.println("PASS native steam supply BML: captured identities, fuel-only pump, access controls and configurable balance ("+assertions+" assertions)");
    }
    private static Map<String,BNode> parse(SteamSupplyUi.View v,boolean controls)throws Exception{BNode root=BParser.parse("border{null;null;scroll{vertical='true';horizontal='false';varray{rescale='true';"+SteamSupplyUi.content(v,controls)+"}};null;null;}");Map<String,BNode> fields=new HashMap<String,BNode>();collect(root,fields);return fields;}
    private static void collect(BNode n,Map<String,BNode> fields){String id=n.getAttribute("id");if(id!=null)check(fields.put(id,n)==null,"unique field "+id);for(BNode child:n.getNodes())collect(child,fields);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
