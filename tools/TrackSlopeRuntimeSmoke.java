import com.wurmonline.mesh.MeshIO;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.wurmrail.RailwayTrackMaterials;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** Real meshes and native paid construction enforce longitudinal grade and zero bank. */
public final class TrackSlopeRuntimeSmoke {
    private static final int[][] DIRECTIONS={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
    private static int assertions;
    private TrackSlopeRuntimeSmoke() { }

    public static void run() throws Exception {
        try(Terrain terrain=new Terrain(132,170,146,180)) {
            for(int layer:new int[]{0,-1})geometry(terrain,layer);
            endpointPreflight(terrain);
            affectedNeighbor(terrain);
            paidPlacement(terrain);
            diagonalHills(terrain);
            existingTrack(terrain);
        }
        System.out.println("PASS native rail terrain construction: slope 20 in all eight directions, zero transverse slope, flat bends/junctions, surface/cave floors, both-end and final-shape preflight, paid rollback and existing-track compatibility ("+assertions+" assertions)");
    }

    private static void geometry(Terrain terrain,int layer) {
        int x=140,y=174;
        for(int direction=0;direction<8;direction++) {
            int mask=(1<<direction)|(1<<((direction+4)%8));
            for(int sign:new int[]{-1,1}) {
                int divisor=Math.abs(DIRECTIONS[direction][0])+Math.abs(DIRECTIONS[direction][1]);
                int gx=sign*20*DIRECTIONS[direction][0]/divisor,gy=sign*20*DIRECTIONS[direction][1]/divisor;
                terrain.corners(layer,x,y,50,50+gx,50+gy,50+gx+gy);
                okay(WurmRailWorld.validateTrackShape(x,y,layer,mask),"slope 20 is allowed along a straight at direction "+direction+" layer "+layer);
                okay(WurmRailWorld.validateTrackShape(x,y,layer,1<<direction),"a one-ended track uses its actual rail axis");
                okay(WurmRailWorld.validateTrackShape(x,y,layer,0),"a provisional unconnected track retains basic terrain validation");
            }
            // A 21-height tile remains invalid even if its connection happens
            // to be diagonal and an exact integer planar grade is impossible.
            terrain.corners(layer,x,y,50,50,50,71);
            bad(WurmRailWorld.validateTrackShape(x,y,layer,mask),"a slope over 20 is rejected for every compass axis");
            int dx=DIRECTIONS[direction][0],dy=DIRECTIONS[direction][1];
            int gx=dx*5-dy,gy=dy*5+dx;
            terrain.corners(layer,x,y,50,50+gx,50+gy,50+gx+gy);
            okay(WurmRailWorld.validateTile(x,y,layer),"small bank fixture is otherwise valid native ground");
            bad(WurmRailWorld.validateTrackShape(x,y,layer,mask),"any nonzero side-to-side grade is rejected");
        }
        for(int mask:new int[]{34,136}) {
            terrain.corners(layer,x,y,50,55,45,51);
            bad(WurmRailWorld.validateTrackShape(x,y,layer,mask),"unequal diagonal cross-track corners cannot create a twisted rail bed");
        }
        terrain.corners(layer,x,y,50,60,60,69);
        okay(WurmRailWorld.validateTrackShape(x,y,layer,136),"balanced southeast diagonal grades may change longitudinal pitch through a tile");
        bad(WurmRailWorld.validateTrackShape(x,y,layer,34),"the same terrain remains banked across the other diagonal");
        terrain.corners(layer,x,y,60,69,50,60);
        okay(WurmRailWorld.validateTrackShape(x,y,layer,34),"balanced northeast diagonal grades do not require an exact midpoint height");
        bad(WurmRailWorld.validateTrackShape(x,y,layer,136),"unequal cross-track corners still reject northwest/southeast banking");
        // Every bend, and every multi-way junction, must be completely level.
        List<Integer> turning=new ArrayList<Integer>();
        for(int first=0;first<8;first++)for(int second=first+1;second<8;second++) {
            int mask=(1<<first)|(1<<second);
            if(second-first!=4&&RailNetwork.isBuildableAlignment(mask))turning.add(mask);
        }
        turning.addAll(Arrays.asList(21,69,85,255));
        for(int mask:turning) {
            terrain.corners(layer,x,y,50,50,50,50);
            okay(WurmRailWorld.validateTrackShape(x,y,layer,mask),"flat bends and junctions remain constructible");
            terrain.corners(layer,x,y,50,50,51,51);
            bad(WurmRailWorld.validateTrackShape(x,y,layer,mask),"even slope one is too much for a bend or junction");
        }
    }

    private static void endpointPreflight(Terrain terrain) throws Exception {
        terrain.flat();
        for(int x=137;x<=143;x++)for(int y=171;y<=178;y++)terrain.point(0,x,y,50+(y-174)*5);
        Fixture f=new Fixture("endpoints",140,174,0,2);
        try {
            Node hub=f.add("hub",140,174),north=f.add("north",140,173),south=f.add("south",140,175),west=f.add("west",139,174);
            f.net.connect(hub.name,north.name);
            call(f.runtime,"connectNewNodes",new Class<?>[]{Node.class,Node.class},hub,south);
            check(hub.links.size()==2&&south.links.contains(hub.name),"valid zero-bank graded straight connects both ends");
            Map<String,List<String>> before=links(f.net);
            check(!(Boolean)call(f.runtime,"constructionConnectionAllowed",new Class<?>[]{Node.class,Node.class},hub,west),
                    "automatic connection selection skips a nonflat junction");
            fail(()->call(f.runtime,"connectNewNodes",new Class<?>[]{Node.class,Node.class},hub,west),
                    "explicit construction rejects a junction on graded ground");
            check(links(f.net).equals(before),"rejected nonflat junction does not modify either endpoint");

            RailNetwork second=new RailNetwork();Node flat=second.addNode("flat",140,174,0),bank=second.addNode("bank",141,174,0);
            WurmRailRuntime runtime=runtime(second,"second-end");
            // The first tile is level. A grade across the far eastern edge
            // banks only the second endpoint of this east/west connection.
            terrain.corners(0,140,174,50,50,50,50);terrain.corners(0,141,174,50,50,50,51);
            Map<String,List<String>> unchanged=links(second);
            fail(()->call(runtime,"connectNewNodes",new Class<?>[]{Node.class,Node.class},flat,bank),
                    "a transverse slope only at the second endpoint is rejected");
            check(links(second).equals(unchanged),"second-end slope rejection cannot leave a half connection");
        } finally { f.close(); }
    }

    private static void affectedNeighbor(Terrain terrain) throws Exception {
        terrain.flat();
        // The hub is level. Its diagonal neighbor is a legal north/south
        // slope, but connecting it back southwest would introduce a bend.
        for(int x=139;x<=142;x++)for(int y=170;y<=174;y++)terrain.point(0,x,y,50+(y-174)*5);
        terrain.corners(0,139,174,50,50,50,50);
        Fixture f=new Fixture("affected-neighbor",139,174,0,2);
        try {
            Node hub=f.add("hub",139,174),diagonal=f.add("diagonal",140,173),north=f.add("north",140,172);
            f.net.connect(diagonal.name,north.name);Map<String,List<String>> before=links(f.net);
            int alignment=diagonal.getAlignmentMask();
            fail(()->call(f.runtime,"applyConnections",new Class<?>[]{Creature.class,Node.class,int.class},f.actor,hub,2),
                    "connection-window preflight rejects a nonflat bend formed at another rail");
            check(links(f.net).equals(before)&&diagonal.getAlignmentMask()==alignment,
                    "all affected final terrain shapes are checked before graph or switch mutation");
            for(Node rail:f.net.nodes.values())check(Items.getItemOptional(rail.markerId).isPresent(),"rejected connection edit preserves all native markers");
        } finally { f.close(); }
    }

    private static void paidPlacement(Terrain terrain) throws Exception {
        for(int layer:new int[]{0,-1}) {
            terrain.flat();
            Fixture f=new Fixture("paid-"+layer,134,174,layer,0);
            try {
                for(int i=0;i<4;i++) {
                    Item section=ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId,51f+i,"Slope fixture");
                    check(f.actor.inventory.insertItem(section,true),"normal builder carries a crafted rail section");
                }
                for(int x=133;x<=138;x++)terrain.point(layer,x,175,51);
                int before=RailwayTrackMaterials.count(f.actor);int markers=markers();
                fail(()->call(f.runtime,"buildLine",new Class<?>[]{Creature.class,String.class,int.class,int.class,int.class,int.class,int.class,boolean.class},
                        f.actor,"bank",134,174,layer,2,3,true),"paid line rejects side-to-side slope");
                check(f.net.nodes.isEmpty()&&RailwayTrackMaterials.count(f.actor)==before&&markers()==markers,
                        "rejected paid line preserves graph, every crafted section and native scenery");
                fail(()->call(f.runtime,"buildLine",new Class<?>[]{Creature.class,String.class,int.class,int.class,int.class,int.class,int.class,boolean.class},
                        f.actor,"single",134,174,layer,2,1,true),"a single placed rail honors its selected east/west construction direction");
                check(f.net.nodes.isEmpty()&&RailwayTrackMaterials.count(f.actor)==before&&markers()==markers,
                        "rejected single-tile placement never consumes its section");
                terrain.flat();
                // A continuous east/west grade has zero north/south bank.
                for(int x=133;x<=138;x++)for(int y=172;y<=177;y++)terrain.point(layer,x,y,50+(x-134)*5);
                call(f.runtime,"buildLine",new Class<?>[]{Creature.class,String.class,int.class,int.class,int.class,int.class,int.class,boolean.class},
                        f.actor,"grade",134,174,layer,2,3,true);
                check(f.net.nodes.size()==3&&RailwayTrackMaterials.count(f.actor)==before-3,"normal paid construction accepts longitudinal grades and consumes exactly three pieces");
                for(Node node:f.net.nodes.values())check(Items.getItem(node.markerId).isOnSurface()==(layer==0),"graded rails remain on the requested native floor");
                check(RailNetwork.load(f.state).nodes.size()==3,"valid graded paid track persists normally");
            } finally { f.close(); }
        }
    }

