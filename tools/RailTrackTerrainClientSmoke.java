import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.GroundItemData;
import com.wurmonline.client.renderer.RenderVector;
import com.wurmonline.client.renderer.Matrix;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CellRenderer;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.client.renderer.cell.SurfaceCell;
import com.wurmonline.client.renderer.cell.Volume;
import com.wurmonline.client.renderer.structures.BridgePartData;
import com.wurmonline.shared.constants.BridgeConstants.BridgeState;
import com.wurmonline.client.renderer.model.ModelDataBounds;
import com.wurmonline.client.renderer.model.ModelResourceWrapper;
import com.wurmonline.client.renderer.model.collada.AbstractColladaModelData;
import com.wurmonline.client.renderer.model.collada.StaticColladaModelData;
import com.wurmonline.client.renderer.model.collada.importer.ColladaModel;
import com.wurmonline.client.renderer.model.collada.importer.ColladaImporter;
import com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry;
import com.wurmonline.client.renderer.model.collada.importer.TriangleMesh;
import com.wurmonline.client.resources.Resources;
import com.wurmonline.client.resources.ResourceUrl;
import com.wurmonline.client.util.SecureStrings;
import com.wurmonline.math.Transform;
import com.wurmonline.math.Quaternion;
import com.wurmonline.math.Vector3f;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.FloatBuffer;
import java.util.*;

/** Real native meshes, instance callbacks, buffers and world matrices; no OpenGL context. */
public final class RailTrackTerrainClientSmoke {
    private static int assertions;
    private static long now=1000000000L;
    private static final int[] DX={0,1,1,1},DY={-1,-1,0,1};
    // Native unload removes URL entries unconditionally when older cache
    // generations are collected. Keep this test's deliberately reseeded base
    // models alive so private-instance cleanup is tested independently of that
    // unrelated native behavior.
    private static final List<ModelResourceWrapper> sourceFixtures=new ArrayList<ModelResourceWrapper>();
    private RailTrackTerrainClientSmoke() { }
    public static void run() throws Exception {
        World previous=CellRenderable.world;
        try {
            Resources resources=new Resources(new File("packs"),new ArrayList<String>());
            realConstructorLifecycle(resources);
            for(int layer:new int[]{0,-1})for(int axis=0;axis<4;axis++)
                for(float grade:new float[]{-.5f,-.25f,.25f,.5f})
                    grade(resources,layer,axis,grade);
            gradeBreaks(resources);instanceIsolation(resources);loaderIsolation(resources);exclusions(resources);unavailableAndPortal(resources);cacheAndReplacement(resources);
            bridgeDeck(resources);
            System.out.println("PASS native terrain-conforming rail tile seams: cardinal and diagonal grades, crests and troughs, surface and cave, zero banking, unchanged XY, cold cached source and independent instances, normals, private VBO invalidation, selection bounds, terraform refresh, unavailable floor and portal edge guards ("+assertions+" assertions)");
        } finally {sourceFixtures.clear();CellRenderable.world=previous;}
    }

