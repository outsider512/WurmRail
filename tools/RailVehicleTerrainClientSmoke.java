import com.wurmonline.client.game.CaveDataBuffer;
import com.wurmonline.client.game.NearTerrainDataBuffer;
import com.wurmonline.client.game.PlayerObj;
import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.CreatureData;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.cell.MountOffsets;
import com.wurmonline.client.renderer.model.ModelDataBounds;
import com.wurmonline.client.renderer.model.ModelResourceWrapper;
import com.wurmonline.client.renderer.model.collada.ColladaModelData;
import com.wurmonline.client.renderer.model.collada.importer.ColladaModel;
import com.wurmonline.client.timing.IFloat;
import com.wurmonline.client.timing.IRotFloat;
import com.wurmonline.client.util.SecureStrings;
import com.wurmonline.mesh.Tiles;
import java.lang.reflect.*;
import java.util.*;

/** Actual patched native terrain sampling, pitch targets and angle interpolation without a GPU. */
public final class RailVehicleTerrainClientSmoke {
    private static int assertions;
    private static long nextId=840000000L;
    private RailVehicleTerrainClientSmoke() { }

    public static void run() throws Exception {
        World old=CellRenderable.world;
        try {
            for(int layer:new int[]{0,-1})for(String model:new String[]{"model.wurmrail.locomotive.","model.wurmrail.cart.large.","model.wurmrail.tankcar.","model.wurmrail.logcar."})
                for(int heading=0;heading<8;heading++)slopeCase(model,layer,heading*45f);
            exclusions();gradeAndHeadingChanges();frameRateIndependentTilt();
            System.out.println("PASS native smooth rail terrain transitions (24/60/144 Hz, sustained 3 FPS, pitch/roll crests, bounded pause recovery, stopped settling, native DOF ownership, teleport reset)");
            portalAndInvalidData();
            System.out.println("PASS native rail-only terrain pitch/roll on all eight headings, smooth grade transitions at 24/60/144 Hz, sustained 3 FPS, repeated getters, stopped settling, bounded pause recovery and teleport reset, surface and tunnel slopes, occupied vehicles, preserved authoritative Z, portal roof/data guards and unchanged other models ("+assertions+" assertions)");
        } finally {CellRenderable.world=old;}
    }

    private static void slopeCase(String model,int layer,float yaw) throws Exception {
        World world=setupWorld(.2f,.1f,5f,.2f,.1f,5f);
        CreatureCellRenderable vehicle=makeVehicle(model,layer,46f,46f,5f,yaw);
        call(vehicle,"determineCreatureRotType",new Class<?>[0]);
        check((Boolean)get(vehicle,"pitchModel")&&(Boolean)get(vehicle,"rollModel"),"both railway tilt axes are enabled independently of the locomotive's tall cab bounds");
        set(vehicle,"hasPlayerBodyAttached",true);
        check(!vehicle.shouldRotatePlayerBodyMounted(),"native fixture retains the artwork's mounted-body rotation guard");
        float[] before=((float[])get(vehicle,"posTargets")).clone();
        call(vehicle,"setCreaturePitchRollTarget",new Class<?>[]{float[].class},new float[]{0,0,1});
        float[] targets=(float[])get(vehicle,"posTargets");
        check(Math.hypot(targets[4],targets[5])>5&&Float.isFinite(targets[4])&&Float.isFinite(targets[5]),
                "explicit-height occupied railway vehicle receives a finite slope tilt rather than the native up-normal flattening");
        check(!vehicle.isOnGround()&&Arrays.equals(Arrays.copyOf(before,4),Arrays.copyOf(targets,4)),
                "terrain tilt leaves authoritative XYZ, heading targets and explicit-height mode unchanged");

        float[] normal=new float[3];
        if(layer==0)world.getNearTerrainBuffer().getRealNormal(46,46,normal);else world.getCaveBuffer().getNormal(46,46,normal);
        CreatureCellRenderable nativeCart=makeVehicle("model.cart.large.",layer,46,46,5,yaw);
        set(nativeCart,"pitchModel",true);set(nativeCart,"rollModel",true);set(nativeCart,"hasPlayerBodyAttached",false);
        call(nativeCart,"setCreaturePitchRollTarget",new Class<?>[]{float[].class},normal);
        float[] expected=(float[])get(nativeCart,"posTargets");
        check(Math.abs(targets[4]-expected[4])<.002f&&Math.abs(targets[5]-expected[5])<.002f,
                "rail slope targets use the actual native ground normal and matching yaw basis at heading "+yaw+" on layer "+layer);
        for(int i=0;i<16;i++) {
            call(vehicle,"moveDOFtoTarget",new Class<?>[]{int.class,boolean.class,float.class},4,true,3f);
            call(vehicle,"moveDOFtoTarget",new Class<?>[]{int.class,boolean.class,float.class},5,true,3f);
            call(vehicle,"setRenderPos",new Class<?>[]{float.class},0f);
        }
        check(Math.hypot(angleDistance(vehicle.getPitch(),0),angleDistance(vehicle.getRoll(),0))>5&&Float.isFinite(vehicle.getPitch())&&Float.isFinite(vehicle.getRoll()),
                "native angle interpolation and render-position path visibly pitch and roll the railway model");
        check(vehicle.getHPosValue(0f)==5f&&!vehicle.isOnGround(),"rendered vehicle height remains the authoritative rail floor during native tilt interpolation");
    }

