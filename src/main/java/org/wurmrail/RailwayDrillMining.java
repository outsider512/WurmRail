package org.wurmrail;

import com.wurmonline.math.TilePos;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.GeneralUtilities;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.Servers;
import com.wurmonline.server.behaviours.CaveWallBehaviour;
import com.wurmonline.server.behaviours.TileRockBehaviour;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.skills.Skill;
import com.wurmonline.server.skills.SkillList;
import com.wurmonline.server.skills.SkillsFactory;
import com.wurmonline.server.villages.Village;
import com.wurmonline.server.villages.VillageRole;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Fixed-face native cave depletion with a real-item output buffer. Never opens a tunnel. */
public final class RailwayDrillMining {
    private static final Logger LOG=Logger.getLogger(RailwayDrillMining.class.getName());
    private static final long NO_ID=-10L;
    /** An output whose face depletion has not yet been confirmed must never be delivered twice. */
    private static final byte UNCONFIRMED_OUTPUT=99;
    private static final int[] DX={0,1,0,-1},DY={-1,0,1,0},WALL_SIDE={3,4,5,2};
    private RailwayDrillMining() { }

    public static final class FaceInfo {
        public final boolean available;
        public final String label,reason;
        public final int templateId;
        private final int x,y,tile,resource,state;
        private FaceInfo(int ordinal,int x,int y,int tile,int template,int resource,int state,String reason) {
            available=reason==null;label=resourceLabel(tile,template,x,y);this.reason=reason;templateId=template;
            this.x=x;this.y=y;this.tile=tile;this.resource=resource;this.state=state;
        }
    }
    public static final class MiningResult {
        public final boolean mined,depleted,retryable;
        public final String reason;
        public final long itemId;
        private MiningResult(boolean mined,boolean depleted,String reason,long itemId) {
            this(mined,depleted,reason,itemId,false);
        }
        private MiningResult(boolean mined,boolean depleted,String reason,long itemId,boolean retryable) {
            this.mined=mined;this.depleted=depleted;this.reason=reason;this.itemId=itemId;this.retryable=retryable;
        }
    }

