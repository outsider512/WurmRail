package org.wurmrail;

import com.wurmonline.mesh.FoliageAge;
import com.wurmonline.mesh.GrassData;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.mesh.TreeData;
import com.wurmonline.server.Items;
import com.wurmonline.server.Players;
import com.wurmonline.server.Server;
import com.wurmonline.server.Servers;
import com.wurmonline.server.behaviours.Terraforming;
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
import com.wurmonline.server.villages.Villages;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.wurmrail.core.SteamYarders;

/** Native trees and real timber; invoked only on the authoritative server thread. */
public final class RailwayYarderWorld {
    private static final Logger LOG=Logger.getLogger(RailwayYarderWorld.class.getName());
    private static final long NO_ID=-10L;
    private static final byte UNCONFIRMED_OUTPUT=99;
    private RailwayYarderWorld() { }

    public static final class Species {
        public final int id;
        public final String label;
        private Species(TreeData.TreeType tree) { id=tree.getTypeId();label=tree.getName(); }
    }
    public static List<Species> speciesChoices() {
        List<Species> result=new ArrayList<Species>();
        for(TreeData.TreeType tree:TreeData.TreeType.values())result.add(new Species(tree));
        return Collections.unmodifiableList(result);
    }
    public static final class TileInfo {
        public final boolean available;
        public final String label,reason;
        public final int age,templateId,weightGrams;
        private final int x,y,tile;
        private final TreeData.TreeType tree;
        private TileInfo(int x,int y,int tile,TreeData.TreeType tree,int age,int template,int weight,String reason) {
            this.x=x;this.y=y;this.tile=tile;this.tree=tree;this.age=age;templateId=template;weightGrams=weight;
            this.reason=reason;available=reason==null;
            label=(tree==null?"No harvestable tree":FoliageAge.values()[age].getAgeName()+" "+tree.getName())+" at "+x+", "+y;
        }
    }
    public static final class HarvestResult {
        public final boolean harvested,retryable;
        public final String reason;
        public final long itemId;
        private HarvestResult(boolean harvested,String reason,long itemId) {
            this(harvested,reason,itemId,false);
        }
        private HarvestResult(boolean harvested,String reason,long itemId,boolean retryable) {
            this.harvested=harvested;this.reason=reason;this.itemId=itemId;this.retryable=retryable;
        }
    }
    public static String locationProblem(Item machine,long owner) {
        if(machine==null)return "The Steam Yarder no longer exists.";
        if(owner<=0||!machine.isOwner(owner))return "The Steam Yarder's ownership has changed.";
        if(!MachineStorageLinks.placed(machine)||!machine.isOnSurface())return "Place the Steam Yarder on the surface ground, outside inventories, structures and trades.";
        if(machine.isLocked()||machine.isSealedByPlayer())return "Unlock and unseal the Steam Yarder before operating it.";
        if(Server.surfaceMesh==null||Server.rockMesh==null||Server.resourceMesh==null)return "The native surface, rock or resource map is unavailable.";
        int x=machine.getTileX(),y=machine.getTileY();
        if(x<0||y<0||x>=Zones.worldTileSizeX-1||y>=Zones.worldTileSizeY-1)return "The Steam Yarder is outside the usable world.";
        VolaTile tile=Zones.getTileOrNull(x,y,true);
        if(tile!=null&&tile.getStructure()!=null)return "Place the Steam Yarder outside houses and bridges.";
        if(Terraforming.isCornerUnderWater(x,y,true))return "Place the Steam Yarder on dry surface ground.";
        return null;
    }
    /** Read-only and strictly bounded to the machine's circular woodlot. */
    public static TileInfo inspect(Item machine,long owner,int x,int y,SteamYarders.HarvestAge threshold) {
        String reason=locationProblem(machine,owner);
        if(reason!=null)return unavailable(x,y,reason);
        long dx=(long)x-machine.getTileX(),dy=(long)y-machine.getTileY();
        if(Math.abs(dx)>SteamYarders.RADIUS||Math.abs(dy)>SteamYarders.RADIUS||dx*dx+dy*dy>SteamYarders.RADIUS*SteamYarders.RADIUS)
            return unavailable(x,y,"This tile is outside the managed five-tile woodlot.");
        if(dx==0&&dy==0)return unavailable(x,y,"The Steam Yarder's own tile is excluded.");
        if(x<0||y<0||x>=Zones.worldTileSizeX-1||y>=Zones.worldTileSizeY-1)return unavailable(x,y,"This tile is outside the usable world.");
        try {
            int tile=Server.surfaceMesh.getTile(x,y);Tiles.Tile type=Tiles.getTile(Tiles.decodeType(tile));byte data=Tiles.decodeData(tile);
            if(!type.isTree())return unavailable(x,y,"No tree; the Yarder only replants trees it harvests.");
            if(type.isEnchanted())return unavailable(x,y,"Enchanted trees are excluded from automated forestry.");
            TreeData.TreeType tree=type.getTreeType(data);int age=FoliageAge.getAgeAsByte(data);
            if(threshold==null||age<minimumAge(threshold))return info(x,y,tile,tree,age,"The tree has not reached the selected harvest age.");
            if(Zones.isTileProtected(x,y))return info(x,y,tile,tree,age,"This tree is protected by the gods.");
            if(RailVegetationHooks.hasSurfaceRail(x,y))return info(x,y,tile,tree,age,"Railway right of way is excluded from forestry.");
            VolaTile world=Zones.getTileOrNull(x,y,true);
            if(world!=null&&(world.getStructure()!=null||world.getWalls().length>0||world.getFloors().length>0||world.getBridgeParts().length>0))
                return info(x,y,tile,tree,age,"A house, bridge or structure occupies this tile.");
            if(world!=null&&(world.findHive(1239,true)!=null||world.findHive(1238,true)!=null))
                return info(x,y,tile,tree,age,"A beehive occupies this tree; automated harvesting is excluded.");
            if(Terraforming.isCornerUnderWater(x,y,true)||Terraforming.allCornersAtRockLevel(x,y,Server.surfaceMesh))
                return info(x,y,tile,tree,age,"The tile cannot safely support the replacement sprout.");
            String denied=permissionProblem(owner,x,y,age);
            return info(x,y,tile,tree,age,denied);
        } catch(Exception failure) {
            LOG.log(Level.WARNING,"Unable to inspect Steam Yarder tree at "+x+","+y,failure);
            return unavailable(x,y,"Native tree information or forestry permission is unavailable.");
        }
    }
    private static TileInfo unavailable(int x,int y,String reason) { return new TileInfo(x,y,0,null,0,0,0,reason); }
    private static TileInfo info(int x,int y,int tile,TreeData.TreeType tree,int age,String reason)throws Exception {
        // Exact native handleChopAction output selection and size calculation.
        int effective=age==15?3:age;
        int template=effective>=8&&!tree.isFruitTree()?ItemList.logHuge:ItemList.log;
        ItemTemplate nativeTemplate=ItemTemplateFactory.getInstance().getTemplate(template);
        double size=effective/15.0;if(tree.isFruitTree())size*=0.25;
        int weight=(int)Math.max(1000,size*nativeTemplate.getWeightGrams());
        // Native chopping converts very small dead fruit trees into kindling, which
        // is outside the Log Bunk's deliberately narrow timber whitelist.
        if(weight<1500&&reason==null)reason="This shrivelled fruit tree would produce kindling rather than logs; cut it manually.";
        return new TileInfo(x,y,tile,tree,age,template,weight,reason);
    }
    private static int minimumAge(SteamYarders.HarvestAge age) {
        switch(age) {case MATURE:return 4;case OLD:return 8;case VERY_OLD:return 12;case OVERAGED:return 14;default:throw new IllegalArgumentException("Invalid harvest age.");}
    }
    private static String permissionProblem(long owner,int x,int y,int age) {
        Village village=Zones.getVillage(x,y,true);
        if(village==null)village=Villages.getVillageWithPerimeterAt(x,y,true);
        if(village==null)return null;
        VillageRole role=village.getRoleFor(owner),everybody=village.getRoleFor(NO_ID);
        if(role==null)return "The Yarder's owner has no established settlement forestry permission.";
        // Native Village action96 distinguishes ages12..14 from all other ages.
        boolean chop=mayChop(role,age)||everybody!=null&&mayChop(everybody,age);
        boolean plant=role.mayPlantSprouts()||everybody!=null&&everybody.mayPlantSprouts();
        return chop&&plant?null:"Settlement permissions must allow the Yarder's owner to cut this age of tree and replant sprouts.";
    }
    private static boolean mayChop(VillageRole role,int age) { return age>11&&age<15?role.mayChopDownOldTrees():role.mayChopDownAllTrees(); }