    private static void exclusions() throws Exception {
        setupWorld(.2f,.1f,5f,.2f,.1f,5f);
        for(String model:new String[]{"model.creature.humanoid.human.player.male","model.cart.large.","model.wurmrail.track.17.","model.wurmrail.cartoon.","model.wurmrail.locomotivefake.","model.wurmrail.tankcarriage.","model.wurmrail.logcarrier."}) {
            CreatureCellRenderable other=makeVehicle(model,0,46,46,5,90);
            call(other,"determineCreatureRotType",new Class<?>[0]);
            check(!(Boolean)call(other,"wurmRailIsRailVehicle",new Class<?>[0])&&!(Boolean)get(other,"rollModel"),
                    "other creatures, native carts, track scenery and prefix lookalikes keep native model-bound rotation flags");
            set(other,"hasPlayerBodyAttached",true);
            call(other,"setCreaturePitchRollTarget",new Class<?>[]{float[].class},new float[]{-.2f,0,.98f});
            float[] targets=(float[])get(other,"posTargets");check(targets[4]==0&&targets[5]==0,"unrelated mounted bodies retain the original native rotation guard");
        }
    }

    private static void gradeAndHeadingChanges() throws Exception {
        for(int layer:new int[]{0,-1})for(String model:new String[]{"model.wurmrail.locomotive.large.","model.wurmrail.cart.large.","model.wurmrail.tankcar.large.","model.wurmrail.logcar.large."}) {
            setupWorld(.2f,.1f,5f,.2f,.1f,5f);
            CreatureCellRenderable car=makeVehicle(model,layer,46,46,5,90);
            long start=1000000000L;
            set(car,"wurmRailTiltReady",false);updateTilt(car,start);
            float[] targets=(float[])get(car,"posTargets");IFloat[] render=(IFloat[])get(car,"interTickPos");
            float initialPitch=targets[4],initialRoll=targets[5];
            check(angleDistance(render[4].getValue(0),initialPitch)<.001f&&angleDistance(render[5].getValue(0),initialRoll)<.001f,
                    "the first valid slope initializes the train's correct terrain pose without a flat spawn animation");
            setupWorld(-.2f,-.1f,5f,-.2f,-.1f,5f);updateTilt(car,start);
            check(initialPitch*targets[4]<0&&initialRoll*targets[5]<0,
                    "crossing a crest supplies new signed pitch and roll targets from the actual terrain");
            check(angleDistance(render[4].getValue(0),initialPitch)<.001f&&angleDistance(render[5].getValue(0),initialRoll)<.001f,
                    "a new slope at the same instant cannot snap the visible pitch or roll to its new target");
            float[] goal={targets[4],targets[5]},previous={signed(render[4].getValue()),signed(render[5].getValue())};
            float[] initial={initialPitch,initialRoll};
            for(int frame=1;frame<=27;frame++) {
                updateTilt(car,start+Math.round(frame*1000000000.0/60));
                for(int axis=0;axis<2;axis++) {
                    float visible=signed(render[axis+4].getValue()),oldError=Math.abs(previous[axis]-goal[axis]),error=Math.abs(visible-goal[axis]);
                    check(Float.isFinite(visible)&&error<oldError&&visible>=Math.min(initial[axis],goal[axis])-.001f&&visible<=Math.max(initial[axis],goal[axis])+.001f,
                            "each frame approaches the new grade monotonically without overshoot or a 360-degree angle wrap");
                    if(frame==1)check(Math.abs(visible-initial[axis])<Math.abs(goal[axis]-initial[axis])*.2,
                            "the first 60-Hz frame moves only a small part of a sharp tile-angle change");
                    previous[axis]=visible;
                }
            }
            for(int axis=0;axis<2;axis++)check(Math.abs(previous[axis]-goal[axis])<Math.abs(initial[axis]-goal[axis])*.06,
                    "the visible vehicle reaches at least 94 percent of a new grade within 450ms");
            float pitchBefore=render[4].getValue(),rollBefore=render[5].getValue();
            for(int repeat=0;repeat<100;repeat++)updateTilt(car,start+450000000L);
            check(render[4].getValue()==pitchBefore&&render[5].getValue()==rollBefore,
                    "repeated pose consumers at one timestamp cannot accelerate smoothing");
            for(int axis=4;axis<=5;axis++)call(car,"moveDOFtoTarget",new Class<?>[]{int.class,boolean.class,float.class},axis,true,3f);
            check(render[4].getValue()==pitchBefore&&render[5].getValue()==rollBefore,
                    "native tick interpolation cannot apply a second angle filter over the railway's smoothed pose");
            for(int frame=28;frame<=120;frame++)updateTilt(car,start+Math.round(frame*1000000000.0/60));
            check(angleDistance(render[4].getValue(),goal[0])<.001f&&angleDistance(render[5].getValue(),goal[1])<.001f,
                    "a stopped train finishes settling to its grade without new movement packets");

            render[3].reallySetValue(270);updateTilt(car,start+2000000000L);
            check(Math.abs(targets[4]+goal[0])<.002f&&Math.abs(targets[5]+goal[1])<.002f,
                    "terrain goals use the correct absolute yaw basis when the physical heading changes");
            check(angleDistance(render[4].getValue(),goal[0])<.001f&&angleDistance(render[5].getValue(),goal[1])<.001f,
                    "changing the yaw basis preserves the current visible tilt before easing to its new goal");
            check(render[0].getValue()==46&&render[1].getValue()==46&&render[2].getValue()==5&&render[3].getValue()==270,
                    "tilt transitions never overwrite sampled XYZ, physical heading or authoritative rail height");

            // A resumed frame makes bounded progress instead of consuming the
            // entire pause as one huge step, including on a persistently slow renderer.
            pitchBefore=render[4].getValue();rollBefore=render[5].getValue();
            updateTilt(car,start+5000000000L);
            float[] beforePause={pitchBefore,rollBefore};
            for(int axis=0;axis<2;axis++) {
                float travelled=angleDistance(render[axis+4].getValue(),beforePause[axis]);
                check(travelled>.01f&&travelled<angleDistance(beforePause[axis],targets[axis+4])*.3f,
                        "a long render pause resumes with a bounded part of the new grade instead of an instant angle change");
            }
            pitchBefore=render[4].getValue();
            updateTilt(car,start+5016666667L);
            check(angleDistance(render[4].getValue(),pitchBefore)>.01f&&angleDistance(render[4].getValue(),targets[4])>1f,
                    "ordinary gradual tilt resumes on the next frame after a long render pause");

            setupWorld(.35f,-.15f,5f,.35f,-.15f,5f);
            car.setPosImmediately(46,46,5,false,true);
            updateTilt(car,System.nanoTime());
            check(angleDistance(render[4].getValue(),targets[4])<.001f&&angleDistance(render[5].getValue(),targets[5])<.001f,
                    "an actual native teleport initializes the new slope instead of easing from a distant train pose");
        }
    }