    /** Bounded four-neighbour inspection. The durable owner ID also works while its player is offline. */
    public static FaceInfo inspect(Item drill,long owner,int ordinal) {
        if(ordinal<0||ordinal>3)throw new IllegalArgumentException("Choose North, East, South or West.");
        int x=drill==null?0:drill.getTileX()+DX[ordinal],y=drill==null?0:drill.getTileY()+DY[ordinal];
        String location=locationProblem(drill,owner);
        if(location!=null)return face(ordinal,x,y,0,0,0,0,location);
        if(x<0||y<0||x>=Zones.worldTileSizeX||y>=Zones.worldTileSizeY)
            return face(ordinal,x,y,0,0,0,0,"The adjacent face is outside the world.");
        try {
            int tile=Server.caveMesh.getTile(x,y);byte type=Tiles.decodeType(tile);
            if(type!=Tiles.Tile.TILE_CAVE_WALL.id&&type!=Tiles.Tile.TILE_CAVE_WALL_ROCKSALT.id&&!Tiles.isOreCave(type))
                return face(ordinal,x,y,tile,0,0,0,"No mineable natural wall; reinforced, clad and open tiles are excluded.");
            if(Zones.isTileProtected(x,y))return face(ordinal,x,y,tile,0,0,0,"The adjacent face is protected by the gods.");
            short height=Tiles.decodeHeight(tile);
            if(height< -25&&height!= -100)return face(ordinal,x,y,tile,0,0,0,"The adjacent face is too deep underwater to mine.");
            int template=Native.template(type),resource=Server.getCaveResource(x,y),state=Zones.getMiningState(x,y);
            if(!BulkHopperPolicy.allows(template))return face(ordinal,x,y,tile,template,resource,state,"This wall does not produce an approved bulk resource.");
            String denied=permissionProblem(owner,x,y,type);
            if(denied!=null)return face(ordinal,x,y,tile,template,resource,state,denied);
            if(CaveWallBehaviour.isCaveWallBlocked(TilePos.fromXY(x,y),WALL_SIDE[ordinal],0)!=null)
                return face(ordinal,x,y,tile,template,resource,state,"A wall or other structure blocks the drill's access to this face.");
            if(normal(template)) {
                if(state<0||state>=tunnellingLimit())return face(ordinal,x,y,tile,template,resource,state,
                        "This face has reached its safe excavation limit; manual tunnel advancement is required.");
            } else if(resource==0)return face(ordinal,x,y,tile,template,resource,state,"This vein is depleted; manual tunnel advancement is required.");
            return face(ordinal,x,y,tile,template,resource,state,null);
        } catch(Exception unavailable) {
            LOG.log(Level.WARNING,"Unable to inspect steam drill face at "+x+","+y,unavailable);
            return face(ordinal,x,y,0,0,0,0,"Native mining information or permissions are unavailable.");
        }
    }
    private static FaceInfo face(int ordinal,int x,int y,int tile,int template,int resource,int state,String reason) {
        return new FaceInfo(ordinal,x,y,tile,template,resource,state,reason);
    }
    private static String resourceLabel(int tile,int template,int x,int y) {
        try { return Tiles.getTile(Tiles.decodeType(tile)).getName()+" ("
                +ItemTemplateFactory.getInstance().getTemplate(template).getName()+") at "+x+", "+y; }
        catch(Exception unavailable) { return "No mineable resource at "+x+", "+y; }
    }
    public static String locationProblem(Item drill,long owner) {
        if(drill==null)return "The Steam Rock Drill no longer exists.";
        if(owner<=0||!drill.isOwner(owner))return "The drill's ownership has changed.";
        if(drill.isOnSurface()||drill.getFloorLevel()!=0||drill.getBridgeId()!=NO_ID)
            return "Place the Steam Rock Drill on a mine floor underground.";
        if(drill.getParentId()!=NO_ID||drill.getOwnerId()!=NO_ID||drill.isBanked()||drill.isMailed()||drill.isTraded())
            return "The drill must remain placed on the mine floor, outside inventories and trades.";
        if(Server.caveMesh==null||Server.resourceMesh==null)return "The native cave and resource maps are unavailable.";
        int x=drill.getTileX(),y=drill.getTileY();
        if(x<0||y<0||x>=Zones.worldTileSizeX||y>=Zones.worldTileSizeY)return "The drill is outside the world.";
        byte floor=Tiles.decodeType(Server.caveMesh.getTile(x,y));
        if(Tiles.isSolidCave(floor)||floor==Tiles.Tile.TILE_CAVE_EXIT.id||floor==Tiles.Tile.TILE_LAVA.id)
            return "Place the Steam Rock Drill on an open, dry mine floor.";
        if(Tiles.decodeHeight(Server.caveMesh.getTile(x,y))<0)return "Place the Steam Rock Drill above water on a dry mine floor.";
        return null;
    }
    private static String permissionProblem(long owner,int x,int y,byte type) {
        Village village=Zones.getVillage(x,y,false);
        if(village==null)return null;
        VillageRole role=village.getRoleFor(owner);
        if(role==null)return "The drill's owner has no established settlement mining permission.";
        VillageRole everybody=village.getRoleFor(NO_ID);
        boolean allowed=mayMine(role,type)||everybody!=null&&mayMine(everybody,type);
        return allowed?null:"The settlement does not allow the drill's owner to mine this face.";
    }
    private static boolean mayMine(VillageRole role,byte type) {
        return type==Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id?role.mayMineIronVeins():
                Tiles.isOreCave(type)?role.mayMineOtherVeins():role.mayMineRock();
    }
    private static boolean normal(int template) { return template==ItemList.rock||template==ItemList.rockSalt; }
    private static int tunnellingLimit() { return Math.max(1,Math.min(56,Servers.localServer.getTunnelingHits())); }

