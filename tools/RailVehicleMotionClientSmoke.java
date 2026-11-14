import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.game.World;
import com.wurmonline.client.timing.IFloat;
import org.wurmrail.client.RailVehicleMotion;

/** Exercises the installed movement hooks and native pose/rider consumers, without a display. */
public final class RailVehicleMotionClientSmoke {
    private static int assertions;
    private RailVehicleMotionClientSmoke() { }

    public static void run() throws Exception {
        World old=CellRenderable.world;
        try {
            RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
            for(String model:new String[]{"model.wurmrail.locomotive.","model.wurmrail.cart.large.","model.wurmrail.tankcar.","model.wurmrail.logcar."})
                for(int layer:new int[]{0,-1})nativePackets(model,layer);
            listenerHeadingsAndCurve();controllerPlaceholderPackets();reversingConsist();curvedConsist();carriedPose();exclusions();
            System.out.println("PASS native buffered rail motion hooks, paired XY/Z, shared position/height/yaw, frame and camera getters, mounted pose, stopped/teleport handling and unchanged ordinary carts ("+assertions+" assertions)");
        } finally { CellRenderable.world=old; }
    }

    /**
     * The listener deliberately supplies placeholder zero XY to a locally
     * controlled native mount. Do not let a render-only rail buffer mistake
     * that placeholder for a server position. Unlike the timed consist cases,
     * this test never reinjects corrected XY or artificial packet timestamps.
     */
    private static void controllerPlaceholderPackets() throws Exception {
        Class<?> listenerType=Class.forName("com.wurmonline.client.comm.ServerConnectionListenerClass");
        java.lang.reflect.Method allocate=RailVehicleTerrainClientSmoke.class.getDeclaredMethod("allocate",Class.class);allocate.setAccessible(true);
        java.lang.reflect.Method makePlayer=RailVehiclePlayerClientSmoke.class.getDeclaredMethod("player",World.class,CreatureCellRenderable.class,boolean.class,float[].class);makePlayer.setAccessible(true);
        for(boolean controlWagon:new boolean[]{false,true}) {
            RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
            CreatureCellRenderable engine=vehicle("model.wurmrail.locomotive.",0,54,54,5,90);
            CreatureCellRenderable wagon=vehicle("model.wurmrail.cart.large.",0,50,54,5,90);
            CreatureCellRenderable controlled=controlWagon?wagon:engine;
            Object player=makePlayer.invoke(null,CellRenderable.world,controlled,true,new float[]{0,0,.55f});
            Object listener=allocate.invoke(null,listenerType);set(listener,"world",CellRenderable.world);
            java.util.Map<Long,CreatureCellRenderable> creatures=new java.util.HashMap<Long,CreatureCellRenderable>();
            creatures.put(engine.getId(),engine);creatures.put(wagon.getId(),wagon);set(listener,"creatures",creatures);
            for(int packet=0;packet<30;packet++) {
                float travel=packet<10?packet*.25f:packet<20?2.25f-(packet-10)*.25f:-.25f+(packet-20)*.25f;
                for(int car=0;car<2;car++) {
                    CreatureCellRenderable model=car==0?engine:wagon;
                    call(listener,"moveCreature",new Class<?>[]{long.class,float.class,float.class,float.class,byte.class},model.getId(),54+travel-4*car,54f,5f,(byte)64);
                    RailVehicleMotion motion=(RailVehicleMotion)get(model,"wurmRailMotion");
                    check(motion!=null,"actual local-controller packets retain a rail playback buffer");
                    float[] pose=motion.sample(System.nanoTime()+1000000000L);
                    check(Math.abs(pose[0]-(54+travel-4*car))<.0001&&Math.abs(pose[1]-54)<.0001,
                            "native controller placeholder cannot overwrite authoritative locomotive/wagon XY; wagon controller="+controlWagon+", packet="+packet+", X="+pose[0]+", Y="+pose[1]);
                }
                RailVehicleMotion authoritative=(RailVehicleMotion)get(controlled,"wurmRailMotion");
                float serverX=54+travel-(controlWagon?4:0);
                // This is the native controller's smooth local prediction
                // write, not an authoritative server teleport. Preserve the
                // native targets while keeping render playback independent.
                controlled.setPosImmediately(serverX-1f,54,5,true,false);
                check(((float[])get(controlled,"posTargets"))[0]==serverX-1f,
                        "render synchronization preserves the native local-controller position target");
                float[] afterPrediction=authoritative.sample(System.nanoTime()+1000000000L);
                check(Math.abs(afterPrediction[0]-serverX)<.0001&&Math.abs(afterPrediction[1]-54)<.0001,
                        "smooth local mount corrections cannot reset or progressively offset authoritative rail playback");
                call(engine,"setRenderPos",new Class<?>[]{float.class},.5f);call(wagon,"setRenderPos",new Class<?>[]{float.class},.5f);
                check(Math.abs((Float)get(engine,"x")-(Float)get(wagon,"x")-4)<.0001,
                        "the native listener alone keeps the first-car gap through forward and reverse packets");
            }
            // Unmounting must not leave a poisoned origin behind while a car
            // is parked and receives no additional repair movement packet.
            set(player,"carrierCreature",null);set(player,"isCarrierController",false);
            call(engine,"setRenderPos",new Class<?>[]{float.class},.5f);call(wagon,"setRenderPos",new Class<?>[]{float.class},.5f);
            check(Math.abs((Float)get(engine,"x")-(Float)get(wagon,"x")-4)<.0001,
                    "dismounted stationary consist retains the last authoritative spacing without a new packet");
            set(player,"carrierCreature",controlled);set(player,"isCarrierController",true);
            controlled.setPosImmediately(60,58,5,false,false);
            float[] teleported=((RailVehicleMotion)get(controlled,"wurmRailMotion")).sample(System.nanoTime()+1000000000L);
            check(teleported[0]==60&&teleported[1]==58,
                    "an actual non-smooth reposition still resets the rail playback buffer");
        }
        System.out.println("PASS native local-controller packet placeholders: locomotive and first-car control, genuine listener XY, forward/reverse spacing and parked dismount without synthetic coordinate reinjection");
    }