    private static void frameRateIndependentTilt() throws Exception {
        for(int layer:new int[]{0,-1}) {
            float[] reference=null;
            for(int rate:new int[]{24,60,144}) {
                setupWorld(.2f,.1f,5f,.2f,.1f,5f);
                CreatureCellRenderable car=makeVehicle("model.wurmrail.cart.large.",layer,46,46,5,90);
                long start=1000000000L;set(car,"wurmRailTiltReady",false);updateTilt(car,start);
                setupWorld(-.2f,-.1f,5f,-.2f,-.1f,5f);updateTilt(car,start);
                for(int frame=1;frame<=rate/2;frame++) {
                    long now=start+Math.round(frame*1000000000.0/rate);updateTilt(car,now);
                    for(int duplicate=0;duplicate<8;duplicate++)updateTilt(car,now);
                }
                IFloat[] render=(IFloat[])get(car,"interTickPos");
                float[] actual={signed(render[4].getValue()),signed(render[5].getValue())};
                if(reference==null)reference=actual;
                else for(int axis=0;axis<2;axis++)check(Math.abs(actual[axis]-reference[axis])<.0001f,
                        "equal elapsed time gives the same smoothed angle at 24, 60 and 144 Hz despite repeated pose consumers");
            }
            setupWorld(.2f,.1f,5f,.2f,.1f,5f);
            CreatureCellRenderable slow=makeVehicle("model.wurmrail.cart.large.",layer,46,46,5,90);
            long start=1000000000L;set(slow,"wurmRailTiltReady",false);updateTilt(slow,start);
            IFloat[] render=(IFloat[])get(slow,"interTickPos");
            float[] previous={signed(render[4].getValue()),signed(render[5].getValue())};
            setupWorld(-.2f,-.1f,5f,-.2f,-.1f,5f);updateTilt(slow,start);
            float[] targets=(float[])get(slow,"posTargets");
            for(int frame=1;frame<=36;frame++) {
                updateTilt(slow,start+frame*333333333L);
                for(int axis=0;axis<2;axis++) {
                    float actual=signed(render[axis+4].getValue());
                    float oldError=Math.abs(previous[axis]-targets[axis+4]),error=Math.abs(actual-targets[axis+4]);
                    check(error<=oldError+.00005f&&(oldError<.001f||error<oldError)&&Math.abs(actual-previous[axis])<oldError*.3f+.00005f,
                            "sustained 3-FPS rendering continues to approach the target with bounded steps on both tilt axes: layer="+layer+" frame="+frame+" axis="+axis+" actual="+actual+" previous="+previous[axis]+" goal="+targets[axis+4]+" oldError="+oldError+" error="+error);
                    previous[axis]=actual;
                }
            }
            check(angleDistance(render[4].getValue(),targets[4])<.001f&&angleDistance(render[5].getValue(),targets[5])<.001f,
                    "a train eventually settles to its grade even when every frame exceeds the normal pause threshold");
        }
    }