    /** A complete timed harvest is staged before changing its one native tree tile. */
    public static HarvestResult harvest(Item machine,long owner,int x,int y,int species,SteamYarders.HarvestAge threshold,long bunkId) {
        return harvest(machine,owner,x,y,species,threshold,bunkId,SteamInputs.UNMETERED);
    }
    public static HarvestResult harvest(Item machine,long owner,int x,int y,int species,SteamYarders.HarvestAge threshold,long bunkId,SteamInputs.Work work) {
        Item output=null;boolean attempted=false,committed=false;long outputId=NO_ID;
        SteamInputs.Payment payment=null;
        try {
            String pending=flushBuffered(machine,owner,bunkId);
            if(pending!=null)return new HarvestResult(false,pending,NO_ID,machine!=null&&machine.getItems().stream().noneMatch(i->i.getAuxData()==99));
            TreeData.TreeType replacement=species(species);
            if(replacement==null)return new HarvestResult(false,"Choose a valid tree species before starting the Yarder.",NO_ID);
            TileInfo tree=inspect(machine,owner,x,y,threshold);
            if(!tree.available)return new HarvestResult(false,tree.reason,NO_ID);
            Item bunk=Items.getItemOptional(bunkId).orElse(null);
            String problem=preflight(machine,owner,bunk,tree);
            if(problem!=null)return new HarvestResult(false,"OUTPUT FULL / NO OUTPUT STORAGE: "+problem,NO_ID,true);
            payment=work.reserve(1);
            if(!payment.ready())return new HarvestResult(false,payment.problem(),NO_ID,!payment.faulted());
            output=ItemFactory.createItem(tree.templateId,quality(machine,tree),tree.tree.getMaterial(),(byte)0,"WurmRail Steam Yarder");
            outputId=output.getWurmId();output.setLastOwnerId(owner);output.setDataXY(x,y);
            output.setWeight(tree.weightGrams,true);
            if(output.getWeightGrams()!=tree.weightGrams)throw new IllegalStateException("Native timber weight could not be confirmed.");
            output.setAuxData(tree.templateId==ItemList.logHuge?(byte)tree.age:(byte)0);
            problem=LogBunkPolicy.insertionProblem(bunk,output);
            if(problem!=null)throw new IllegalStateException(problem);
            output.setAuxData(UNCONFIRMED_OUTPUT);
            try { machine.insertItem(output,true); }
            catch(RuntimeException notification) { if(!contains(machine,output))throw notification; }
            if(!contains(machine,output))throw new IllegalStateException("The native timber could not be staged in the Yarder buffer.");
            // A callback during staging cannot authorize a stale tree snapshot.
            TileInfo current=inspect(machine,owner,x,y,threshold);
            if(!current.available||current.tile!=tree.tile)throw new IllegalStateException("The selected tree or its permission changed before harvest.");
            problem=MachineTimberLinks.problem(machine,owner,bunk);
            if(problem!=null)throw new IllegalStateException(problem);
            byte data=Tiles.encodeTreeData(FoliageAge.YOUNG_ONE,false,TreeData.isCentre(Tiles.decodeData(tree.tile)),GrassData.GrowthTreeStage.SHORT);
            byte type=Tiles.getTile(Tiles.decodeType(tree.tile)).isMycelium()?replacement.asMyceliumTree():replacement.asNormalTree();
            int planted=Tiles.encode(Tiles.decodeHeight(tree.tile),type,data);
            attempted=true;
            Server.setSurfaceTile(x,y,Tiles.decodeHeight(tree.tile),type,data);
            Server.setWorldResource(x,y,0);
            if(Server.surfaceMesh.getTile(x,y)!=planted||Server.getWorldResource(x,y)!=0)
                throw new IllegalStateException("Native harvest and replacement sprout could not be confirmed; timber remains quarantined.");
            committed=true;
            payment.commit();
            output.setAuxData(tree.templateId==ItemList.logHuge?(byte)(tree.age==15?3:tree.age):(byte)0);
            try { Zones.getZone(x,y,true).changeTile(x,y);Players.getInstance().sendChangedTile(x,y,true,true); }
            catch(Exception notification) { LOG.log(Level.WARNING,"Yarder harvested tree but tile notification failed at "+x+","+y,notification); }
            String transfer=flushBuffered(machine,owner,bunkId);
            return new HarvestResult(true,transfer,outputId,transfer!=null);
        } catch(Exception failure) {
            if(payment!=null&&payment.ready())try {
                if(committed)payment.commit();else if(attempted)payment.uncertain();else payment.refund();
            }catch(RuntimeException accounting){failure.addSuppressed(accounting);}
            if(!attempted&&output!=null) {
                String cleanup=cancelArtifact(machine,output);
                LOG.log(cleanup==null?Level.WARNING:Level.SEVERE,"Steam Yarder stopped before changing a tree: "+detail(failure),failure);
                return new HarvestResult(false,"Harvest stopped before changing the tree: "+detail(failure)+(cleanup==null?"":" "+cleanup),cleanup==null?NO_ID:outputId);
            }
            LOG.log(Level.SEVERE,"Steam Yarder harvest stopped; retained output "+outputId,failure);
            return new HarvestResult(committed,"Harvest stopped: "+detail(failure)+(output==null?"":" Timber item "+outputId+" is retained for inspection."),outputId);
        }
    }
    private static TreeData.TreeType species(int id) { for(TreeData.TreeType tree:TreeData.TreeType.values())if(tree.getTypeId()==id)return tree;return null; }
    private static String preflight(Item machine,long owner,Item bunk,TileInfo tree)throws Exception {
        String problem=MachineTimberLinks.problem(machine,owner,bunk);if(problem!=null)return problem;
        if(!machine.isHollow()||!machine.getItems().isEmpty())return "The Yarder's timber safety buffer is unavailable.";
        if(!LogBunkPolicy.hasItemSlot(bunk))return "The Log Bunk's item limit has been reached.";
        if(LogBunkPolicy.remainingWeightGrams(bunk)<tree.weightGrams)return "The Log Bunk has insufficient remaining timber weight capacity.";
        ItemTemplate template=ItemTemplateFactory.getInstance().getTemplate(tree.templateId);
        // Native timber is not combineable: changing its weight leaves dimensions unchanged.
        long volume=template.getVolume();
        if(LogBunkPolicy.remainingVolume(bunk)<volume)return "The Log Bunk has insufficient remaining timber volume capacity.";
        for(Item item:bunk.getItems())if(!contains(bunk,item)||!LogBunkPolicy.eligible(item))return "The linked Log Bunk contains invalid timber links; inspect it before harvesting.";
        return null;
    }
    /** Only committed timber may leave the persistent one-item machine buffer. */
    public static String flushBuffered(Item machine,long owner,long bunkId) {
        String location=locationProblem(machine,owner);if(location!=null)return location;
        if(machine.getItems().isEmpty())return null;
        if(machine.getItems().size()!=1)return "The Yarder's timber buffer needs inspection before harvesting can resume.";
        Item output=machine.getItems().iterator().next();
        if(output.getAuxData()==UNCONFIRMED_OUTPUT)return "Timber item "+output.getWurmId()+" is retained in the Yarder: tree harvest is unconfirmed; operator inspection is required.";
        if(!contains(machine,output)||!LogBunkPolicy.eligible(output))return "The Yarder buffer contains an invalid timber item; unload it before harvesting.";
        Item bunk=Items.getItemOptional(bunkId).orElse(null);
        String problem=MachineTimberLinks.problem(machine,owner,bunk);if(problem!=null)return "Timber retained in Yarder: "+problem;
        problem=LogBunkPolicy.insertionProblem(bunk,output);if(problem!=null)return "Timber retained in Yarder: "+problem;
        if(!bunk.canHold(output)||!bunk.mayCreatureInsertItem()||!bunk.testInsertItem(output))return "Timber retained in Yarder: the linked Log Bunk cannot accept this timber.";
        try { bunk.insertItem(output,false,true); }
        catch(RuntimeException failure) {
            if(contains(bunk,output)&&!machine.getItems().contains(output))return null;
            restoreBuffer(machine,bunk,output);
            LOG.log(Level.WARNING,"Yarder timber retained for a later retry",failure);
            return "Timber retained in Yarder: native Log Bunk insertion failed; check storage before restarting.";
        }
        if(contains(bunk,output)&&!machine.getItems().contains(output))return null;
        restoreBuffer(machine,bunk,output);
        return "Timber retained in Yarder: the linked Log Bunk refused the timber.";
    }
    private static boolean contains(Item parent,Item child) { return parent!=null&&child.getParentId()==parent.getWurmId()&&parent.getItems().contains(child); }
    private static void restoreBuffer(Item machine,Item bunk,Item output) {
        if(contains(machine,output)&&!bunk.getItems().contains(output))return;
        try {
            if(bunk.getItems().contains(output))bunk.dropItem(output.getWurmId(),false);
            machine.insertItem(output,true);
            if(!contains(machine,output)||bunk.getItems().contains(output))throw new IllegalStateException("Native timber links could not be restored.");
        } catch(Exception failure) { throw new IllegalStateException("Timber item "+output.getWurmId()+" requires operator recovery; harvesting is stopped.",failure); }
    }
    private static String cancelArtifact(Item machine,Item output) {
        try {
            if(machine.getItems().contains(output))machine.dropItem(output.getWurmId(),false);
            Items.destroyItem(output.getWurmId());
            if(Items.getItemOptional(output.getWurmId()).isPresent()||machine.getItems().contains(output))throw new IllegalStateException("Native staging cleanup was not confirmed.");
            return null;
        } catch(Exception failure) { LOG.log(Level.SEVERE,"Unable to cancel unearned Yarder staging item "+output.getWurmId(),failure);return "Staging item "+output.getWurmId()+" requires operator recovery."; }
    }
    private static float quality(Item machine,TileInfo tree)throws Exception {
        double knowledge=Math.max(1,Math.min(100,machine.getCurrentQualityLevel()));
        Skill woodcutting=SkillsFactory.createSkills("WurmRail Steam Yarder").learn(SkillList.WOODCUTTING,(float)knowledge);
        int age=tree.age==15?3:tree.age;
        double difficulty=Servers.localServer.challengeServer?Tiles.getTile(Tiles.decodeType(tree.tile)).getWoodDificulity()*(1.0+age/14.0):15-age;
        return (float)Math.max(1,Math.min(knowledge,woodcutting.skillCheck(difficulty,0,true,1f)));
    }
    private static String detail(Exception failure) { return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(); }
}
