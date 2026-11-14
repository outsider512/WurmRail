import com.wurmonline.server.Items;
import com.wurmonline.server.items.Item;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.RailNetwork.*;

/** Old files and held trains remain harmless; no railway can produce water. */
public final class DeprecatedWaterFillRuntimeSmoke {
    private static int assertions;
    public static void run()throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998489104L,true,2);
        List<Item> created=new ArrayList<Item>();RailNetwork net=new RailNetwork();
        Path directory=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("deprecated-fill");Files.createDirectories(directory);
        try {
            List<String> ring=new ArrayList<String>();int[][] coords={{135,215},{136,215},{137,215},{138,215},{138,216},{138,217},{138,218},{137,218},{136,218},{135,218},{135,217},{135,216}};
            for(int i=0;i<coords.length;i++){Node n=net.addNode("retiredWater"+i,coords[i][0],coords[i][1],0);Item marker=WurmRailWorld.spawnTrackMarker(actor,n.worldX(),n.worldY(),0);created.add(marker);n.markerId=marker.getWurmId();ring.add(n.name);}
            for(int i=0;i<ring.size();i++)net.connect(ring.get(i),ring.get((i+1)%ring.size()));
            Node old=net.nodes.get(ring.get(2)),other=net.nodes.get(ring.get(8));net.setStation(old.name,true,1);net.setStation(other.name,true,1);
            Item marker=Items.getItem(old.markerId);marker.setName("Water: Old lake fill");
            Item engine=WurmRailWorld.spawnLocomotive(actor,old.worldX(),old.worldY(),0);created.add(engine);
            Train train=net.createTrain("RetiredWaterTrain",actor.getWurmId(),Collections.singletonList(engine.getWurmId()),old.name,ring.get(3));
            net.setRoute(train.name,Arrays.asList(old.name,other.name),1);train.serviceStopRail=old.name;train.serviceStopSerial=7;train.powered=false;
            List<String> itinerary=new ArrayList<String>(train.route);int index=train.routeIndex;
            WaterFillStops legacy=new WaterFillStops();WaterFillStops.Stop definition=new WaterFillStops.Stop(old.name,old.markerId,actor.getWurmId(),legacy.nextRevision(),"Old lake fill",old.tileX,old.tileY,0,100);
            legacy.put(definition);Path file=directory.resolve("waterfill.properties");legacy.save(file);byte[] original=Files.readAllBytes(file);
            WurmRailRuntime runtime=new WurmRailRuntime();set(runtime,"network",net);set(runtime,"stateFile",directory.resolve("network.properties"));
            call(runtime,"loadDeprecatedWaterFillStops");call(runtime,"retireDeprecatedWaterFillStops");
            check(train.serviceStopRail==null&&!train.powered,"retiring an old service hold preserves operator STOP");
            check(train.route.equals(itinerary)&&train.routeIndex==index&&net.nodes.size()==ring.size()&&old.links.size()==2,"old route order, target index, tracks and physical graph remain intact");
            check(Arrays.equals(original,Files.readAllBytes(file)),"legacy saved definitions are preserved without rewriting or truncation");
            check(marker.getName().contains("inactive"),"the physical old marker clearly reports deprecation");
            @SuppressWarnings("unchecked") Map<String,String> catalog=(Map<String,String>)call(runtime,"routeStopCatalog",new Class<?>[]{Train.class},train);
            check(catalog.get(old.name).contains("Inactive"),"existing selected legacy targets remain removable in route controls");
            net.setRoute(train.name,Collections.singletonList(other.name),1);
            catalog=(Map<String,String>)call(runtime,"routeStopCatalog",new Class<?>[]{Train.class},train);
            check(!catalog.containsKey(old.name),"retired fill stations are not offered as new route-stop choices");
            RailwayWaterFillService.World forbidden=new RailwayWaterFillService.World(){public String unavailableReason(Train t,WaterFillStops.Stop s){throw new AssertionError("obsolete service called world");}public RailwayWaterFillService.Transfer fill(Train t,WaterFillStops.Stop s,long amount){throw new AssertionError("obsolete railway generated water");}};
            RailwayWaterFillService inactive=new RailwayWaterFillService(net,()->legacy,forbidden);
            check(!inactive.shouldService(train,old),"legacy service never requests a rail arrival hold");inactive.serviceStops(30);
            check(inactive.activeVisits()==0,"legacy compatibility calls cannot run producer visits");
            RailwayWaterFillService.Transfer disabled=new RailwayWaterFillWorld(net).fill(train,definition,1000000);
            check(disabled.finished&&disabled.transferredMillilitres==0&&disabled.status.toLowerCase(Locale.ROOT).contains("deprecated"),"direct calls to obsolete natural-water railway filling cannot create water");
            // A saved hold may also survive with a missing or corrupt obsolete file.
            Files.write(file,"invalid legacy contents".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            train.serviceStopRail=old.name;train.serviceStopSerial=8;train.powered=true;
            call(runtime,"loadDeprecatedWaterFillStops");call(runtime,"retireDeprecatedWaterFillStops");
            check(train.serviceStopRail==null&&train.powered,"damaged obsolete files do not disable railways or strand an enabled train");
            net.stop(train.name);net.setRoute(train.name,itinerary,1);
            net.routeStopServices=(t,n)->false;net.resumeScheduled(train.name);int initial=train.routeIndex;
            for(int step=0;step<900&&train.routeIndex==initial;step++)net.tick(.1);
            check(train.serviceStopRail==null&&train.powered,"a retired waypoint cannot reintroduce an unhandled service hold");
            // Reusing the same physical rail as stored-water freight overrides retirement.
            WaterUnloadStops current=(WaterUnloadStops)get(runtime,"waterUnloadStops");
            current.put(new WaterUnloadStops.Stop(old.name,old.markerId,actor.getWurmId(),current.nextRevision(),"Stored water",WaterUnloadStops.Operation.LOAD,engine.getWurmId(),100));
            train.serviceStopRail=old.name;train.serviceStopSerial=9;call(runtime,"retireDeprecatedWaterFillStops");
            check(old.name.equals(train.serviceStopRail),"retirement cannot cancel a real replacement liquid freight visit");
            System.out.println("PASS native deprecated Water Fill retirement: preserved files/routes/graph, STOP and held arrivals, corrupt-file recovery, inactive route catalog and no railway generation ("+assertions+" assertions)");
        }finally{for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());}
    }
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object call(Object o,String name)throws Exception{return call(o,name,new Class<?>[0]);}
    private static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
