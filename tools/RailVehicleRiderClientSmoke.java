import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.RenderVector;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.cell.MovementData;
import com.wurmonline.client.renderer.cell.Volume;
import com.wurmonline.client.renderer.model.ModelDataBounds;
import com.wurmonline.client.sound.MovementSound;
import com.wurmonline.client.timing.IFloat;
import org.wurmrail.client.RailVehicleMotion;

/** Real rider gameTick/tickCarried, including a child ticking before its rail carrier. */
public final class RailVehicleRiderClientSmoke {
    private static int assertions;
    private RailVehicleRiderClientSmoke() { }

    public static void run() throws Exception {
        World original=CellRenderable.world;
        try {
            for(int layer:new int[]{0,-1})for(float height:new float[]{0,5})
                for(float heading:new float[]{90,180})carriedFirst(layer,height,heading);
            for(int layer:new int[]{0,-1})for(String model:new String[]{"model.wurmrail.locomotive.large.","model.wurmrail.cart.large.","model.wurmrail.tankcar.","model.wurmrail.logcar."})
                for(int fps:new int[]{24,60,144})continuousSeatFrame(model,layer,fps);
            ordinaryMountAndUnmount();
            System.out.println("PASS native rider-first ticks and continuous 24/60/144-Hz seated camera/render frames across 20-Hz curved packets and changing grades, every native tick fraction, locomotive/cart/tank/log seats, surface/cave, ordinary-mount interpolation and dismount isolation ("+assertions+" assertions)");
        } finally { CellRenderable.world=original; }
    }

    /**
     * Native tickCarried runs at 24 Hz; rail playback and grade easing run per
     * rendered frame. A seat must use that frame's parent transform rather than
     * interpolate last tick's world-space seat offset again. Future monotonic
     * timestamps hold each snapshot still while all real getters inspect it;
     * this exercises the production packet buffer without wall-clock sleeps.
     */
    private static void continuousSeatFrame(String model,int layer,int fps) throws Exception {
        setupWorld(0,0,5);
        CreatureCellRenderable car=vehicle(model,layer,46,46,5,180);
        CreatureCellRenderable rider=vehicle("model.creature.humanoid.human.player.male",layer,46,46,5.55f,180);
        float[] local=model.contains("locomotive")?new float[]{.65f,-.48f,.55f}:new float[]{-.9f,1.2f,.8f};
        set(rider,"carrierCreature",car);set(rider,"offsets",local.clone());set(car,"hasHPosBeenUpdated",true);
        rider.gameTick();
        // Finish the original mount transition so a failure demonstrates
        // ongoing riding, rather than the initial boarding interpolation.
        IFloat[] offsets=(IFloat[])get(rider,"interTickOffset");
        for(IFloat value:offsets)value.reallySetValue(value.getValue());
        car.move(46,45.9f,5,180);
        RailVehicleMotion motion=(RailVehicleMotion)get(car,"wurmRailMotion");
        long start=System.nanoTime()+10000000000L;
        motion.reset(46,46,5,0,start);
        for(int packet=1;packet<=30;packet++) {
            double t=packet/30.0,u=1-t;
            float x=(float)(46+3*t*t),y=(float)(46-6*t+3*t*t),z=(float)(5+.2*Math.sin(Math.PI*t));
            float yaw=(float)Math.toDegrees(Math.atan2(t,u));
            motion.accept(x,y,z,yaw,start+packet*50000000L);
        }
        long nextTick=0;double largestOffsetChange=0;double[] previous=null;
        for(int frame=0;frame<=fps*3/2;frame++) {
            long elapsed=Math.round(frame*1000000000.0/fps),sample=start+elapsed+50000000L;
            if(frame==fps/3)setupWorld(.15f,-.1f,5);
            if(frame==fps)setupWorld(-.12f,.08f,5);
            call(car,"wurmRailApplyMotion",new Class<?>[]{long.class},sample);
            call(car,"wurmRailUpdateTerrainTilt",new Class<?>[]{long.class},sample);
            call(car,"setRenderPos",new Class<?>[]{float.class},0f);
            if(elapsed>=nextTick) {
                rider.gameTick();
                nextTick=(elapsed/41666667L+1)*41666667L;
            }
            IFloat[] parent=(IFloat[])get(car,"interTickPos");
            double[] expected=rotateSeat(local,parent[3].getValue(),parent[4].getValue(),parent[5].getValue());
            if(previous!=null)largestOffsetChange=Math.max(largestOffsetChange,Math.sqrt(
                    Math.pow(expected[0]-previous[0],2)+Math.pow(expected[1]-previous[1],2)+Math.pow(expected[2]-previous[2],2)));
            previous=expected;
            for(float alpha:new float[]{0,.25f,.5f,.75f,1}) {
                float[] camera={rider.getXPosValue(alpha),rider.getYPosValue(alpha),rider.getHPosValue(alpha)};
                for(int axis=0;axis<3;axis++)check(Math.abs(camera[axis]-parent[axis].getValue()-expected[axis])<.0002,
                        "seated camera stays in the current rail seat frame, axis "+axis+", frame "+frame+" at "+fps+" Hz, tick fraction "+alpha+", layer "+layer+", "+model);
                call(rider,"setRenderPos",new Class<?>[]{float.class},alpha);
                String[] fields={"x","y","h"};
                for(int axis=0;axis<3;axis++)check(Math.abs((Float)get(rider,fields[axis])-parent[axis].getValue()-expected[axis])<.0002,
                        "rendered rider shares the seated camera transform on axis "+axis+", frame "+frame+", tick fraction "+alpha);
            }
        }
        check(largestOffsetChange>.005&&largestOffsetChange<.15,
                "continuous regression exercises visible, smooth seat displacement as both heading and terrain tilt change");
        check(get(rider,"wurmRailMotion")==null,"continuous mounted camera never creates an independent movement buffer");
    }