    private static void listenerHeadingsAndCurve() throws Exception {
        Class<?> listenerType=Class.forName("com.wurmonline.client.comm.ServerConnectionListenerClass");
        java.lang.reflect.Method allocate=RailVehicleTerrainClientSmoke.class.getDeclaredMethod("allocate",Class.class);allocate.setAccessible(true);
        Object listener=allocate.invoke(null,listenerType);set(listener,"world",CellRenderable.world);
        java.util.Map<Long,CreatureCellRenderable> creatures=new java.util.HashMap<Long,CreatureCellRenderable>();set(listener,"creatures",creatures);
        for(int heading=0;heading<8;heading++) {
            float serverYaw=heading*45f,nativeYaw=(180-serverYaw+360)%360;
            CreatureCellRenderable car=vehicle("model.wurmrail.locomotive.",0,46,46,5,nativeYaw);creatures.put(car.getId(),car);
            double radians=Math.toRadians(serverYaw);float x=46+(float)Math.sin(radians)*.25f,y=46-(float)Math.cos(radians)*.25f;
            call(listener,"moveCreature",new Class<?>[]{long.class,float.class,float.class,float.class,byte.class},car.getId(),x,y,5f,(byte)(heading*32));
            RailVehicleMotion motion=(RailVehicleMotion)get(car,"wurmRailMotion");
            float[] authoritative=motion.sample(System.nanoTime()+1000000000L);
            check(angle(authoritative[3],serverYaw)<.001,"actual listener's compressed heading is converted into the curve buffer's compass frame");
            call(car,"setRenderPos",new Class<?>[]{float.class},.5f);
            check(angle(car.getYawValue(.5f),nativeYaw)<.001&&angle(car.getRot(),nativeYaw)<.001,
                    "buffered physical heading returns to the native model/camera frame at compass heading "+serverYaw);
        }
        CreatureCellRenderable turning=vehicle("model.wurmrail.cart.",0,46,48,5,180);
        long now=System.nanoTime();
        call(turning,"wurmRailAcceptMotion",new Class<?>[]{float.class,float.class,float.class,float.class,long.class},46f,48f,5f,180f,now-300000000L);
        call(turning,"wurmRailAcceptMotion",new Class<?>[]{float.class,float.class,float.class,float.class,long.class},48f,46f,5f,90f,now+100000000L);
        call(turning,"setRenderPos",new Class<?>[]{float.class},.5f);
        float x=(Float)get(turning,"x"),y=(Float)get(turning,"y");double t=Math.sqrt(Math.max(0,(x-46)/2.0));
        check(x>46.01&&x<47.99&&Math.abs(y-(48-4*t+2*t*t))<.005,
                "actual native north-to-east yaw endpoints reconstruct the rail curve rather than a chord across the intersection");
    }