    private static void existingTrack(Terrain terrain) throws Exception {
        terrain.flat();terrain.corners(0,140,174,50,50,51,51);
        okay(WurmRailWorld.validateTile(140,174,0),"new construction-only bank checks do not invalidate legacy vehicle ground checks");
        RailNetwork net=new RailNetwork();Node hub=net.addNode("oldHub",140,174,0);
        Node north=net.addNode("oldNorth",140,173,0),south=net.addNode("oldSouth",140,175,0),east=net.addNode("oldEast",141,174,0);
        for(Node side:Arrays.asList(north,south,east))net.connect(hub.name,side.name);
        Path path=state("legacy");net.save(path);RailNetwork loaded=RailNetwork.load(path);
        check(loaded.nodes.get(hub.name).links.size()==3,"an existing sloped junction remains readable without deleting old track");
    }

    /** Wurm terrain follows two triangles, not a single four-corner plane.
     * These alternating integer rises are balanced across each diagonal but
     * deliberately violate the old midpoint equality on every rail tile. */
    private static void diagonalHills(Terrain terrain) throws Exception {
        for(int layer:new int[]{0,-1})for(int direction:new int[]{1,3,5,7})
            for(int grade:new int[]{15,20})for(int sign:new int[]{-1,1}) {
                terrain.flat();int x=139,y=174,dx=DIRECTIONS[direction][0],dy=DIRECTIONS[direction][1];
                for(int px=terrain.minX;px<=terrain.maxX;px++)for(int py=terrain.minY;py<=terrain.maxY;py++) {
                    int progress=dx*(px-x)+dy*(py-y);
                    int rise=(grade/2)*progress+(grade%2)*Math.floorDiv(progress,2)-3*Math.floorMod(progress,2);
                    terrain.point(layer,px,py,250+sign*rise);
                }
                String tag="diagonal-"+layer+"-"+direction+"-"+grade+"-"+sign;
                Fixture f=new Fixture(tag,x,y,layer,0);Item engine=null;
                try {
                    for(int piece=0;piece<3;piece++) {
                        Item section=ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId,61f+piece,"Diagonal slope fixture");
                        check(f.actor.inventory.insertItem(section,true),"ordinary diagonal builder carries one crafted section per tile");
                    }
                    int before=RailwayTrackMaterials.count(f.actor);
                    call(f.runtime,"buildLine",new Class<?>[]{Creature.class,String.class,int.class,int.class,int.class,int.class,int.class,boolean.class},
                            f.actor,"diagonal",x,y,layer,direction,3,true);
                    check(f.net.nodes.size()==3&&RailwayTrackMaterials.count(f.actor)==before-3,
                            "paid construction accepts a balanced nonplanar diagonal hill at grade "+grade+" direction "+direction+" layer "+layer);
                    for(int tile=0;tile<3;tile++) {
                        Node rail=f.net.nodes.get("diagonal"+tile);
                        check(rail!=null&&rail.tileX==x+dx*tile&&rail.tileY==y+dy*tile&&rail.layer==layer,
                                "each diagonal piece occupies its requested native tile and floor");
                        check(rail.links.size()==(tile==1?2:1),"automatic diagonal linking creates one straight line without accidental junctions");
                        if(tile>0)check(rail.links.contains("diagonal"+(tile-1)),"each diagonal hill edge connects to the preceding piece");
                    }
                    check(RailNetwork.load(f.state).nodes.get("diagonal1").links.size()==2,"a paid diagonal hill retains both connections after save/load");
                    double lateralX=-dy*.4/Math.sqrt(2),lateralY=dx*.4/Math.sqrt(2);
                    for(int edge=0;edge<2;edge++)for(double progress:new double[]{.1,.25,.5,.75,.9}) {
                        double cx=(x+dx*(edge+progress))*4+2,cy=(y+dy*(edge+progress))*4+2;
                        float left=Zones.calculateHeight((float)(cx+lateralX),(float)(cy+lateralY),layer==0);
                        float right=Zones.calculateHeight((float)(cx-lateralX),(float)(cy-lateralY),layer==0);
                        check(Math.abs(left-right)<.0002,"native triangular ground stays level between left/right rails throughout an asymmetric diagonal grade");
                    }
                    Node start=f.net.nodes.get("diagonal0"),end=f.net.nodes.get("diagonal2");
                    engine=WurmRailWorld.spawnLocomotive(f.actor,start.worldX(),start.worldY(),layer);
                    Train train=f.net.createTrain("DiagonalHill",f.actor.getWurmId(),Arrays.asList(engine.getWurmId()),start.name,"diagonal1");
                    call(f.runtime,"applyPoses",new Class<?>[]{Train.class},train);
                    f.net.movementGuard=poses->{
                        try {return (Boolean)call(f.runtime,"validateMotion",new Class<?>[]{List.class},poses);}
                        catch(Exception failure){throw new IllegalStateException(failure);}
                    };
                    float startHeight=engine.getPosZ();
                    travelHill(f,train,end.name);float endHeight=engine.getPosZ();
                    check((endHeight-startHeight)*sign>0,"native locomotive physically climbs or descends the requested diagonal hill");
                    List<Pose> held=new ArrayList<Pose>(train.poses);f.net.reverse(train.name);
                    check(train.poses.get(0).x==held.get(0).x&&train.poses.get(0).y==held.get(0).y&&train.poses.get(0).rotation==held.get(0).rotation,
                            "reversing to return on the diagonal hill changes traction without moving or spinning the body");
                    travelHill(f,train,start.name);
                    check(Math.abs(engine.getPosZ()-startHeight)<.001,"native uphill/downhill return reaches the original floor height");
                } finally {
                    f.net.movementGuard=null;
                    if(engine!=null&&Items.getItemOptional(engine.getWurmId()).isPresent())Items.destroyItem(engine.getWurmId());
                    f.close();
                }
            }
        System.out.println("PASS native diagonal hills: all four diagonal headings, grades 15/20, balanced asymmetric terrain triangles, actual zero-bank samples, paid construction and guarded locomotive travel on surface/cave floors");
    }
    private static void travelHill(Fixture f,Train train,String destination) throws Exception {
        f.net.setRoute(train.name,Arrays.asList(destination),0);f.net.start(train.name);
        for(int step=0;step<400&&train.powered;step++) {
            Pose old=train.poses.get(0);f.net.tick(.1);Pose now=train.poses.get(0);
            check(Math.hypot(now.x-old.x,now.y-old.y)<=train.maxSpeed*.1+.001,"guarded diagonal hill travel moves progressively without a jump");
            call(f.runtime,"applyPoses",new Class<?>[]{Train.class},train);
        }
        check(!train.powered&&"Destination reached".equals(train.status),"native motion guard permits the entire uphill/downhill diagonal service: "+train.status);
        Node stop=f.net.nodes.get(destination);Pose pose=train.poses.get(0);
        check(Math.hypot(pose.x-stop.worldX(),pose.y-stop.worldY())<.001,"the hill service physically reaches the selected rail center");
    }

    private static int markers() {int count=0;for(Item item:Items.getAllItems())if(item.getTemplateId()==WurmRailWorld.trackTemplateId)count++;return count;}
    private static final class Terrain implements AutoCloseable {
        final int minX,minY,maxX,maxY;final Map<Integer,Integer> surface=new LinkedHashMap<Integer,Integer>(),cave=new LinkedHashMap<Integer,Integer>();
        Terrain(int minX,int minY,int maxX,int maxY) {
            this.minX=minX;this.minY=minY;this.maxX=maxX;this.maxY=maxY;
            for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++) {int key=x+y*256;surface.put(key,Server.surfaceMesh.getTile(x,y));cave.put(key,Server.caveMesh.getTile(x,y));}
            flat();
        }
        void flat(){for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++){point(0,x,y,50);point(-1,x,y,50);}}
        void point(int layer,int x,int y,int height){mesh(layer).setTile(x,y,Tiles.encode((short)height,layer==0?Tiles.Tile.TILE_DIRT.id:Tiles.Tile.TILE_CAVE.id,layer==0?(byte)0:(byte)50));}
        void corners(int layer,int x,int y,int nw,int ne,int sw,int se){point(layer,x,y,nw);point(layer,x+1,y,ne);point(layer,x,y+1,sw);point(layer,x+1,y+1,se);}
        MeshIO mesh(int layer){return layer==0?Server.surfaceMesh:Server.caveMesh;}
        public void close(){for(Map.Entry<Integer,Integer> entry:surface.entrySet())Server.surfaceMesh.setTile(entry.getKey()%256,entry.getKey()/256,entry.getValue());for(Map.Entry<Integer,Integer> entry:cave.entrySet())Server.caveMesh.setTile(entry.getKey()%256,entry.getKey()/256,entry.getValue());}
    }
    private static final class Fixture implements AutoCloseable {
        final RailNetwork net=new RailNetwork();final TrackMaterialsSmoke.InventoryActor actor;final WurmRailRuntime runtime;final Path state;final int layer;
        Fixture(String name,int x,int y,int layer,int power) throws Exception {
            this.layer=layer;actor=TrackMaterialsSmoke.actor(998900032L,true,power);actor.getStatus().setLayer(layer);
            actor.getStatus().getPosition().setPosX(x*4+2);actor.getStatus().getPosition().setPosY(y*4+2);
            runtime=runtime(net,name);state=state(name);
        }
        Node add(String name,int x,int y) throws Exception {Node node=net.addNode(name,x,y,layer);Item marker=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),layer);node.markerId=marker.getWurmId();return node;}
        public void close(){for(Node node:net.nodes.values())if(Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);if(actor.inventory!=null&&Items.getItemOptional(actor.inventory.getWurmId()).isPresent())Items.destroyItem(actor.inventory.getWurmId());actor.getStatus().setLayer(0);}
    }
    private static Path state(String name){return Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("track-slopes/"+name+"/network.properties");}
    private static WurmRailRuntime runtime(RailNetwork net,String name) throws Exception {WurmRailRuntime runtime=new WurmRailRuntime();runtime.configure(new Properties());set(runtime,"network",net);set(runtime,"ready",true);set(runtime,"stateFile",state(name));return runtime;}
    private static void set(Object target,String name,Object value) throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Map<String,List<String>> links(RailNetwork net){Map<String,List<String>> result=new LinkedHashMap<String,List<String>>();for(Node node:net.nodes.values())result.put(node.name,new ArrayList<String>(node.links));return result;}
    private static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception {Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(target,args);}catch(InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw wrapped;}}
    private interface Checked {void run() throws Exception;}
    private static void fail(Checked action,String message) throws Exception {boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,message);}
    private static void okay(String error,String message){check(error==null,message+": "+error);}
    private static void bad(String error,String message){check(error!=null&&!error.isEmpty(),message);}
    private static void check(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(message);}
}