    /** One native-sized mining action, invoked only by the machine's timed server-thread scheduler. */
    public static MiningResult mine(Item drill,long owner,int ordinal,long hopperId) {
        return mine(drill,owner,ordinal,hopperId,SteamInputs.UNMETERED);
    }
    public static MiningResult mine(Item drill,long owner,int ordinal,long hopperId,SteamInputs.Work work) {
        long outputId=NO_ID;Item output=null;
        boolean depletionAttempted=false,committed=false,depleted=false;
        SteamInputs.Payment payment=null;
        try {
            String pending=flushBuffered(drill,owner,hopperId);
            if(pending!=null)return new MiningResult(false,false,pending,NO_ID,drill!=null&&drill.getItems().stream().noneMatch(i->i.getAuxData()==99));
            FaceInfo face=inspect(drill,owner,ordinal);
            if(!face.available)return new MiningResult(false,false,face.reason,NO_ID);
            Item hopper=Items.getItemOptional(hopperId).orElse(null);
            String problem=preflight(drill,owner,hopper,face.templateId);
            if(problem!=null)return new MiningResult(false,false,"OUTPUT FULL / NO OUTPUT STORAGE: "+problem,NO_ID,true);
            int resource=face.resource==65535?Server.rand.nextInt(10000):face.resource;
            if((face.templateId==ItemList.adamantineOre||face.templateId==ItemList.glimmerSteelOre)&&resource>50)resource=50;
            if(!normal(face.templateId)&&resource<=0) {
                depletionAttempted=true;
                Server.setCaveResource(face.x,face.y,0);
                return new MiningResult(false,true,"This vein is depleted; no resource was mined.",NO_ID);
            }
            payment=work.reserve(1);
            if(!payment.ready())return new MiningResult(false,false,payment.problem(),NO_ID,!payment.faulted());
            output=ItemFactory.createItem(face.templateId,quality(drill,face),(byte)0,"WurmRail Steam Rock Drill");
            outputId=output.getWurmId();output.setLastOwnerId(owner);output.setDataXY(face.x,face.y);
            output.setAuxData(UNCONFIRMED_OUTPUT);
            // Real safety inventory: an unexpected output failure must retain the exact item.
            try { drill.insertItem(output,true); }
            catch(RuntimeException notification) { if(!contains(drill,output))throw notification; }
            if(!contains(drill,output))throw new IllegalStateException("The native output could not be staged in the drill buffer.");
            FaceInfo current=inspect(drill,owner,ordinal);
            if(!current.available||current.tile!=face.tile||current.resource!=face.resource)throw new IllegalStateException("The mining face changed before the paid action committed.");
            String changed=MachineStorageLinks.problem(drill,owner,hopper);if(changed!=null)throw new IllegalStateException(changed);
            // These are the native CaveWallBehaviour resource/mining-state rules,
            // intentionally stopping before its createInsideTunnel branch.
            depletionAttempted=true;
            Server.setCaveResource(face.x,face.y,Math.max(0,resource-1));
            boolean coalExhausted=org.wurmrail.shared.CoalVeinTiles.isCoal(Tiles.decodeType(face.tile))&&resource<=1;
            if(coalExhausted) {
                // Exhaust the resource without opening an automated tunnel.
                Server.caveMesh.setTile(face.x,face.y,Tiles.encode(Tiles.decodeHeight(face.tile),Tiles.Tile.TILE_CAVE_WALL.id,Tiles.decodeData(face.tile)));
            }
            if(normal(face.templateId))Zones.setMiningState(face.x,face.y,(byte)(face.state+1),false);
            if(Server.getCaveResource(face.x,face.y)!=Math.max(0,resource-1)
                    ||coalExhausted&&Tiles.decodeType(Server.caveMesh.getTile(face.x,face.y))!=Tiles.Tile.TILE_CAVE_WALL.id
                    ||normal(face.templateId)&&Zones.getMiningState(face.x,face.y)!=face.state+1)
                throw new IllegalStateException("Native face depletion could not be confirmed; the output remains quarantined in the drill.");
            committed=true;
            depleted=normal(face.templateId)?face.state+1>=tunnellingLimit():resource<=1;
            payment.commit();
            output.setAuxData((byte)0);
            if(coalExhausted)try {
                Zones.getZone(face.x,face.y,false).changeTile(face.x,face.y);
                com.wurmonline.server.Players.getInstance().sendChangedTile(face.x,face.y,false,true);
            }catch(Exception notification){LOG.log(Level.WARNING,"Coal Vein depleted but its tile notification could not complete",notification);}
            String transfer=flushBuffered(drill,owner,hopperId);
            return new MiningResult(true,depleted,transfer,outputId,transfer!=null);
        } catch(Exception failure) {
            if(payment!=null&&payment.ready())try {
                if(committed)payment.commit();else if(depletionAttempted)payment.uncertain();else payment.refund();
            }catch(RuntimeException accounting){failure.addSuppressed(accounting);}
            if(!depletionAttempted&&output!=null) {
                String cleanup=cancelStagingArtifact(drill,output);
                LOG.log(cleanup==null?Level.WARNING:Level.SEVERE,"Steam Rock Drill staging failed before depletion: "+detail(failure)
                        +(cleanup==null?"; unearned staging item cancelled.":"; "+cleanup),failure);
                return new MiningResult(false,false,"Mining stopped before changing the face: "+detail(failure)
                        +(cleanup==null?" Unearned staging item cancelled.":" "+cleanup),cleanup==null?NO_ID:outputId);
            }
            String location=output==null?"":describeOutput(drill,output);
            LOG.log(Level.SEVERE,"Steam Rock Drill mining stopped"+location,failure);
            return new MiningResult(committed,depleted,"Mining stopped: "+detail(failure)+location,outputId);
        }
    }
    /** Before depletion, this is a factory staging artifact, not an earned mined resource. */
    private static String cancelStagingArtifact(Item drill,Item output) {
        try {
            if(drill.getItems().contains(output))drill.dropItem(output.getWurmId(),false);
            Items.destroyItem(output.getWurmId());
            if(Items.getItemOptional(output.getWurmId()).isPresent()||drill.getItems().contains(output))
                throw new IllegalStateException("Native staging item cleanup was not confirmed.");
            return null;
        } catch(Exception cleanup) {
            LOG.log(Level.SEVERE,"Failed to cancel an unearned drill staging item",cleanup);
            return "Staging cleanup failed; operator recovery is required."+describeOutput(drill,output);
        }
    }
    private static String describeOutput(Item drill,Item output) {
        Item current=Items.getItemOptional(output.getWurmId()).orElse(null);
        boolean linked=drill!=null&&drill.getItems().contains(output);
        if(current==null)return " Output item "+output.getWurmId()+" has no native item record; drill buffer link="+linked+". Operator inspection is required.";
        return " Output item "+output.getWurmId()+": native parent="+current.getParentId()+", drill buffer link="+linked+". Operator inspection is required.";
    }
    /** Recover a retained, real item before any subsequent face depletion. No output is discarded. */
    public static String flushBuffered(Item drill,long owner,long hopperId) {
        String location=locationProblem(drill,owner);if(location!=null)return location;
        if(drill.getItems().isEmpty())return null;
        if(drill.getItems().size()!=1)return "The drill output buffer needs inspection before mining can resume.";
        Item output=drill.getItems().iterator().next();
        if(!contains(drill,output)||!BulkHopperPolicy.eligible(output))return "The drill output buffer contains an invalid item; unload it before mining.";
        if(output.getAuxData()==UNCONFIRMED_OUTPUT)
            return "Output retained in drill: face depletion for item "+output.getWurmId()+" is unconfirmed; operator inspection is required before restarting.";
        Item hopper=Items.getItemOptional(hopperId).orElse(null);
        String problem=MachineStorageLinks.problem(drill,owner,hopper);if(problem!=null)return "Output retained in drill: "+problem;
        problem=BulkHopperPolicy.insertionProblem(hopper,output);if(problem!=null)return "Output retained in drill: "+problem;
        if(!hopper.canHold(output)||!hopper.mayCreatureInsertItem()||!hopper.testInsertItem(output))
            return "Output retained in drill: the linked hopper cannot accept this resource.";
        try { hopper.insertItem(output,false,true); }
        catch(RuntimeException failure) {
            if(contains(hopper,output)&&!drill.getItems().contains(output))return null;
            restoreBuffer(drill,hopper,output);LOG.log(Level.WARNING,"Drill output retained for a later retry",failure);
            return "Output retained in drill: native hopper insertion failed. Check the linked hopper before restarting.";
        }
        if(contains(hopper,output)&&!drill.getItems().contains(output))return null;
        restoreBuffer(drill,hopper,output);
        return "Output retained in drill: the linked hopper refused the resource.";
    }
    private static boolean contains(Item parent,Item child) { return child.getParentId()==parent.getWurmId()&&parent.getItems().contains(child); }
    private static void restoreBuffer(Item drill,Item hopper,Item output) {
        if(contains(drill,output)&&!hopper.getItems().contains(output))return;
        try {
            if(hopper.getItems().contains(output))hopper.dropItem(output.getWurmId(),false);
            drill.insertItem(output,true);
            if(!contains(drill,output)||hopper.getItems().contains(output))throw new IllegalStateException("Native output links could not be restored.");
        } catch(Exception failure) { throw new IllegalStateException("Output item "+output.getWurmId()+" requires operator recovery; mining is stopped.",failure); }
    }
    private static String preflight(Item drill,long owner,Item hopper,int templateId) throws Exception {
        String problem=MachineStorageLinks.problem(drill,owner,hopper);if(problem!=null)return problem;
        if(!drill.isHollow()||!drill.getItems().isEmpty())return "The drill output buffer is unavailable.";
        if(!BulkHopperPolicy.allows(templateId))return "The linked hopper does not accept this resource.";
        if(!BulkHopperPolicy.hasItemSlot(hopper))return "The Bulk Hopper's item limit has been reached.";
        ItemTemplate template=ItemTemplateFactory.getInstance().getTemplate(templateId);
        if(BulkHopperPolicy.remainingWeightGrams(hopper)<template.getWeightGrams())return "The Bulk Hopper's weight capacity has been reached.";
        if(BulkHopperPolicy.remainingVolume(hopper)<template.getVolume())return "The Bulk Hopper's volume capacity has been reached.";
        for(Item item:hopper.getItems())if(item.getParentId()!=hopper.getWurmId()||!BulkHopperPolicy.eligible(item))
            return "The linked hopper contains invalid resource links; inspect it before mining.";
        return null;
    }
    private static float quality(Item drill,FaceInfo face) throws Exception {
        double knowledge=Math.max(1,Math.min(100,drill.getCurrentQualityLevel()));
        Skill mining=SkillsFactory.createSkills("WurmRail Steam Rock Drill").learn(SkillList.MINING,(float)knowledge);
        double power=Math.max(1,Math.min(knowledge,mining.skillCheck(Native.difficulty(Tiles.decodeType(face.tile)),0,true,1f)));
        Random vein=new Random((long)(face.x+face.y*Zones.worldTileSizeY)*789221L);
        int cap=normal(face.templateId)||face.templateId==ItemList.ironOre?100:Native.maximumQuality();
        int veinCap=Math.min(cap,20+vein.nextInt(80));if(face.state== -1)veinCap=99;
        return GeneralUtilities.calcOreRareQuality(Math.min(power,veinCap),0,0);
    }
    private static String detail(Exception failure) { return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(); }
    /** Loaded only after native templates and maps exist, never during mod discovery. */
    private static final class Native {
        static int template(byte type) throws Exception { return ((Integer)method("getItemTemplateForTile").invoke(null,type)).intValue(); }
        static int difficulty(byte type) throws Exception { return ((Integer)method("getDifficultyForTile").invoke(null,type)).intValue(); }
        static int maximumQuality() throws Exception { Field field=TileRockBehaviour.class.getDeclaredField("MAX_QL");field.setAccessible(true);return field.getInt(null); }
        private static Method method(String name) throws Exception { Method method=TileRockBehaviour.class.getDeclaredMethod(name,byte.class);method.setAccessible(true);return method; }
    }
}