    private static void nativePackets(String model,int layer) throws Exception {
        CreatureCellRenderable car=vehicle(model,layer,46,46,5,90);
        car.move(46.25f,46,-3000,90);
        Object first=get(car,"wurmRailMotion");
        check(first instanceof RailVehicleMotion,"actual native move packet creates a game-loader-resolvable rail playback buffer");
        car.move(46.25f,46,5.1f,90);
        check(first==get(car,"wurmRailMotion")&&!car.isOnGround(),"explicit-height packet merges with its native XY twin while retaining tunnel-safe height mode");
        car.move(46.25f,46,-3000,90);
        check(!car.isOnGround(),"a later native no-height twin cannot temporarily restore roof sampling after authoritative rail height is known");
        RailVehicleMotion motion=(RailVehicleMotion)first;
        float[] end=motion.sample(System.nanoTime()+1000000000L);
        check(end[0]==46.25f&&end[1]==46&&end[2]==5.1f&&end[3]==90,"paired native packets preserve the complete authoritative pose");
        call(car,"wurmRailApplyMotion",new Class<?>[]{long.class},System.nanoTime());
        float[] targets=(float[])get(car,"posTargets");targets[0]=60;targets[1]=60;targets[2]=25;targets[3]=270;
        for(int dof=0;dof<4;dof++)call(car,"moveDOFtoTarget",new Class<?>[]{int.class,boolean.class,float.class},dof,dof==3,24f);
        IFloat[] interpolated=(IFloat[])get(car,"interTickPos");
        for(int dof=0;dof<4;dof++)check(interpolated[dof].getValue()==end[dof],"rail pose bypasses the old native target filter for degree "+dof);
        for(float alpha:new float[]{0,.25f,.5f,.75f,1}) {
            check(car.getXPosValue(alpha)==end[0]&&car.getYPosValue(alpha)==end[1]
                    &&car.getHPosValue(alpha)==end[2]&&angle(car.getYawValue(alpha),end[3])<.001,
                    "native frame/camera getters share the corrected XYZ and physical heading at fraction "+alpha);
            call(car,"setRenderPos",new Class<?>[]{float.class},alpha);
            check((Float)get(car,"x")==end[0]&&(Float)get(car,"y")==end[1]&&(Float)get(car,"h")==end[2],
                    "native render position uses the same playback pose as camera consumers");
        }
        float[] held=motion.sample(System.nanoTime()+10000000000L);
        check(equal(end,held),"an idle packet stream holds the last complete native pose without extrapolation");
        car.setPosImmediately(60,46,5,true,true);
        float[] reset=motion.sample(System.nanoTime()+1000000000L);
        check(reset[0]==60&&reset[1]==46&&reset[2]==5,"native spawn/teleport position resets playback instead of drawing a cross-map curve");
    }

    private static void carriedPose() throws Exception {
        CreatureCellRenderable car=vehicle("model.wurmrail.locomotive.",0,46,46,5,90);
        car.move(46.5f,46,5,90);RailVehicleMotion motion=(RailVehicleMotion)get(car,"wurmRailMotion");
        motion.sample(System.nanoTime()+1000000000L);
        CreatureCellRenderable rider=vehicle("model.creature.humanoid.human.player.male",0,46,46,5.55f,90);
        set(rider,"carrierCreature",car);set(rider,"offsets",new float[]{-.3f,.7f,.55f});
        IFloat[] offsets=(IFloat[])get(rider,"interTickOffset");
        offsets[0].reallySetValue(.3f);offsets[1].reallySetValue(-.7f);offsets[2].reallySetValue(.55f);
        call(rider,"setRenderPos",new Class<?>[]{float.class},.5f);
        check(Math.abs((Float)get(rider,"x")-46.8f)<.0001&&Math.abs((Float)get(rider,"y")-45.3f)<.0001
                &&Math.abs((Float)get(rider,"h")-5.55f)<.0001,
                "native mounted render path inherits the corrected carrier pose and all seat offsets");
        check(get(rider,"wurmRailMotion")==null,"rider attachment never creates a second railway playback buffer on the avatar");
    }