    private static void ordinaryMountAndUnmount() throws Exception {
        setupWorld(.15f,.1f,5);
        CreatureCellRenderable ordinary=vehicle("model.cart.large.",0,46,46,5,180);
        CreatureCellRenderable rider=vehicle("model.creature.humanoid.human.player.male",0,47,48,6,180);
        set(rider,"carrierCreature",ordinary);
        IFloat[] offsets=(IFloat[])get(rider,"interTickOffset");
        for(int axis=0;axis<3;axis++){offsets[axis].reallySetValue(axis+.1f);offsets[axis].setValue(axis+.8f);}
        for(float alpha:new float[]{0,.25f,.5f,.75f,1}) {
            check(Math.abs(rider.getXPosValue(alpha)-(46+.1+.7*alpha))<.0001
                    &&Math.abs(rider.getYPosValue(alpha)-(46+1.1+.7*alpha))<.0001
                    &&Math.abs(rider.getHPosValue(alpha)-(5+2.1+.7*alpha))<.0001,
                    "ordinary mounts retain their native tick-offset interpolation at fraction "+alpha);
        }
        CreatureCellRenderable rail=vehicle("model.wurmrail.locomotive.",0,46,46,5,180);
        float[] local={.65f,-.48f,.55f};set(rider,"offsets",local.clone());set(rider,"carrierCreature",rail);
        call(rail,"setRenderPos",new Class<?>[]{float.class},0f);
        IFloat[] railPose=(IFloat[])get(rail,"interTickPos");
        double[] expected=rotateSeat(local,railPose[3].getValue(),railPose[4].getValue(),railPose[5].getValue());
        check(Math.abs(rider.getXPosValue(0)-(46+expected[0]))<.0002
                &&Math.abs(rider.getYPosValue(0)-(46+expected[1]))<.0002
                &&Math.abs(rider.getHPosValue(0)-(5+expected[2]))<.0002,
                "boarding a railway vehicle discards a previous ordinary mount's interpolated seat offset immediately");
        set(rider,"carrierCreature",null);
        IFloat[] own=(IFloat[])get(rider,"interTickPos");
        own[0].reallySetValue(47);own[1].reallySetValue(48);own[2].reallySetValue(6);
        for(float alpha:new float[]{0,.5f,1}) {
            check(rider.getXPosValue(alpha)==47&&rider.getYPosValue(alpha)==48&&rider.getHPosValue(alpha)==6,
                    "unmounted players immediately use their independent native pose");
            call(rider,"setRenderPos",new Class<?>[]{float.class},alpha);
            check((Float)get(rider,"x")==47&&(Float)get(rider,"y")==48&&(Float)get(rider,"h")==6,
                    "unmounted player rendering stops inheriting a previous carrier");
        }
    }

