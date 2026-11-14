import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.CreatureCommunicator;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.zones.VirtualZone;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.wurmrail.RailModelRegistry;
import org.wurmrail.RailwaySignalWorld;
import org.wurmrail.RailwaySignalWorld.DisplayState;
import org.wurmrail.WurmRailWorld;

/** Native signal persistence, aspect synchronization and safe scenery repair. */
public final class SignalWorldSmoke {
    private static int assertions;

    public static void run() throws Exception {
        Item track=WurmRailWorld.spawnTrackMarker(null,218*4+2,210*4+2,0);
        Item unrelated=WurmRailWorld.spawnTrackMarker(null,219*4+2,210*4+2,0);
        VolaTile tile=Zones.getOrCreateTile(218,210,true);
        Field watchers=VolaTile.class.getDeclaredField("watchers"); watchers.setAccessible(true);
        Object savedWatchers=watchers.get(tile);
        FixtureWatcher observer=allocate(FixtureWatcher.class);
        observer.communicator=new CreatureCommunicator(observer);
        Set<VirtualZone> nativeWatchers=new HashSet<VirtualZone>();
        nativeWatchers.add(new VirtualZone(observer,0,0,218,210,4,true)); watchers.set(tile,nativeWatchers);
        List<DisplayState> states=new ArrayList<DisplayState>();
        Item cave=null;
        try {
            for(int direction=0;direction<8;direction++) states.add(state(track,direction,false,null,"No route reserved"));
            RailwaySignalWorld.sync(states);
            require(signals().size()==8,"one persistent ground signal for each absolute incoming side");
            long[] ids=new long[8];
            for(Item signal:signals()) {
                int direction=signal.getAuxData()&7; ids[direction]=signal.getWurmId();
                require(signal.getData()==track.getWurmId()&&signal.getParentId()==-10L&&signal.getOwnerId()==-10L
                                &&signal.getRotation()==0&&!signal.isMovingItem(),"signal is fixed parent-linked ground scenery");
                require(signal.getModelName().equals("model.wurmrail.signal."+direction+".red.")
                                &&signal.getName().contains("RED")&&signal.getName().contains("No route reserved"),
                        "red model and hover diagnostic expose status without a menu");
                require(IntegrationSmoke.Checks.groundSpawns(signal.getWurmId())>0
                                &&signal.getModelName().equals(IntegrationSmoke.Checks.groundModel(signal.getWurmId())),
                        "existing native observer receives each red signal model");
            }
            int adds=IntegrationSmoke.Checks.groundSpawns(ids[0]),removes=IntegrationSmoke.Checks.groundRemovals(ids[0]);
            RailwaySignalWorld.sync(new ArrayList<DisplayState>(states));
            require(IntegrationSmoke.Checks.groundSpawns(ids[0])==adds&&IntegrationSmoke.Checks.groundRemovals(ids[0])==removes
                            &&Items.getItem(ids[0]).getWurmId()==ids[0],"unchanged loaded snapshots preserve native items without redundant packets");
            track.setPosXY(218*4+2.4f,210*4+2);
            RailwaySignalWorld.sync(states);
            require(Items.getItem(ids[0]).getPosX()==218*4+2&&track.getPosX()==218*4+2.4f,
                    "older off-center parent marker is not moved and the signal origin remains centered");

            states.clear();
            for(int direction=0;direction<8;direction++) states.add(state(track,direction,true,"Train 12",null));
            RailwaySignalWorld.sync(states);
            for(int direction=0;direction<8;direction++) {
                Item signal=Items.getItem(ids[direction]);
                require((signal.getAuxData()&255)==(direction|8)&&signal.getModelName().equals("model.wurmrail.signal."+direction+".green.")
                                &&signal.getName().contains("GREEN from ")&&signal.getName().contains("for Train 12"),"owner-specific green chooses the exact absolute approach model");
                require(IntegrationSmoke.Checks.groundSpawns(signal.getWurmId())>1
                                &&IntegrationSmoke.Checks.groundRemovals(signal.getWurmId())>0
                                &&signal.getModelName().equals(IntegrationSmoke.Checks.groundModel(signal.getWurmId())),
                        "stationary aspect changes reannounce the ground mesh to nearby native observers");
            }
            DisplayState ownerless=state(track,0,true,null,"Missing authority owner");
            require(!ownerless.green&&ownerless.aux()==0&&RailwaySignalWorld.displayName(ownerless).contains("RED"),
                    "ownerless permissive display snapshots fail closed to red");
            require(Items.getItem(ids[0]).getName().contains("from North")&&Items.getItem(ids[4]).getName().contains("from South"),
                    "opposite facing signal names use explicit independent absolute approaches");
            List<DisplayState> duplicatedDefinitions=new ArrayList<DisplayState>(states);
            duplicatedDefinitions.add(state(track,0,true,"Other train",null));
            RailwaySignalWorld.sync(duplicatedDefinitions);
            require(Items.getItem(ids[0]).getModelName().equals("model.wurmrail.signal.0.red.")
                            &&Items.getItem(ids[0]).getName().contains("Invalid duplicate"),
                    "ambiguous duplicated signal definitions remain visibly red rather than showing either owner's green");
            RailwaySignalWorld.sync(states);
            adds=IntegrationSmoke.Checks.groundSpawns(ids[0]); removes=IntegrationSmoke.Checks.groundRemovals(ids[0]);
            RailwaySignalWorld.sync(states);
            require(Items.getItem(ids[0]).getName().length()<=39&&IntegrationSmoke.Checks.groundSpawns(ids[0])==adds
                            &&IntegrationSmoke.Checks.groundRemovals(ids[0])==removes,
                    "bounded native signal names do not retrigger scenery packets on unchanged reconciliation");

            Item duplicate=WurmRailWorld.spawnSignal(track.getWurmId(),218*4+2,210*4+2,0,0);
            Item orphan=WurmRailWorld.spawnSignal(Long.MAX_VALUE,218*4+2,210*4+2,0,0);
            Item wrongParent=WurmRailWorld.spawnSignal(ids[0],218*4+2,210*4+2,0,1);
            unrelated.setData(track.getWurmId()); unrelated.setAuxData((byte)1);
            RailwaySignalWorld.sync(states);
            require(signals().size()==8&&!Items.getItemOptional(duplicate.getWurmId()).isPresent()
                            &&!Items.getItemOptional(orphan.getWurmId()).isPresent()&&!Items.getItemOptional(wrongParent.getWurmId()).isPresent(),
                    "duplicates and invalid parent references are removed deterministically");
            require(Items.getItemOptional(unrelated.getWurmId()).isPresent()&&Items.getItemOptional(track.getWurmId()).isPresent(),
                    "repair never deletes a parent marker or an item of another template");

            Item invalid=Items.getItem(ids[7]); invalid.setAuxData((byte)255);
            require(invalid.getModelName().equals("model.wurmrail.signal.0.red."),"invalid native aux bytes render fail-safe red");
            RailwaySignalWorld.sync(states);
            require((invalid.getAuxData()&255)==15&&invalid.getModelName().equals("model.wurmrail.signal.7.green."),
                    "valid core snapshot repairs invalid visual data by persistent parent and approach");
            for(int aux=0;aux<256;aux++) {
                String expected=aux>15?"model.wurmrail.signal.0.red.":"model.wurmrail.signal."+(aux&7)+"."+((aux&8)!=0?"green":"red")+".";
                require(expected.equals(RailModelRegistry.modelName(WurmRailWorld.signalTemplateId,(byte)aux,0)),
                        "all unsigned aux values map to a supported directional model or fail-safe red");
            }
            Items.destroyItem(ids[2]); RailwaySignalWorld.sync(states);
            require(signals().size()==8&&find(track.getWurmId(),2).getWurmId()!=ids[2],"missing native signal repairs without needing a saved signal item ID");
            Item displaced=find(track.getWurmId(),2); displaced.setPosXY(displaced.getPosX()+.25f,displaced.getPosY());
            RailwaySignalWorld.sync(states);
            require(signals().size()==8&&!Items.getItemOptional(displaced.getWurmId()).isPresent()
                            &&find(track.getWurmId(),2).getPosX()==218*4+2,
                    "misplaced signal is replaced at the absolute track origin");
            states.remove(3); RailwaySignalWorld.sync(states);
            require(signals().size()==7&&find(track.getWurmId(),3)==null,"removing one approach definition leaves the other approaches intact");
            Items.destroyItem(track.getWurmId()); RailwaySignalWorld.sync(states);
            require(signals().isEmpty(),"a missing native parent track cannot leave a permissive-looking signal behind");

            cave=WurmRailWorld.spawnTrackMarker(null,218*4+2,212*4+2,-1);
            DisplayState underground=new DisplayState("cave",cave.getWurmId(),218,212,-1,4,false,null,"Unreserved");
            RailwaySignalWorld.sync(Collections.singletonList(underground));
            require(signals().size()==1&&!signals().get(0).isOnSurface()
                            &&signals().get(0).getData()==cave.getWurmId()&&signals().get(0).getModelName().equals("model.wurmrail.signal.4.red."),
                    "underground signals preserve layer and absolute incoming side");
        } finally {
            watchers.set(tile,savedWatchers);
            RailwaySignalWorld.sync(Collections.<DisplayState>emptyList());
            if(Items.getItemOptional(track.getWurmId()).isPresent()) Items.destroyItem(track.getWurmId());
            if(Items.getItemOptional(unrelated.getWurmId()).isPresent()) Items.destroyItem(unrelated.getWurmId());
            if(cave!=null&&Items.getItemOptional(cave.getWurmId()).isPresent()) Items.destroyItem(cave.getWurmId());
        }
        System.out.println("PASS directional signal ground items, all 16 aspect models, owner names, native ground synchronization, reload, repair and cleanup ("+assertions+" assertions)");
    }

    private static DisplayState state(Item track,int direction,boolean green,String owner,String reason) {
        return new DisplayState("signal"+direction,track.getWurmId(),218,210,0,direction,green,owner,reason);
    }
    private static List<Item> signals() {
        List<Item> result=new ArrayList<Item>();
        for(Item item:Items.getAllItems()) if(WurmRailWorld.isSignal(item)) result.add(item);
        return result;
    }
    private static Item find(long parent,int direction) {
        for(Item item:signals()) if(item.getData()==parent&&(item.getAuxData()&7)==direction) return item;
        return null;
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
        assertions++; if(!success) throw new AssertionError(message);
    }
}