    /** Genuine packet conversion and native render getters at one shared frame time. */
    private static void reversingConsist() throws Exception {
        Class<?> listenerType=Class.forName("com.wurmonline.client.comm.ServerConnectionListenerClass");
        java.lang.reflect.Method allocate=RailVehicleTerrainClientSmoke.class.getDeclaredMethod("allocate",Class.class);allocate.setAccessible(true);
        double worst=0;
        for(String wagonModel:new String[]{"model.wurmrail.cart.","model.wurmrail.tankcar.","model.wurmrail.logcar."})
            for(int layer:new int[]{0,-1})for(int heading=0;heading<8;heading++)
                for(int engineRate:new int[]{4,20})for(int wagonRate:new int[]{4,20})for(int fps:new int[]{24,60}) {
            RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
            float yaw=heading*45f,nativeYaw=(180-yaw+360)%360;
            double dx=Math.sin(Math.toRadians(yaw)),dy=-Math.cos(Math.toRadians(yaw));
            CreatureCellRenderable engine=vehicle("model.wurmrail.locomotive.",layer,54,54,5,nativeYaw);
            CreatureCellRenderable wagon=vehicle(wagonModel,layer,(float)(54-4*dx),(float)(54-4*dy),5,nativeYaw);
            Object listener=allocate.invoke(null,listenerType);set(listener,"world",CellRenderable.world);
            java.util.Map<Long,CreatureCellRenderable> creatures=new java.util.HashMap<Long,CreatureCellRenderable>();
            creatures.put(engine.getId(),engine);creatures.put(wagon.getId(),wagon);set(listener,"creatures",creatures);
            long start=System.nanoTime()+10000000000L;
            nativeConsistPacket(listener,engine,54,54,heading,start);
            nativeConsistPacket(listener,wagon,(float)(54-4*dx),(float)(54-4*dy),heading,start);
            ((RailVehicleMotion)get(engine,"wurmRailMotion")).reset(54,54,5,yaw,start);
            ((RailVehicleMotion)get(wagon,"wurmRailMotion")).reset((float)(54-4*dx),(float)(54-4*dy),5,yaw,start);
            double nextEngine=1.0/engineRate,nextWagon=1.0/wagonRate;
            for(int frame=0;frame<=fps*11;frame++) {
                double time=frame/(double)fps;
                while(nextEngine<=time+.00000001) {
                    double travel=consistTravel(nextEngine);
                    nativeConsistPacket(listener,engine,(float)(54+travel*dx),(float)(54+travel*dy),heading,start+Math.round(nextEngine*1e9));
                    nextEngine+=1.0/engineRate;
                }
                while(nextWagon<=time+.00000001) {
                    double travel=consistTravel(nextWagon)-4;
                    nativeConsistPacket(listener,wagon,(float)(54+travel*dx),(float)(54+travel*dy),heading,start+Math.round(nextWagon*1e9));
                    nextWagon+=1.0/wagonRate;
                }
                set(CellRenderable.world,"wurmRailFrameNanos",start+Math.round(time*1e9));
                call(engine,"setRenderPos",new Class<?>[]{float.class},.5f);call(wagon,"setRenderPos",new Class<?>[]{float.class},.5f);
                float ex=(Float)get(engine,"x"),ey=(Float)get(engine,"y"),wx=(Float)get(wagon,"x"),wy=(Float)get(wagon,"y");
                double error=Math.abs(Math.hypot(ex-wx,ey-wy)-4);worst=Math.max(worst,error);
                check(error<.001,"native reversing consist keeps 4m separation at headings "+heading+" layer "+layer+" and rates "+engineRate+"/"+wagonRate+"; error="+error);
                check(engine.getXPosValue(.5f)==ex&&engine.getYPosValue(.5f)==ey&&wagon.getXPosValue(.5f)==wx&&wagon.getYPosValue(.5f)==wy,
                        "native car render and camera getters use the same consist frame");
                check(angle(engine.getYawValue(.5f),nativeYaw)<.001&&angle(wagon.getYawValue(.5f),nativeYaw)<.001,
                        "reversal preserves both model body orientations through quantized native packets");
            }
        }
        System.out.println("PASS native multi-car forward/STOP/reverse consist: mixed 4/20 Hz packet streams, 24/60 Hz render frames, all eight headings and both floors, shared camera/model time; worst 4m gap error="+worst+"m");
    }
    private static void nativeConsistPacket(Object listener,CreatureCellRenderable vehicle,float x,float y,int heading,long time) throws Exception {
        nativeConsistPacket(listener,vehicle,x,y,heading*45f,time);
    }
    private static void nativeConsistPacket(Object listener,CreatureCellRenderable vehicle,float x,float y,float yaw,long time) throws Exception {
        // The native listener owns 8-bit heading conversion. Future controlled
        // timestamps isolate this regression from wall-clock scheduling noise.
        int compressed=((int)(yaw*256/360))&255;
        call(listener,"moveCreature",new Class<?>[]{long.class,float.class,float.class,float.class,byte.class},vehicle.getId(),x,y,5f,(byte)compressed);
        call(vehicle,"wurmRailAcceptMotion",new Class<?>[]{float.class,float.class,float.class,float.class,long.class},
                x,y,5f,180-compressed*(360f/256),time);
    }
    private static double consistTravel(double time) {
        if(time<=2)return time*5;if(time<=3)return 10;
        if(time<=6)return 10-(time-3)*5;if(time<=7)return -5;
        if(time<=10)return -5+(time-7)*5;return 10;
    }
    private static void curvedConsist() throws Exception {
        Class<?> listenerType=Class.forName("com.wurmonline.client.comm.ServerConnectionListenerClass");
        java.lang.reflect.Method allocate=RailVehicleTerrainClientSmoke.class.getDeclaredMethod("allocate",Class.class);allocate.setAccessible(true);
        ConsistPath path=new ConsistPath();double worstPosition=0,worstGap=0;
        for(String wagonModel:new String[]{"model.wurmrail.cart.","model.wurmrail.tankcar.","model.wurmrail.logcar."})
            for(int engineRate:new int[]{4,20})for(int wagonRate:new int[]{4,20}) {
            RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
            float[] front=path.pose(6),rear=path.pose(2);
            CreatureCellRenderable engine=vehicle("model.wurmrail.locomotive.",0,front[0],front[1],5,180-front[2]);
            CreatureCellRenderable wagon=vehicle(wagonModel,0,rear[0],rear[1],5,180-rear[2]);
            Object listener=allocate.invoke(null,listenerType);set(listener,"world",CellRenderable.world);
            java.util.Map<Long,CreatureCellRenderable> creatures=new java.util.HashMap<Long,CreatureCellRenderable>();
            creatures.put(engine.getId(),engine);creatures.put(wagon.getId(),wagon);set(listener,"creatures",creatures);
            long start=System.nanoTime()+10000000000L;
            nativeConsistPacket(listener,engine,front[0],front[1],front[2],start);nativeConsistPacket(listener,wagon,rear[0],rear[1],rear[2],start);
            ((RailVehicleMotion)get(engine,"wurmRailMotion")).reset(front[0],front[1],5,front[2],start);
            ((RailVehicleMotion)get(wagon,"wurmRailMotion")).reset(rear[0],rear[1],5,rear[2],start);
            double nextEngine=1.0/engineRate,nextWagon=1.0/wagonRate;
            for(int frame=0;frame<=60*14;frame++) {
                double time=frame/60.0;
                while(nextEngine<=time+.00000001) {
                    float[] p=path.pose(6+curveTravel(nextEngine));
                    nativeConsistPacket(listener,engine,p[0],p[1],p[2],start+Math.round(nextEngine*1e9));nextEngine+=1.0/engineRate;
                }
                while(nextWagon<=time+.00000001) {
                    float[] p=path.pose(2+curveTravel(nextWagon));
                    nativeConsistPacket(listener,wagon,p[0],p[1],p[2],start+Math.round(nextWagon*1e9));nextWagon+=1.0/wagonRate;
                }
                set(CellRenderable.world,"wurmRailFrameNanos",start+Math.round(time*1e9));
                call(engine,"setRenderPos",new Class<?>[]{float.class},.5f);call(wagon,"setRenderPos",new Class<?>[]{float.class},.5f);
                float x=(Float)get(engine,"x"),y=(Float)get(engine,"y"),wx=(Float)get(wagon,"x"),wy=(Float)get(wagon,"y");
                double gap=Math.abs(path.distance(x,y)-path.distance(wx,wy)-4);worstGap=Math.max(worstGap,gap);
                check(gap<.035,"quantized native packets retain arc-length consist spacing around the bend in either travel direction: "+gap);
                for(int car=0;car<2;car++) {
                    float[] expected=path.pose((car==0?6:2)+curveTravel(Math.max(0,time-.25)));
                    double error=Math.hypot((car==0?x:wx)-expected[0],(car==0?y:wy)-expected[1]);worstPosition=Math.max(worstPosition,error);
                    check(error<.025,"a reversing curved consist follows each original centreline instead of forcing a straight visual coupling: "+error);
                }
            }
        }
        System.out.println("PASS native curved reversing consist: actual 8-bit packet headings and mixed 4/20 Hz streams; worst centreline error="+worstPosition+"m, worst 4m arc gap error="+worstGap+"m");
    }
    private static double curveTravel(double time) {
        if(time<=4)return time*5;if(time<=5)return 20;if(time<=9)return 20-(time-5)*5;
        if(time<=10)return 0;return Math.min(20,(time-10)*5);
    }
    private static final class ConsistPath {
        final java.util.List<double[]> points=new java.util.ArrayList<double[]>();double length;
        ConsistPath() {
            add(54,70);add(54,56);
            for(int i=1;i<=512;i++){double t=i/512.0,u=1-t;add(54+2*t*t,56-4*t+2*t*t);}
            add(70,54);
        }
        void add(double x,double y) {
            if(!points.isEmpty()){double[] last=points.get(points.size()-1);length+=Math.hypot(x-last[0],y-last[1]);}
            points.add(new double[]{x,y,length});
        }
        float[] pose(double distance) {
            int lo=1,hi=points.size()-1;
            while(lo<hi){int mid=(lo+hi)/2;if(points.get(mid)[2]<distance)lo=mid+1;else hi=mid;}
            double[] a=points.get(lo-1),b=points.get(lo);double t=(distance-a[2])/(b[2]-a[2]);
            return new float[]{(float)(a[0]+t*(b[0]-a[0])),(float)(a[1]+t*(b[1]-a[1])),
                    (float)((Math.toDegrees(Math.atan2(b[0]-a[0],a[1]-b[1]))+360)%360)};
        }
        double distance(double x,double y) {
            double best=Double.POSITIVE_INFINITY,result=0;
            for(int i=1;i<points.size();i++) {
                double[] a=points.get(i-1),b=points.get(i);double dx=b[0]-a[0],dy=b[1]-a[1];
                double t=Math.max(0,Math.min(1,((x-a[0])*dx+(y-a[1])*dy)/(dx*dx+dy*dy)));
                double error=Math.hypot(x-a[0]-t*dx,y-a[1]-t*dy);
                if(error<best){best=error;result=a[2]+t*(b[2]-a[2]);}
            }return result;
        }
    }