    private static void updateTilt(CreatureCellRenderable car,long now) throws Exception {
        call(car,"wurmRailUpdateTerrainTilt",new Class<?>[]{long.class},now);
    }
    private static float signed(float angle) {return ((angle+540)%360)-180;}

    private static void portalAndInvalidData() throws Exception {
        setupWorld(.2f,.1f,5f,.2f,.1f,5f);CreatureCellRenderable car=makeVehicle("model.wurmrail.cart.",0,46,46,5,90);
        call(car,"wurmRailUpdateTerrainTilt",new Class<?>[0]);float[] targets=(float[])get(car,"posTargets");float pitch=targets[4],roll=targets[5];
        setupWorld(.6f,.1f,35f,.2f,.1f,5f);
        call(car,"wurmRailUpdateTerrainTilt",new Class<?>[0]);
        check(targets[4]==pitch&&targets[5]==roll&&car.getHPosValue(0f)==5,
                "interpolated XY under a wrong-layer raised tunnel roof keeps prior finite tilt and the authoritative floor height");
        set(car,"layer",-1);call(car,"wurmRailUpdateTerrainTilt",new Class<?>[0]);
        check(Math.hypot(targets[4],targets[5])>5&&car.getHPosValue(0f)==5,"valid cave floor resumes slope sampling despite the raised surface roof");
        World world=CellRenderable.world;short[] floors=(short[])get(world.getCaveBuffer(),"floors");Arrays.fill(floors,(short)-100);
        pitch=targets[4];roll=targets[5];call(car,"wurmRailUpdateTerrainTilt",new Class<?>[0]);
        check(targets[4]==pitch&&targets[5]==roll,"unloaded cave data cannot replace the last valid rail tilt");
        setupWorld(.2f,.1f,5f,.2f,.1f,5f);byte[] types=(byte[])get(CellRenderable.world.getCaveBuffer(),"types");Arrays.fill(types,Tiles.Tile.TILE_CAVE_WALL.id);
        call(car,"wurmRailUpdateTerrainTilt",new Class<?>[0]);check(targets[4]==pitch&&targets[5]==roll,"solid rock outside a cave lip is not sampled as a tunnel floor");
        targets[4]=Float.NaN;targets[5]=Float.POSITIVE_INFINITY;IFloat[] render=(IFloat[])get(car,"interTickPos");
        render[4].reallySetValue(Float.NaN);render[5].reallySetValue(Float.NEGATIVE_INFINITY);call(car,"wurmRailUpdateTerrainTilt",new Class<?>[0]);
        check(Float.isFinite(targets[4])&&Float.isFinite(targets[5])&&Float.isFinite(render[4].getValue(0))&&Float.isFinite(render[5].getValue(0)),
                "invalid saved and interpolated angles are repaired even while terrain is unavailable");
    }