    private static void carriedFirst(int layer,float height,float clientYaw) throws Exception {
        setupWorld(0,0,height);
        CreatureCellRenderable car=vehicle("model.wurmrail.locomotive.",layer,46,46,height,180);
        CreatureCellRenderable rider=vehicle("model.creature.humanoid.human.player.male",layer,46,46,height+.55f,180);
        set(rider,"carrierCreature",car);
        // These are native seat-local offsets; tickCarried must calculate all
        // world offsets itself. No precomputed interTickOffset is supplied.
        float[] local={.65f,-.48f,.55f};set(rider,"offsets",local.clone());
        set(car,"hasHPosBeenUpdated",true);
        rider.gameTick();
        IFloat[] offsets=(IFloat[])get(rider,"interTickOffset");
        check(Math.hypot(offsets[0].getValue(),offsets[1].getValue())>.7,"unmodified native tickCarried computes the initial seat-local world offset");

        float slopeX=height==0?0:.2f,slopeY=height==0?0:.1f;
        setupWorld(slopeX,slopeY,height);
        float x=clientYaw==90?46.5f:46f,y=clientYaw==180?45.5f:46f;
        float z=height+slopeX*(x-46)+slopeY*(y-46);
        car.move(x,y,z,clientYaw);
        RailVehicleMotion motion=(RailVehicleMotion)get(car,"wurmRailMotion");
        long now=System.nanoTime();motion.reset(46,46,height,0,now-300000000L);
        motion.accept(x,y,z,180-clientYaw,now-200000000L);
        // Deliberately leave the parent's native pose, render angles and height
        // readiness stale. The child is the first object to tick after arrival.
        IFloat[] parent=(IFloat[])get(car,"interTickPos");
        parent[0].reallySetValue(46);parent[1].reallySetValue(46);parent[2].reallySetValue(height);
        parent[3].reallySetValue(180);set(car,"pitch",0f);set(car,"roll",0f);set(car,"hasHPosBeenUpdated",false);
        // Start partway through a grade transition. The child must inherit the
        // visible eased parent pose, rather than rotate its seat by raw targets.
        // All assertions observe one native render frame. Letting real time
        // advance between getters would compare different eased seat poses.
        long frame=System.nanoTime()+10000000000L;
        set(CellRenderable.world,"wurmRailFrameNanos",frame);
        set(car,"wurmRailTiltNanos",frame-50000000L);
        rider.gameTick();
        check(parent[0].getValue()==x&&parent[1].getValue()==y&&parent[2].getValue()==z&&parent[3].getValue()==clientYaw,
                "a rider ticking before its parent refreshes the carrier's current complete playback pose");
        check((Boolean)get(car,"hasHPosBeenUpdated"),"authoritative zero-height carriers remain eligible for native carried movement");
        float[] targets=(float[])get(car,"posTargets");float pitch=(Float)get(car,"pitch"),roll=(Float)get(car,"roll");
        check(angle(pitch,parent[4].getValue())<.1&&angle(roll,parent[5].getValue())<.1,
                "native carrier render angles use its current smoothed pose before tickCarried rotates the seat");
        if(height>0) {
            double visible=Math.hypot(angle(pitch,0),angle(roll,0)),goal=Math.hypot(targets[4],targets[5]);
            check(visible>1&&visible<goal*.95,
                    "the rider-first case exercises an intermediate non-flat angle while the carrier eases toward its grade");
        }
        double[] expected=rotateSeat(local,clientYaw,pitch,roll);
        for(int axis=0;axis<3;axis++)check(Math.abs(offsets[axis].getValue()-expected[axis])<.0001,
                "native tickCarried calculates current rotated seat offset on axis "+axis+" layer "+layer);
        check(get(rider,"wurmRailMotion")==null,"mounted avatars do not create an independent railway playback buffer");
        check(Math.abs(rider.getXPosValue(1)-(x+expected[0]))<.0001
                &&Math.abs(rider.getYPosValue(1)-(y+expected[1]))<.0001
                &&Math.abs(rider.getHPosValue(1)-(z+expected[2]))<.0001,
                "native camera getters use current carrier XYZ and natively calculated tilted seat offsets");
        call(rider,"setRenderPos",new Class<?>[]{float.class},1f);
        check(Math.abs((Float)get(rider,"x")-(x+expected[0]))<.0001
                &&Math.abs((Float)get(rider,"y")-(y+expected[1]))<.0001
                &&Math.abs((Float)get(rider,"h")-(z+expected[2]))<.0001,
                "native rider render coordinates agree with native camera getters after a child-first tick");
    }