    private static void bridgeDeck(Resources resources) throws Exception {
        RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
        long bridgeId=34179076L;
        BridgePartData deck=(BridgePartData)allocate(BridgePartData.class);
        set(deck,"bridgeId",bridgeId);
        set(deck,"state",BridgeState.COMPLETED);
        set(deck,"realHeight",200);
        set(deck,"dir",2);
        set(deck,"slope",3f);
        set(deck,"xTile",11);
        set(deck,"yTile",11);
        SurfaceCell cell=(SurfaceCell)allocate(SurfaceCell.class);
        set(cell,"staticStructures",Collections.singletonList(deck));
        CellRenderer cells=(CellRenderer)allocate(CellRenderer.class);
        SurfaceCell[][] grid=new SurfaceCell[16][16];
        grid[0][0]=cell;
        set(cells,"surfaceCells",grid);
        CellRenderable.world.setCellRenderer(cells);

        String name="model.wurmrail.track.68";
        ModelResourceWrapper base=load(resources,name);
        float[][] original=snapshot(base);
        GroundItemCellRenderable rail=make(name+".",0,46,46,base);
        ((GroundItemData)get(rail,"item")).setBridgeId(bridgeId);
        set(rail,"onGround",false);
        set(rail,"h",21.5f);
        conform(rail);
        check(Math.abs(rail.getHPos()-21.5f)<.0001,"bridge rail remains at native deck centre, above terrain");
        float[][] formed=snapshot(rail.getModelWrapper());
        for(int i=0;i<formed.length;i++) for(int j=0;j<formed[i].length;j+=3)
            check(Math.abs(formed[i][j+1]-original[i][j+1]-original[i][j]*.75f)<.0001,
                    "bridge rail vertices match the native deck plane without shortening the footprint");
        set(cell,"staticStructures",Collections.emptyList());
        conform(rail);
        check(Math.abs(rail.getHPos()-21.5f)<.0001&&Arrays.deepEquals(formed,snapshot(rail.getModelWrapper())),
                "late bridge data keeps authoritative item height and last geometry");
        System.out.println("PASS native bridge rail mesh conforms to the deck and retains explicit height while its deck data is unavailable");
    }

    public static void coldFirstTrack() throws Exception {
        RailVehicleTerrainClientSmoke.setupWorld(.5f,0,5,.5f,0,5);
        Resources resources=new Resources(new File("packs"),new ArrayList<String>());
        ModelResourceWrapper base=load(resources,"model.wurmrail.track.68");
        GroundItemCellRenderable item=make("model.wurmrail.track.68.",0,46,46,base);
        conform(item);
        Object helper=get(item,"wurmRailTrackTerrain");
        check(helper!=null&&helper.getClass().getClassLoader()==item.getClass().getClassLoader(),
                "the first native ground-item callback resolves the terrain helper after isolated bootstrap release");
        check(item.getWorldModelMatrix()!=null&&item.getPitch()==0&&item.getRoll()==0,"first cold rail has a finite unshortened native world matrix");
        System.out.println("PASS first native ground item resolves rail terrain helper after bootstrap release");
    }

    /** The native constructor requests the shared model before initialize()
     * delivers its private-copy callback. This warm-cache path cannot be
     * represented by allocating an already initialized object with Unsafe. */
    private static void realConstructorLifecycle(Resources resources) throws Exception {
        for(int layer:new int[]{0,-1})for(int axis=0;axis<4;axis++) {
            String name="model.wurmrail.track."+((1<<axis)|(1<<(axis+4)));
            ModelResourceWrapper source=load(resources,name);float[][] pristine=snapshot(source),originalNormals=normalSnapshot(source);
            com.wurmonline.client.renderer.model.ModelResourceLoader.storeWeakReference(source.getModelData(),source.getResourceUrl(),source);
            for(float grade:new float[]{.5f,-.5f,0,.25f,-.25f}) {
                RailVehicleTerrainClientSmoke.setupWorld(grade*DX[axis],grade*DY[axis],8,grade*DX[axis],grade*DY[axis],8);
                // Allocate only the display-free world renderer's origin fields;
                // the rail and packet data below use their genuine constructors.
                fixture(name+".",layer,46,46);
                GroundItemData packet=new GroundItemData(970000001L,name+".","rail track","",(byte)14,
                        46f,46f,-3000f,0f,0f,0f,0f,0f,layer,"",(short)0,1f,(byte)0,-10L,(byte)0,-10L,0);
                GroundItemCellRenderable rail=new GroundItemCellRenderable(packet);
                check(rail.getModelWrapper()==source,"native warm constructor receives the pristine cached model before initialize");
                check(Arrays.deepEquals(pristine,snapshot(source))&&Arrays.deepEquals(originalNormals,normalSnapshot(source)),
                        "native constructor before initialize must not deform the shared cached rail source (axis="+axis+", layer="+layer+", grade="+grade+")");
                rail.initialize();rail.prepareRender();verifyMesh(rail,axis,layer);verifyNativeUpload(rail);
                check(rail.getModelWrapper()!=source,"initialize gives the real constructed rail its private model");
                check(Arrays.deepEquals(pristine,snapshot(source))&&Arrays.deepEquals(originalNormals,normalSnapshot(source)),
                        "native initialization and render preparation preserve source positions and normals");
                rail.touch();rail.prepareRender();verifyMesh(rail,axis,layer);
                check(com.wurmonline.client.renderer.model.ModelResourceLoader.getModel(name+".")==source,
                        "constructor, initialization and terrain touch preserve the same pristine URL-cache entry");
            }
        }
        System.out.println("PASS native rail constructor lifecycle: warm cached source before initialize, private callback, opposite-grade instances, terrain touch, render matrices and actual interleaved VBO upload");
    }

