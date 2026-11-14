import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.CreatureCommunicator;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.zones.VirtualZone;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.wurmrail.RailwayJunctionWorld;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;

/** Native persistent indicator reconciliation and nearby ground-mesh update regression. */
public final class JunctionWorldSmoke {
    private static int assertions;

    public static void run() throws Exception {
        RailNetwork network = new RailNetwork();
        Node junction = network.addNode("physicalJunction",190,210,0);
        int[][] offsets = {{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
        for (int direction=0; direction<8; direction++) {
            Node neighbor=network.addNode("physicalSide"+direction,190+offsets[direction][0],210+offsets[direction][1],0);
            network.connect(junction.name,neighbor.name);
        }
        junction.station=true;
        for (Node node:network.nodes.values())
            node.markerId=WurmRailWorld.spawnTrackMarker(null,node.worldX(),node.worldY(),node.layer).getWurmId();
        Item originalTrack=Items.getItem(junction.markerId);
        VolaTile tile=Zones.getOrCreateTile(junction.tileX,junction.tileY,true);
        Field watchers=VolaTile.class.getDeclaredField("watchers"); watchers.setAccessible(true);
        Object originalWatchers=watchers.get(tile);
        FixtureWatcher observer=allocate(FixtureWatcher.class);
        observer.communicator=new CreatureCommunicator(observer);
        Set<VirtualZone> nativeWatchers=new HashSet<VirtualZone>();
        nativeWatchers.add(new VirtualZone(observer,0,0,junction.tileX,junction.tileY,4,true));
        watchers.set(tile,nativeWatchers);
        try {
            RailwayJunctionWorld.sync(network);
            List<Item> found=indicators();
            require(found.size()==1,"one indicator exists for an eight-way station junction; neighboring ends have none");
            Item indicator=found.get(0); long id=indicator.getWurmId();
            require(indicator.getData()==junction.markerId && indicator.getParentId()==-10L
                            &&indicator.getRotation()==0&&!indicator.isMovingItem(),
                    "indicator is fixed ground scenery with persistent parent identity and absolute heading");
            require("model.wurmrail.alignment.0.".equals(indicator.getModelName())
                            &&indicator.getName().contains("Not aligned"),"new multiway junction is visibly unconfigured");
            require(IntegrationSmoke.Checks.groundSpawns(id)>0&&indicator.getModelName().equals(IntegrationSmoke.Checks.groundModel(id)),
                    "nearby native watcher is sent the indicator ground model");
            int adds=IntegrationSmoke.Checks.groundSpawns(id), removes=IntegrationSmoke.Checks.groundRemovals(id);
            RailwayJunctionWorld.sync(network);
            require(indicators().size()==1&&indicators().get(0).getWurmId()==id
                            &&IntegrationSmoke.Checks.groundSpawns(id)==adds&&IntegrationSmoke.Checks.groundRemovals(id)==removes,
                    "unchanged reconciliation reuses the saved item without duplicate packets");
            originalTrack.setPosXY(junction.worldX()+.5f,junction.worldY());
            RailwayJunctionWorld.sync(network);
            require(indicators().size()==1&&indicators().get(0).getWurmId()==id
                            &&indicator.getPosX()==junction.worldX()&&originalTrack.getPosX()==junction.worldX()+.5f,
                    "valid older track marker within its tile keeps a centered indicator without moving the track");

            for (int mask=1; mask<256; mask++) {
                if (Integer.bitCount(mask)!=2) continue;
                network.setJunctionAlignment(junction.name,mask);
                adds=IntegrationSmoke.Checks.groundSpawns(id); removes=IntegrationSmoke.Checks.groundRemovals(id);
                RailwayJunctionWorld.sync(network);
                require(indicator.getWurmId()==id&&(indicator.getAuxData()&255)==mask
                                &&("model.wurmrail.alignment."+mask+".").equals(indicator.getModelName()),
                        "physical pair selects the exact unsigned native model: "+mask);
                require(IntegrationSmoke.Checks.groundSpawns(id)>adds&&IntegrationSmoke.Checks.groundRemovals(id)>removes
                                &&indicator.getModelName().equals(IntegrationSmoke.Checks.groundModel(id)),
                        "stationary pair change removes and re-adds the mesh for an existing watcher: "+mask);
            }
            require(indicator.getName().equals("Junction alignment: West \u2194 Northwest"),
                    "hover name exposes the physical two-way compass alignment");
            Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("junction-world.properties");
            network.save(state);
            RailNetwork reloaded=RailNetwork.load(state);
            RailwayJunctionWorld.sync(reloaded);
            require(indicators().size()==1&&indicators().get(0).getWurmId()==id
                            &&indicators().get(0).getData()==reloaded.nodes.get(junction.name).markerId,
                    "a newly loaded network recovers the same native saved indicator without cached item IDs");

            Item duplicate=WurmRailWorld.spawnJunctionIndicator(junction); duplicate.setData(junction.markerId);
            Item orphan=WurmRailWorld.spawnJunctionIndicator(junction); orphan.setData(Long.MAX_VALUE);
            Item wrongType=WurmRailWorld.spawnJunctionIndicator(junction); wrongType.setData(id);
            RailwayJunctionWorld.sync(network);
            require(indicators().size()==1&&indicators().get(0).getWurmId()==id
                            &&!Items.getItemOptional(duplicate.getWurmId()).isPresent()
                            &&!Items.getItemOptional(orphan.getWurmId()).isPresent()
                            &&!Items.getItemOptional(wrongType.getWurmId()).isPresent(),
                    "duplicates and stale parent references are removed without replacing the valid indicator");
            require(Items.getItemOptional(originalTrack.getWurmId()).isPresent(),
                    "indicator cleanup never deletes its real track marker");

            for (int direction=2;direction<8;direction++) network.disconnect(junction.name,"physicalSide"+direction);
            RailwayJunctionWorld.sync(network);
            require(indicators().isEmpty()&&!Items.getItemOptional(id).isPresent(),
                    "junction becoming a two-sided track removes its obsolete indicator");
            network.connect(junction.name,"physicalSide2");
            RailwayJunctionWorld.sync(network);
            require(indicators().size()==1&&(indicators().get(0).getAuxData()&255)==0,
                    "new third side resets the visible physical alignment");
            long repaired=indicators().get(0).getWurmId();
            Items.destroyItem(repaired);
            RailwayJunctionWorld.sync(network);
            require(indicators().size()==1&&indicators().get(0).getWurmId()!=repaired,
                    "missing native indicator is repaired by the next reconciliation");
            Item replacement=indicators().get(0);
            replacement.setPosXY(junction.worldX()+.25f,junction.worldY());
            RailwayJunctionWorld.sync(network);
            require(indicators().size()==1&&!Items.getItemOptional(replacement.getWurmId()).isPresent()
                            &&indicators().get(0).getPosX()==junction.worldX(),
                    "misplaced indicator is replaced at the curve origin rather than displaying a shifted active path");
            replacement=indicators().get(0);
            Items.destroyItem(junction.markerId);
            RailwayJunctionWorld.sync(network);
            require(indicators().isEmpty()&&!Items.getItemOptional(replacement.getWurmId()).isPresent(),
                    "missing parent track cannot leave a usable-looking orphan indicator");
            require(Items.getItemOptional(network.nodes.get("physicalSide0").markerId).isPresent(),
                    "repair and cleanup leave unrelated track items intact");

            Node underground=network.addNode("undergroundJunction",200,210,-1);
            for(int direction:new int[]{0,2,4}) {
                Node neighbor=network.addNode("undergroundSide"+direction,200+offsets[direction][0],210+offsets[direction][1],-1);
                network.connect(underground.name,neighbor.name);
                neighbor.markerId=WurmRailWorld.spawnTrackMarker(null,neighbor.worldX(),neighbor.worldY(),-1).getWurmId();
            }
            underground.markerId=WurmRailWorld.spawnTrackMarker(null,underground.worldX(),underground.worldY(),-1).getWurmId();
            network.setJunctionAlignment(underground.name,17);
            RailwayJunctionWorld.sync(network);
            require(indicators().size()==1&&!indicators().get(0).isOnSurface()
                            &&indicators().get(0).getData()==underground.markerId&&(indicators().get(0).getAuxData()&255)==17,
                    "cave junction receives the same absolute alignment at its own layer");
        } finally {
            watchers.set(tile,originalWatchers);
            RailwayJunctionWorld.sync(new RailNetwork());
            for(Node node:network.nodes.values())
                if(Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);
        }
        System.out.println("PASS physical junction saved indicators, 28 absolute pair models, native ground remove/add packets, reload, repair and cleanup ("
                +assertions+" assertions)");
    }

    private static List<Item> indicators() {
        List<Item> result=new ArrayList<Item>();
        for(Item item:Items.getAllItems())
            if(item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId)result.add(item);
        return result;
    }

    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws Exception {
        Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object unsafe=field.get(null);
        return (T)unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);
    }

    private static final class FixtureWatcher extends Creature {
        CreatureCommunicator communicator;
        private FixtureWatcher() throws Exception { super(); }
        @Override public boolean hasLink() { return true; }
        @Override public com.wurmonline.server.creatures.Communicator getCommunicator() { return communicator; }
    }

    private static void require(boolean success,String message) {
        assertions++; if(!success)throw new AssertionError(message);
    }
}