    /** Reusable socket/GPU-free native world, with actual surface/cave buffer sampling. */
    public static World setupWorld(float surfaceX,float surfaceY,float surfaceBase,float caveX,float caveY,float caveBase) throws Exception {
        World world=(World)allocate(World.class);NearTerrainDataBuffer surface=new NearTerrainDataBuffer();
        set(world,"nearTerrainBuffer",surface);set(world,"player",allocate(PlayerObj.class));
        Constructor<CaveDataBuffer> constructor=CaveDataBuffer.class.getDeclaredConstructor(World.class);constructor.setAccessible(true);
        CaveDataBuffer cave=constructor.newInstance(world);set(world,"caveBuffer",cave);
        float[] heights=(float[])get(surface,"heights");short[] floors=(short[])get(cave,"floors");byte[] types=(byte[])get(cave,"types");
        for(int x=8;x<=20;x++)for(int y=8;y<=20;y++) {
            heights[(x&511)|((y&511)<<9)]=surfaceBase+surfaceX*(x*4-46)+surfaceY*(y*4-46);
            int at=cave.getOffset(x,y);floors[at]=(short)Math.round((caveBase+caveX*(x*4-46)+caveY*(y*4-46))*10);types[at]=Tiles.Tile.TILE_CAVE.id;
        }
        set(cave,"minx",(short)8);set(cave,"maxx",(short)20);set(cave,"miny",(short)8);set(cave,"maxy",(short)20);
        CellRenderable.world=world;return world;
    }

    /** Reusable native renderable state; constructors that register a real renderer are intentionally bypassed. */
    public static CreatureCellRenderable makeVehicle(String model,int layer,float x,float y,float h,float yaw) throws Exception {
        CreatureCellRenderable vehicle=(CreatureCellRenderable)allocate(CreatureCellRenderable.class);
        set(vehicle,"modelName",new SecureStrings(model));set(vehicle,"layer",layer);set(vehicle,"onGround",false);
        IFloat[] values=new IFloat[6];for(int i=0;i<6;i++)values[i]=i<3?new IFloat():new IRotFloat(0);
        float[] positions={x,y,h,yaw,0,0};for(int i=0;i<6;i++)values[i].reallySetValue(positions[i]);
        set(vehicle,"interTickPos",values);set(vehicle,"posTargets",positions.clone());set(vehicle,"oldPosTargets",positions.clone());
        set(vehicle,"oldTargetDiff",new float[6]);set(vehicle,"posUpdateLastTick",new boolean[6]);set(vehicle,"terrainNormal",new float[3]);
        set(vehicle,"interTickOffset",new IFloat[]{new IFloat(),new IFloat(),new IFloat()});set(vehicle,"mountOffsets",new MountOffsets());
        CreatureData creature=(CreatureData)allocate(CreatureData.class);set(creature,"id",nextId+=256);set(creature,"modelName",new SecureStrings(model));
        set(creature,"name",new SecureStrings("Native terrain fixture"));set(creature,"layer",layer);set(creature,"bridgeId",-10L);
        set(vehicle,"creature",creature);set(vehicle,"pitchModel",true);set(vehicle,"rollModel",true);
        ColladaModel source=(ColladaModel)allocate(ColladaModel.class);set(source,"shouldNotRotateIfMounted",true);
        ColladaModelData data=(ColladaModelData)allocate(ColladaModelData.class);set(data,"model",source);set(data,"bounds",new ModelDataBounds(-1,0,-2,1,3,2));
        ModelResourceWrapper wrapper=(ModelResourceWrapper)allocate(ModelResourceWrapper.class);set(wrapper,"modelData",data);set(vehicle,"model",wrapper);
        call(vehicle,"setRenderPos",new Class<?>[]{float.class},0f);return vehicle;
    }
    public static Object get(Object object,String name) throws Exception {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException missing){}
        throw new NoSuchFieldException(name);
    }
    public static void set(Object object,String name,Object value) throws Exception {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);return;}catch(NoSuchFieldException missing){}
        throw new NoSuchFieldException(name);
    }
    public static Object call(Object object,String name,Class<?>[] args,Object... values) throws Exception {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{
            Method method=type.getDeclaredMethod(name,args);method.setAccessible(true);try{return method.invoke(object,values);}catch(InvocationTargetException wrapped){if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause();throw wrapped;}
        }catch(NoSuchMethodException missing){}
        throw new NoSuchMethodException(name);
    }
    private static Object allocate(Class<?> type) throws Exception {Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);}
    private static float angleDistance(float a,float b){return Math.abs(((a-b+540)%360)-180);}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