    private static void verifyNativeUpload(GroundItemCellRenderable rail) throws Exception {
        for(TriangleMesh mesh:meshes(rail.getModelWrapper())) {
            check(!mesh.hasWeightBuffer(),"rail scenery uses ordinary native vertex upload without skinning");
            int stride=3+(mesh.hasNormalBuffer()?3:0)+(mesh.hasColorBuffer()?3:0)
                    +mesh.getNumTextureCoords(0)+mesh.getNumTextureCoords(1);
            FloatBuffer uploaded=FloatBuffer.allocate(mesh.getVertexCount()*stride);
            call(mesh,"uploadDataToVBO",new Class<?>[]{FloatBuffer.class},uploaded);
            FloatBuffer points=mesh.getVertexBuffer();
            check(uploaded.position()==mesh.getVertexCount()*stride,"native upload writes the complete expected interleaved vertex stream");
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++)for(int coordinate=0;coordinate<3;coordinate++)
                check(uploaded.get(vertex*stride+coordinate)==points.get(vertex*3+coordinate),
                        "the native GPU upload path uses the same conformed XYZ as selection and world matrices");
        }
    }

    private static void grade(Resources resources,int layer,int axis,float grade) throws Exception {
        float gx=grade*DX[axis],gy=grade*DY[axis];
        RailVehicleTerrainClientSmoke.setupWorld(gx,gy,8,gx,gy,8);
        String name="model.wurmrail.track."+((1<<axis)|(1<<(axis+4)));
        ModelResourceWrapper base=load(resources,name);
        float[][] pristine=snapshot(base);
        GroundItemCellRenderable first=make(name+".",layer,46,46,base),second=make(name+".",layer,46+4*DX[axis],46+4*DY[axis],base);
        conform(first);conform(second);
        verifyMesh(first,axis,layer);verifyMesh(second,axis,layer);
        check(Arrays.deepEquals(pristine,snapshot(base)),"native cached source is pristine after first and subsequent item callbacks");
        List<float[]> a=railEnd(first,axis,1),b=railEnd(second,axis,-1);
        check(a.size()>0&&b.size()>0,"both native railhead seam faces have vertices");
        for(float[] point:a) {
            float best=Float.POSITIVE_INFINITY;
            for(float[] other:b)best=Math.min(best,distance(point,other));
            check(best<.0001f,"adjacent railhead endpoints meet in native XYZ at axis "+axis+" grade "+grade+" layer "+layer+" gap="+best);
        }
        check(first.getVolume().getSizeH()>.3f&&first.getVolume().getSizeX()>1,"selection volume follows the sloped native mesh");
    }

    private static void gradeBreaks(Resources resources) throws Exception {
        for(int layer:new int[]{0,-1})for(int axis=0;axis<4;axis++)for(int sign:new int[]{-1,1}) {
            World world=RailVehicleTerrainClientSmoke.setupWorld(0,0,8,0,0,8);
            // Deliberately nonplanar diagonal grades: their centre floor is not
            // the average of the two exit corner heights. Sample the actual
            // client interpolator rather than assuming a rigid plane.
            float[] heights=(float[])get(world.getNearTerrainBuffer(),"heights");
            short[] floors=(short[])get(world.getCaveBuffer(),"floors");
            for(int x=8;x<=20;x++)for(int y=8;y<=20;y++) {
                float along=((x*4-46)*DX[axis]+(y*4-46)*DY[axis])/(DX[axis]*DX[axis]+DY[axis]*DY[axis]);
                float h=8+sign*.25f*Math.abs(along);
                heights[(x&511)|((y&511)<<9)]=h;floors[world.getCaveBuffer().getOffset(x,y)]=(short)Math.round(h*10);
            }
            String name="model.wurmrail.track."+((1<<axis)|(1<<(axis+4)));
            GroundItemCellRenderable item=make(name+".",layer,46,46,load(resources,name));conform(item);verifyMesh(item,axis,layer);
        }
    }

    private static void instanceIsolation(Resources resources) throws Exception {
        String name="model.wurmrail.track.68";
        ModelResourceWrapper source=load(resources,name);float[][] original=snapshot(source);
        RailVehicleTerrainClientSmoke.setupWorld(.5f,0,8,.5f,0,8);
        GroundItemCellRenderable rising=make(name+".",0,46,46,source);conform(rising);
        float[][] first=snapshot(rising.getModelWrapper());
        RailVehicleTerrainClientSmoke.setupWorld(-.5f,0,8,-.5f,0,8);
        GroundItemCellRenderable falling=make(name+".",0,46,46,source);conform(falling);
        check(Arrays.deepEquals(first,snapshot(rising.getModelWrapper())),"another instance on the opposite grade cannot alter a rail's buffers");
        check(Arrays.deepEquals(original,snapshot(source)),"copy-on-write retains pristine optimized shared buffers");
        List<TriangleMesh> own=meshes(rising.getModelWrapper()),other=meshes(falling.getModelWrapper());
        for(int i=0;i<own.size();i++) {
            check(get(own.get(i),"vertBuffer")!=get(other.get(i),"vertBuffer")&&get(own.get(i),"normalBuffer")!=get(other.get(i),"normalBuffer"),"per-instance position and normal wrappers are independently owned");
            check((Boolean)get(own.get(i),"needUpdateVBO")&&!(Boolean)get(own.get(i),"allowSlim"),"private native VBO is marked for upload and retains CPU data for terrain changes");
            check(own.get(i).isOptimized()&&own.get(i).getIndexCount()>0,"native optimized index topology survives shaping");
        }
        for(int i=0;i<6;i++) {
            RailVehicleTerrainClientSmoke.setupWorld(i%2==0?0f:.5f,0,8,0,0,8);conform(rising);verifyMesh(rising,2,0);
        }
        RailVehicleTerrainClientSmoke.setupWorld(0,0,8,0,0,8);conform(rising);
        check(Arrays.deepEquals(original,snapshot(rising.getModelWrapper())),"repeated terraform changes restore exact original flat positions without accumulating deformation");
    }

    private static void exclusions(Resources resources) throws Exception {
        RailVehicleTerrainClientSmoke.setupWorld(.25f,0,8,.25f,0,8);
        for(String name:new String[]{"model.wurmrail.locomotive.","model.wurmrail.cart.","model.wurmrail.platform.pickup.","model.wurmrail.alignment.17.","model.wurmrail.signal.0.red.","model.wurmrail.track.fake.","model.cart.large."}) {
            GroundItemCellRenderable item=fixture(name,0,46,46);
            check(item.wantNewModelCopy(),"non-rail models retain their original native copy policy");
            check(!(Boolean)call(item,"wurmRailConformTrack",new Class<?>[]{long.class},now+=1000000000L),"unrelated models and malformed masks keep native behavior: "+name);
        }
        GroundItemCellRenderable rail=make("model.wurmrail.track.68.",0,46,46,load(resources,"model.wurmrail.track.68"));
        set(rail,"parentRenderable",fixture("model.cart.large.",0,46,46));
        check(!(Boolean)call(rail,"wurmRailConformTrack",new Class<?>[]{long.class},now+=1000000000L),"contained railway sections retain native placement");
    }

    private static void loaderIsolation(Resources resources) throws Exception {
        String name="model.wurmrail.track.68";ModelResourceWrapper base=load(resources,name);
        float[][] original=snapshot(base);
        com.wurmonline.client.renderer.model.ModelResourceLoader.storeWeakReference(base.getModelData(),base.getResourceUrl(),base);
        List<GroundItemCellRenderable> rails=new ArrayList<GroundItemCellRenderable>();
        for(float grade:new float[]{.5f,-.5f,0,.25f,-.25f}) {
            RailVehicleTerrainClientSmoke.setupWorld(grade,0,8,grade,0,8);
            ModelResourceWrapper fromCache=com.wurmonline.client.renderer.model.ModelResourceLoader.getModel(name+".");
            check(Arrays.deepEquals(original,snapshot(fromCache)),"actual native URL-cache lookup returns pristine art before each opposite-grade instance");
            GroundItemCellRenderable item=fixture(name+".",0,46,46);fromCache.addLoadListener(item);conform(item);rails.add(item);verifyMesh(item,2,0);
            ModelResourceWrapper after=com.wurmonline.client.renderer.model.ModelResourceLoader.getModel(name+".");
            check(after!=item.getModelWrapper()&&Arrays.deepEquals(original,snapshot(after)),"private terrain wrapper cannot replace the native URL cache with deformed geometry");
            check(after==fromCache&&!item.wantNewModelCopy(),"rail callbacks avoid native intermediate clones and preserve a single pristine URL-cache source");
            check(get(item.getModelWrapper(),"wurmRailTerrainSource")!=null,"a live private rail keeps its pristine source alive in the native weak cache");
        }
        Class<?> loader=com.wurmonline.client.renderer.model.ModelResourceLoader.class;
        Field queueField=loader.getDeclaredField("wurmRailTerrainModels");queueField.setAccessible(true);
        Map<?,?> queued=(Map<?,?>)queueField.get(null);
        ModelResourceWrapper disposable=(ModelResourceWrapper)call(base,"wurmRailPrivateTerrainCopy",new Class<?>[0]);
        Object data=disposable.getModelData();java.lang.ref.Reference<?> reference=null;
        for(Map.Entry<?,?> entry:queued.entrySet())if(entry.getValue()==data)reference=(java.lang.ref.Reference<?>)entry.getKey();
        check(reference!=null,"private wrappers register their model in the native-compatible weak cleanup lifecycle");
        ModelResourceWrapper before=com.wurmonline.client.renderer.model.ModelResourceLoader.getModel(name+".");
        reference.clear();reference.enqueue();com.wurmonline.client.renderer.model.ModelResourceLoader.unload();
        check(!queued.containsKey(reference),"native resource cleanup drains the collected private model");
        check(com.wurmonline.client.renderer.model.ModelResourceLoader.getModel(name+".")==before&&Arrays.deepEquals(original,snapshot(before)),"private model cleanup cannot evict or mutate the pristine native URL cache");
        for(GroundItemCellRenderable rail:rails)check(rail.getModelWrapper().isLoaded(),"cleanup preserves every live rail instance");
    }

    private static void unavailableAndPortal(Resources resources) throws Exception {
        World world=RailVehicleTerrainClientSmoke.setupWorld(.5f,0,8,.5f,0,8);
        GroundItemCellRenderable cave=make("model.wurmrail.track.68.",-1,46,46,load(resources,"model.wurmrail.track.68"));conform(cave);
        float[][] before=snapshot(cave.getModelWrapper());
        set(world.getCaveBuffer(),"minx",(short)100);conform(cave);
        check(Arrays.deepEquals(before,snapshot(cave.getModelWrapper())),"unavailable cave profile retains the last valid shape without partial deformation");
        world=RailVehicleTerrainClientSmoke.setupWorld(0,0,40,.5f,0,8);
        byte[] types=(byte[])get(world.getCaveBuffer(),"types");
        types[world.getCaveBuffer().getOffset(12,11)]=com.wurmonline.mesh.Tiles.Tile.TILE_CAVE_WALL.id;
        conform(cave);verifyMesh(cave,2,-1);
        check(cave.getHPos()<10,"the tunnel mouth samples its continuous cave floor without snapping to the surface roof");
        before=snapshot(cave.getModelWrapper());types[world.getCaveBuffer().getOffset(11,11)]=com.wurmonline.mesh.Tiles.Tile.TILE_CAVE_WALL.id;
        conform(cave);check(Arrays.deepEquals(before,snapshot(cave.getModelWrapper())),"a solid cave at the track centre cannot supply a new rail profile");
    }

    private static void cacheAndReplacement(Resources resources) throws Exception {
        RailVehicleTerrainClientSmoke.setupWorld(.5f,0,8,.5f,0,8);
        ModelResourceWrapper source=load(resources,"model.wurmrail.track.68");float[][] original=snapshot(source);
        List<GroundItemCellRenderable> rails=new ArrayList<GroundItemCellRenderable>();List<Object> profiles=new ArrayList<Object>();
        for(int i=0;i<100;i++) {
            GroundItemCellRenderable rail=make("model.wurmrail.track.68.",0,46,46,source);conform(rail);rails.add(rail);
            Object helper=get(rail,"wurmRailTrackTerrain");profiles.add(get(helper,"previous"));set(helper,"nextCheck",Long.MAX_VALUE);
            for(TriangleMesh mesh:meshes(rail.getModelWrapper()))set(mesh,"needUpdateVBO",false);
        }
        long start=System.nanoTime();
        for(int frame=0;frame<60;frame++)for(GroundItemCellRenderable rail:rails)rail.prepareRender();
        long elapsed=System.nanoTime()-start;
        for(int i=0;i<rails.size();i++) {
            GroundItemCellRenderable rail=rails.get(i);Object helper=get(rail,"wurmRailTrackTerrain");
            check(profiles.get(i)==get(helper,"previous"),"cached native preparation allocates no new terrain profiles between polls");
            for(TriangleMesh mesh:meshes(rail.getModelWrapper()))check(!(Boolean)get(mesh,"needUpdateVBO"),"cached frame preparation cannot repeatedly invalidate private GPU vertices");
            set(helper,"nextCheck",0L);rail.prepareRender();
            check(profiles.get(i)==get(helper,"previous"),"an unchanged terrain poll preserves the existing profile and geometry");
        }
        check(Arrays.deepEquals(original,snapshot(source)),"one hundred independently shaped instances leave the shared source untouched");
        System.out.println("PASS native rail preparation cache: 100 visible instances x 60 frames, no mesh/profile rebuilds ("+(elapsed/1000000L)+" ms CPU)");
        for(int state=0;state<5;state++) {
            GroundItemCellRenderable rail=make("model.wurmrail.track.68.",0,46,46,source);conform(rail);
            if(state==0){set(rail,"modelName",new SecureStrings("model.cart.large."));((GroundItemData)get(rail,"item")).setModelName("model.cart.large.");}
            if(state==1)set(rail,"onGround",false);
            if(state==2)set(rail,"parentRenderable",fixture("model.cart.large.",0,46,46));
            if(state==3)set(rail,"scale",.5f);
            if(state==4){set(rail,"layer",1);((GroundItemData)get(rail,"item")).setLayer(1);}
            set(rail,"pitch",7f);set(rail,"roll",9f);set(rail,"h",20f);
            call(rail,"wurmRailConformTrack",new Class<?>[]{long.class},System.nanoTime());
            check(!(Boolean)get(rail,"wurmRailTrackReady")&&get(rail,"wurmRailTrackTerrain")==null,"changing to a normal, carried, parented, scaled or elevated model releases rail pose ownership");
            check(rail.getPitch()==7f&&rail.getRoll()==9f&&((Float)get(rail,"h"))==20f,"released railway state cannot overwrite native height or tilt");
            check(Arrays.deepEquals(original,snapshot(rail.getModelWrapper())),"released rail instance restores original artwork before native placement resumes");
        }
    }

    private static void verifyMesh(GroundItemCellRenderable item,int axis,int layer) throws Exception {
        check(item.getPitch()==0&&item.getRoll()==0,"native rigid tilt is disabled only after a complete rail profile is ready");
        AbstractColladaModelData data=(AbstractColladaModelData)item.getModelWrapper().getModelData();
        Matrix matrix=item.getWorldModelMatrix();int count=0;
        for(TriangleMesh mesh:meshes(item.getModelWrapper())) {
            FloatBuffer p=mesh.getVertexBuffer(),n=mesh.getNormalBuffer();
            for(int at=0;at<p.limit();at+=3) {
                Vector3f world=new Vector3f(p.get(at),p.get(at+1),p.get(at+2));matrix.transformInPlace(world);
                float along=(p.get(at)*DX[axis]+p.get(at+2)*DY[axis])/(DX[axis]*DX[axis]+DY[axis]*DY[axis]);
                along=Math.max(-2,Math.min(2,along));
                float floor=floor(item.getXPos()+along*DX[axis],item.getYPos()+along*DY[axis],layer);
                check(Math.abs(world.x-(item.getXPos()+p.get(at)))<.0001f&&Math.abs(world.z-(item.getYPos()+p.get(at+2)))<.0001f,"slopes preserve every rail vertex's authored horizontal position");
                // Track geometry begins at y=.015 and ends at .21. The inverse
                // transform yields an original component inside those limits.
                float original=world.y-floor;
                check(original>.014f&&original<.211f,"the rail/sleeper base follows the actual longitudinal floor profile without banking");
                float length=n.get(at)*n.get(at)+n.get(at+1)*n.get(at+1)+n.get(at+2)*n.get(at+2);
                check(Float.isFinite(length)&&Math.abs(length-1)<.0001f,"shaped native normals remain finite unit vectors");
                count++;
            }
        }
        check(count>100&&data.getBounds()!=null&&data.getPickingBounds()!=null,"actual meshes remain renderable and selectable");
    }

    private static List<float[]> railEnd(GroundItemCellRenderable item,int axis,int side) throws Exception {
        List<float[]> result=new ArrayList<float[]>();Matrix matrix=item.getWorldModelMatrix();
        for(TriangleMesh mesh:meshes(item.getModelWrapper())) {
            if(!"rail".equals(mesh.getColladaMaterial().getName()))continue;
            FloatBuffer points=mesh.getVertexBuffer();
            for(int i=0;i<points.limit();i+=3) {
                float t=(points.get(i)*DX[axis]+points.get(i+2)*DY[axis])/(DX[axis]*DX[axis]+DY[axis]*DY[axis]);
                if(Math.abs(t-side*2)>.00001f)continue;
                Vector3f p=new Vector3f(points.get(i),points.get(i+1),points.get(i+2));matrix.transformInPlace(p);
                result.add(new float[]{p.x,p.y,p.z});
            }
        }return result;
    }

    private static ModelResourceWrapper load(Resources resources,String name) throws Exception {
        Field manager=Class.forName("com.wurmonline.client.WurmClientBase").getDeclaredField("resourceManager");manager.setAccessible(true);manager.set(null,resources);
        ResourceUrl url=resources.getResource(name);ColladaModel model;
        try(InputStream source=url.openStream()) {model=ColladaImporter.loadModel(url.toString(),source);}
        model.optimize();StaticColladaModelData data=new StaticColladaModelData(url,model);
        ModelDataBounds bounds=model.calculateBoundingBox();set(data,"bounds",bounds);set(data,"pickingBound",new ModelDataBounds(bounds));
        ModelResourceWrapper wrapper=new ModelResourceWrapper(url);wrapper.setModel(data);sourceFixtures.add(wrapper);return wrapper;
    }
    private static GroundItemCellRenderable make(String name,int layer,float x,float y,ModelResourceWrapper wrapper) throws Exception {
        GroundItemCellRenderable item=fixture(name,layer,x,y);
        // Exercise the genuine callback, including its cold cached-wrapper clone.
        item.modelLoaded(wrapper);return item;
    }
    private static GroundItemCellRenderable fixture(String name,int layer,float x,float y) throws Exception {
        if(CellRenderable.world!=null&&get(CellRenderable.world,"worldRenderer")==null)
            set(CellRenderable.world,"worldRenderer",allocate(Class.forName("com.wurmonline.client.renderer.WorldRender")));
        GroundItemCellRenderable item=(GroundItemCellRenderable)allocate(GroundItemCellRenderable.class);
        GroundItemData data=(GroundItemData)allocate(GroundItemData.class);
        set(data,"modelName",new SecureStrings(name));set(data,"layer",layer);set(data,"scale",1f);set(item,"item",data);
        set(item,"modelName",new SecureStrings(name));set(item,"x",x);set(item,"y",y);set(item,"h",8f);set(item,"scale",1f);set(item,"onGround",true);
        set(item,"layer",layer);set(item,"volume",new Volume());set(item,"terrainNormal",new float[3]);set(item,"calcNormal",new RenderVector(0,0,1));
        set(item,"s",new RenderVector(0,0,0));set(item,"pendingEffects",new ArrayList<Object>());
        set(item,"renderTransform",new Transform());set(item,"rotQuat",new Quaternion());set(item,"renderModel",new Matrix());set(item,"worldModel",new Matrix());set(item,"tempModel",new Matrix());set(item,"actualPos",new Vector3f());
        set(item,"renderColor",new com.wurmonline.client.renderer.Color(1,1,1,1));set(item,"renderState",new com.wurmonline.client.renderer.backend.RenderState());
        return item;
    }
    private static void conform(GroundItemCellRenderable item) throws Exception {
        if(CellRenderable.world!=null&&get(CellRenderable.world,"worldRenderer")==null)
            set(CellRenderable.world,"worldRenderer",allocate(Class.forName("com.wurmonline.client.renderer.WorldRender")));
        Object helper=get(item,"wurmRailTrackTerrain");if(helper!=null)set(helper,"nextCheck",0L);
        call(item,"wurmRailConformTrack",new Class<?>[]{long.class},System.nanoTime());call(item,"updateModelMatrix",new Class<?>[0]);
    }
    private static float floor(float x,float y,int layer) {return layer<0?CellRenderable.world.getCaveBuffer().getInterpolatedFloor(x,y):CellRenderable.world.getNearTerrainBuffer().getInterpolatedHeight(x,y);}
    private static List<TriangleMesh> meshes(ModelResourceWrapper wrapper) {
        List<TriangleMesh> result=new ArrayList<TriangleMesh>();for(ColladaGeometry geometry:((AbstractColladaModelData)wrapper.getModelData()).getModel().getGeometryArray())Collections.addAll(result,geometry.getTriangleMeshArray());return result;
    }
    private static float[][] snapshot(ModelResourceWrapper wrapper) {
        List<TriangleMesh> meshes=meshes(wrapper);float[][] result=new float[meshes.size()][];
        for(int i=0;i<meshes.size();i++){FloatBuffer p=meshes.get(i).getVertexBuffer();result[i]=new float[p.limit()];for(int j=0;j<p.limit();j++)result[i][j]=p.get(j);}return result;
    }
    private static float[][] normalSnapshot(ModelResourceWrapper wrapper) {
        List<TriangleMesh> meshes=meshes(wrapper);float[][] result=new float[meshes.size()][];
        for(int i=0;i<meshes.size();i++){FloatBuffer p=meshes.get(i).getNormalBuffer();result[i]=new float[p.limit()];for(int j=0;j<p.limit();j++)result[i][j]=p.get(j);}return result;
    }
    private static float distance(float[] a,float[] b) {return (float)Math.sqrt((a[0]-b[0])*(a[0]-b[0])+(a[1]-b[1])*(a[1]-b[1])+(a[2]-b[2])*(a[2]-b[2]));}
    private static Object allocate(Class<?> type) throws Exception {Field f=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");f.setAccessible(true);Object unsafe=f.get(null);return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);}
    private static Object get(Object object,String name) throws Exception {for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException missing){}throw new NoSuchFieldException(name);}
    private static void set(Object object,String name,Object value) throws Exception {for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);return;}catch(NoSuchFieldException missing){}throw new NoSuchFieldException(name);}
    private static Object call(Object object,String name,Class<?>[] types,Object... args) throws Exception {for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,args);}catch(NoSuchMethodException missing){}throw new NoSuchMethodException(name);}
    private static void check(boolean value,String message) {assertions++;if(!value)throw new AssertionError(message);}
}