    private static void exclusions() throws Exception {
        CreatureCellRenderable ordinary=vehicle("model.cart.large.",0,46,46,5,90);
        ordinary.move(48,46,5,90);
        check(get(ordinary,"wurmRailMotion")==null,"ordinary Wurm carts keep the original native movement implementation");
        call(ordinary,"moveDOFtoTarget",new Class<?>[]{int.class,boolean.class,float.class},0,false,10f);
        IFloat[] original=(IFloat[])get(ordinary,"interTickPos");
        check(Math.abs(original[0].getValue()-(46+2f/24))<.00001,"unrelated vehicles still use their exact native 24-tick target increment");
        for(String model:new String[]{"model.wurmrail.track.17.","model.wurmrail.cartoon.","model.wurmrail.locomotivefake.","model.wurmrail.tankcarriage.","model.wurmrail.logcarrier."}) {
            CreatureCellRenderable other=vehicle(model,0,46,46,5,90);other.move(46.25f,46,5,90);
            check(get(other,"wurmRailMotion")==null,"track scenery and similarly named models are excluded from rail motion hooks");
        }
    }

    private static CreatureCellRenderable vehicle(String name,int layer,float x,float y,float h,float yaw) throws Exception {
        CreatureCellRenderable result=RailVehicleTerrainClientSmoke.makeVehicle(name,layer,x,y,h,yaw);
        set(result,"heading",new com.wurmonline.client.renderer.RenderVector(0,1,0));
        set(result,"vel",new com.wurmonline.client.renderer.RenderVector());
        set(result,"maxMovementAnimationSpeed",-1f);
        set(result,"movementData",new com.wurmonline.client.renderer.cell.MovementData(null,0f,
                com.wurmonline.client.sound.MovementSound.MovementType.WALK));
        return result;
    }
    private static Object get(Object object,String field) throws Exception { return RailVehicleTerrainClientSmoke.get(object,field); }
    private static void set(Object object,String field,Object value) throws Exception { RailVehicleTerrainClientSmoke.set(object,field,value); }
    private static Object call(Object object,String method,Class<?>[] types,Object...args) throws Exception { return RailVehicleTerrainClientSmoke.call(object,method,types,args); }
    private static boolean equal(float[] a,float[] b) { for(int i=0;i<a.length;i++)if(a[i]!=b[i])return false;return true; }
    private static float angle(float a,float b) { return Math.abs((a-b+540)%360-180); }
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
}