    /** Independent matrix form of Wurm's local-seat roll, pitch, then yaw frame. */
    private static double[] rotateSeat(float[] local,float yaw,float pitch,float roll) {
        double r=Math.toRadians(roll),p=Math.toRadians(pitch),a=Math.toRadians(yaw)+Math.PI/2;
        double rx=local[0],ry=Math.cos(r)*local[1]-Math.sin(r)*local[2],rz=Math.cos(r)*local[2]+Math.sin(r)*local[1];
        double px=Math.cos(p)*rx-Math.sin(p)*rz,pz=Math.cos(p)*rz+Math.sin(p)*rx;
        return new double[]{Math.cos(a)*px+Math.sin(a)*ry,Math.cos(a)*ry-Math.sin(a)*px,pz};
    }

    private static CreatureCellRenderable vehicle(String name,int layer,float x,float y,float h,float yaw) throws Exception {
        CreatureCellRenderable result=RailVehicleTerrainClientSmoke.makeVehicle(name,layer,x,y,h,yaw);
        set(result,"heading",new RenderVector(0,1,0));set(result,"vel",new RenderVector());set(result,"maxMovementAnimationSpeed",-1f);
        set(result,"movementData",new MovementData(null,0f,MovementSound.MovementType.WALK));
        set(result,"volume",new Volume());set(result,"xScale",1f);set(result,"hScale",1f);set(result,"yScale",1f);
        set(result,"offsets",new float[3]);
        Object data=result.getModelWrapper().getModelData();ModelDataBounds bounds=result.getModelWrapper().getBounds();
        set(data,"pickingBound",new ModelDataBounds(bounds));set(data,"originalPickingBound",new ModelDataBounds(bounds));
        return result;
    }
    private static void setupWorld(float slopeX,float slopeY,float height) throws Exception {
        World world=RailVehicleTerrainClientSmoke.setupWorld(slopeX,slopeY,height,slopeX,slopeY,height);
        // Native cave gameTick consults only isDev(). No connection constructor
        // or network session is needed for this native CPU path.
        java.lang.reflect.Method allocator=RailVehicleTerrainClientSmoke.class.getDeclaredMethod("allocate",Class.class);allocator.setAccessible(true);
        Object client=allocator.invoke(null,com.wurmonline.client.WurmClientBase.class);
        Object connection=allocator.invoke(null,com.wurmonline.client.comm.SimpleServerConnectionClass.class);
        set(client,"serverConnection",connection);set(world,"client",client);
    }
    private static Object get(Object target,String field) throws Exception { return RailVehicleTerrainClientSmoke.get(target,field); }
    private static void set(Object target,String field,Object value) throws Exception { RailVehicleTerrainClientSmoke.set(target,field,value); }
    private static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception { return RailVehicleTerrainClientSmoke.call(target,name,types,args); }
    private static float angle(float a,float b) { return Math.abs((a-b+540)%360-180); }
    private static void check(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
}
