import java.lang.reflect.Field;
import java.util.*;
import org.wurmrail.client.RailVehicleMotion;

/** Pure Java playback of generated rail shapes; no client socket, display or game classes. */
public final class RailVehicleMotionSelfTest {
    private static int assertions;
    private static final long SECOND=1000000000L;
    private static double worst20,worst4,worstYaw,worstHeight,worstQuantized20,worstQuantized4,worstQuantizedYaw;
    private static int cases;
    public static void main(String[] args) throws Exception {
        consistCadenceAndReversal();idleAndLostPackets();allTrackShapes();duplicatesAndHeight();stopsAndReset();reverseWithoutTurning();boundedAndInvalid();
        System.out.println("RailVehicleMotionSelfTest: "+assertions+" assertions passed; "+cases+" cases, all 28 shapes, both directions, 4/20 Hz packets, 24/60 Hz frames, exact/8-bit yaw, world offsets402/3702; exact lateral error 20Hz="+worst20+"m, 4Hz="+worst4+"m; quantized lateral error 20Hz="+worstQuantized20+"m, 4Hz="+worstQuantized4+"m; exact/quantized body yaw error="+worstYaw+"/"+worstQuantizedYaw+" degrees; sampled slope height error="+worstHeight+"m.");
    }
    private static void allTrackShapes() {
        int[][] directions={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
        for(int a=0;a<8;a++)for(int b=a+1;b<8;b++)for(boolean reverse:new boolean[]{false,true})
            for(int rate:new int[]{4,20})for(int frames:new int[]{24,60})for(boolean quantized:new boolean[]{false,true})for(int offset:new int[]{402,3702}) {
                cases++;Path rail=new Path(directions[a],directions[b],offset);double duration=rail.length/5;
                float[] initial=rail.pose(reverse?rail.length:0);if(quantized)initial[3]=nativeYaw(initial[3]);
                RailVehicleMotion motion=new RailVehicleMotion(initial[0],initial[1],initial[2],initial[3],0);
                double packet=1.0/rate;float[] previous=initial;
                for(int frame=0;frame<(duration+1)*frames;frame++) {
                    double now=frame/(double)frames;
                    while(packet<=now+.00000001) {
                        double s=Math.min(rail.length,packet*5);float[] target=rail.pose(reverse?rail.length-s:s);if(quantized)target[3]=nativeYaw(target[3]);long stamp=Math.round(packet*SECOND);
                        motion.accept(target[0],target[1],-3000,target[3],stamp);
                        motion.accept(target[0],target[1],target[2],target[3],stamp+1); // native explicit-height twin
                        packet+=1.0/rate;
                    }
                    float[] pose=motion.sample(Math.round(now*SECOND));finite(pose);
                    double[] nearest=rail.project(pose);double error=nearest[0];
                    if(quantized) { if(rate==20)worstQuantized20=Math.max(worstQuantized20,error);else worstQuantized4=Math.max(worstQuantized4,error); }
                    else { if(rate==20)worst20=Math.max(worst20,error);else worst4=Math.max(worst4,error); }
                    check(error<(quantized?.012:.01),"interpolated anchor follows generated rail, pair="+a+"/"+b+" reverse="+reverse+" packets="+rate+" quantized="+quantized+" offset="+offset+" error="+error);
                    double yaw=Math.abs(delta(pose[3],(float)nearest[1]));if(quantized)worstQuantizedYaw=Math.max(worstQuantizedYaw,yaw);else worstYaw=Math.max(worstYaw,yaw);
                    check(yaw<7,"backing interpolation retains physical body yaw instead of turning 180 degrees: "+yaw);
                    check(Math.hypot(pose[0]-previous[0],pose[1]-previous[1])<=5.0/frames+.012,"render playback never outruns authoritative maximum speed");
                    double heightError=Math.abs(pose[2]-(10+.1*pose[0]+.05*pose[1]));worstHeight=Math.max(worstHeight,heightError);
                    check(heightError<.08,"height shares horizontal playback instead of trailing separately: "+Arrays.toString(pose));
                    previous=pose;
                }
                float[] stopped=rail.pose(reverse?0:rail.length);if(quantized)stopped[3]=nativeYaw(stopped[3]);float[] held=motion.sample(Math.round((duration+3)*SECOND));
                close(held,stopped,.0001,"a stopped train settles exactly at its last authoritative pose");
            }
    }
    private static void duplicatesAndHeight() throws Exception {
        RailVehicleMotion motion=new RailVehicleMotion(0,0,12,359,0);
        motion.accept(.25f,0,-3000,1,50000000L);motion.accept(.25f,0,12.1f,1,50000001L);
        long cadence=number(motion,"cadence");check(cadence==50000000L,"paired XY and explicit Z preserve the 20Hz movement cadence");
        float[] halfway=motion.sample(275000000L);check(halfway[0]>.10&&halfway[0]<.15&&Math.abs(delta(halfway[3],0))<1,"position and wrapped physical yaw interpolate together on the shared quarter-second timeline");
        check(halfway[2]>12&&halfway[2]<12.1,"paired explicit Z fills the same buffered pose");
        motion.accept(.5f,0,-3000,2,100000000L);float[] sentinel=motion.sample(400000000L);
        check(sentinel[2]==12.1f,"native ground-height sentinel preserves the last real height");
        motion.accept(.5f,0,12.2f,2,200000001L);check(number(motion,"cadence")==cadence,"stationary height updates do not invent a movement interval");
    }
    private static void stopsAndReset() {
        RailVehicleMotion motion=new RailVehicleMotion(0,0,3,90,0);motion.accept(1.25f,0,3.5f,90,250000000L);
        float[] prior=motion.sample(250000000L);
        for(int i=1;i<=50;i++) {
            float[] next=motion.sample(250000000L+i*10000000L);
            check(next[0]>=prior[0]&&next[0]<=1.25&&next[2]>=prior[2]&&next[2]<=3.5,"packet loss holds the known endpoint without overshoot or extrapolation");prior=next;
        }
        close(prior,new float[]{1.25f,0,3.5f,90},.00001,"stalled sender settles exactly");
        motion.accept(50,60,7,270,SECOND);close(motion.sample(SECOND),new float[]{50,60,7,270},.00001,"actual teleport starts a fresh buffer");
        motion.reset(50,60,6.9f,270,SECOND+1);close(motion.sample(SECOND+1),new float[]{50,60,6.9f,270},.00001,"portal layer reset preserves the continuous supplied XYZ pose");
        motion.accept(49.75f,60,6.8f,270,SECOND+50000001L);
        float[] cave=motion.sample(SECOND+275000001L);check(cave[2]>6.8&&cave[2]<6.9,"surface/cave transition height interpolates continuously after reset on the shared timeline");
    }
    private static void boundedAndInvalid() throws Exception {
        RailVehicleMotion motion=new RailVehicleMotion(0,0,0,90,0);
        for(int i=1;i<=400;i++)motion.accept(i*.25f,0,0,90,i*50000000L);
        Field packets=RailVehicleMotion.class.getDeclaredField("packets");packets.setAccessible(true);
        check(((Deque<?>)packets.get(motion)).size()<=32,"render history has a fixed 32-packet memory bound");
        float[] before=motion.sample(30*SECOND);motion.accept(Float.NaN,0,0,0,31*SECOND);motion.accept(0,Float.POSITIVE_INFINITY,0,0,31*SECOND);
        close(before,motion.sample(31*SECOND),.0001,"invalid packets cannot poison client pose state");
        motion.accept(0,0,Float.NaN,Float.NaN,32*SECOND);finite(motion.sample(32*SECOND));
    }
    private static void reverseWithoutTurning() {
        RailVehicleMotion motion=new RailVehicleMotion(0,0,2,90,0);
        float[] positions={.25f,.5f,.5f,.4f,.2f,0};
        for(int i=0;i<positions.length;i++) {
            long time=(i+1)*50000000L;motion.accept(positions[i],0,2,90,time);
            for(int frame=0;frame<3;frame++)check(Math.abs(delta(motion.sample(time+frame*16000000L)[3],90))<.001,"traction reversal keeps the same physical locomotive orientation");
        }
    }
    /** Every car must render the same moment, even when their packet cadences differ. */
    private static void consistCadenceAndReversal() {
        for(int engineRate:new int[]{4,20})for(int wagonRate:new int[]{4,20})for(int fps:new int[]{24,60,144}) {
            RailVehicleMotion engine=new RailVehicleMotion(3702,3702,10,90,0);
            RailVehicleMotion wagon=new RailVehicleMotion(3698,3702,10,90,0);
            double nextEngine=1.0/engineRate,nextWagon=1.0/wagonRate;
            for(int frame=0;frame<=fps*11;frame++) {
                double time=frame/(double)fps;
                while(nextEngine<=time+.00000001) {
                    engine.accept((float)(3702+consistTravel(nextEngine)),3702,10,90,Math.round(nextEngine*SECOND));
                    nextEngine+=1.0/engineRate;
                }
                while(nextWagon<=time+.00000001) {
                    wagon.accept((float)(3698+consistTravel(nextWagon)),3702,10,90,Math.round(nextWagon*SECOND));
                    nextWagon+=1.0/wagonRate;
                }
                float[] front=engine.sample(Math.round(time*SECOND)),rear=wagon.sample(Math.round(time*SECOND));
                check(Math.abs(front[0]-rear[0]-4)<.001,
                        "one consist playback moment preserves the 4m reversing gap; engine="+engineRate+"Hz wagon="+wagonRate+"Hz frame="+fps+" time="+time+" gap="+(front[0]-rear[0]));
                check(front[3]==90&&rear[3]==90,"a consist reverses without rotating either body");
            }
            close(engine.sample(20*SECOND),new float[]{(float)(3702+consistTravel(11)),3702,10,90},.001,"engine reaches its exact final authoritative stop");
            close(wagon.sample(20*SECOND),new float[]{(float)(3698+consistTravel(11)),3702,10,90},.001,"wagon reaches its exact final authoritative stop");
        }
    }
    private static double consistTravel(double time) {
        if(time<=2)return time*5;
        if(time<=3)return 10;
        if(time<=6)return 10-(time-3)*5;
        if(time<=7)return -5;
        if(time<=10)return -5+(time-7)*5;
        return 10;
    }
    private static void idleAndLostPackets() {
        RailVehicleMotion idle=new RailVehicleMotion(0,0,2,90,0);
        idle.accept(.25f,0,2,90,50000000L);idle.sample(SECOND);
        idle.accept(.5f,0,2,90,10*SECOND);
        check(idle.sample(10*SECOND)[0]==.25f,"movement after a long stop is not drawn backwards over the stationary interval");
        check(Math.abs(idle.sample(10*SECOND+225000000L)[0]-.375)<.0001,"a resumed move keeps its normal cadence and continuous interpolation");
        RailVehicleMotion lost=new RailVehicleMotion(0,0,2,90,0);
        lost.accept(.25f,0,2,90,50000000L);lost.accept(5.25f,0,2,90,1050000000L);
        check(Math.abs(lost.sample(1050000000L)[0]-4)<.0001,"a long gap with continued motion keeps its full time span instead of inventing a pause or accelerating playback");
    }
    private static final class Path {
        final List<double[]> points=new ArrayList<double[]>();final List<double[]> mesh=new ArrayList<double[]>();double length;
        Path(int[] a,int[] b,int offset) {
            for(int i=0;i<=256;i++)add(offset+a[0]*(8-6*i/256.0),offset+a[1]*(8-6*i/256.0));
            for(int i=1;i<=2048;i++) { double t=i/2048.0,u=1-t;add(offset+2*a[0]*u*u+2*b[0]*t*t,offset+2*a[1]*u*u+2*b[1]*t*t); }
            for(int i=1;i<=256;i++)add(offset+b[0]*(2+6*i/256.0),offset+b[1]*(2+6*i/256.0));
            mesh.add(new double[]{offset+8*a[0],offset+8*a[1]});
            for(int i=0;i<=32;i++) { double t=i/32.0,u=1-t;mesh.add(new double[]{offset+2*a[0]*u*u+2*b[0]*t*t,offset+2*a[1]*u*u+2*b[1]*t*t}); }
            mesh.add(new double[]{offset+8*b[0],offset+8*b[1]});
        }
        void add(double x,double y) {
            if(!points.isEmpty()) { double[] previous=points.get(points.size()-1);length+=Math.hypot(x-previous[0],y-previous[1]); }
            points.add(new double[]{x,y,length});
        }
        float[] pose(double distance) {
            int low=0,high=points.size()-1;while(low<high) { int mid=(low+high)/2;if(points.get(mid)[2]<distance)low=mid+1;else high=mid; }
            int end=Math.max(1,low);double[] a=points.get(end-1),b=points.get(end);double t=(distance-a[2])/(b[2]-a[2]);
            double x=a[0]+t*(b[0]-a[0]),y=a[1]+t*(b[1]-a[1]);
            return new float[]{(float)x,(float)y,(float)(10+.1*x+.05*y),angle(Math.toDegrees(Math.atan2(b[0]-a[0],a[1]-b[1])))};
        }
        double[] project(float[] pose) {
            double best=Double.POSITIVE_INFINITY,yaw=0;
            for(int i=1;i<mesh.size();i++) {
                double[] a=mesh.get(i-1),b=mesh.get(i);double dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy;
                if(length==0)continue;double t=Math.max(0,Math.min(1,((pose[0]-a[0])*dx+(pose[1]-a[1])*dy)/length));
                double error=Math.hypot(pose[0]-a[0]-t*dx,pose[1]-a[1]-t*dy);
                if(error<best) { best=error;yaw=angle(Math.toDegrees(Math.atan2(dx,-dy))); }
            }
            return new double[]{best,yaw};
        }
    }
    private static long number(Object target,String name) throws Exception { Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.getLong(target); }
    private static float angle(double yaw) { return (float)((yaw%360+360)%360); }
    private static float nativeYaw(float yaw) { return (((int)(yaw*256/360))&255)*(360f/256); }
    private static double delta(float a,float b) { return (b-a+540)%360-180; }
    private static void finite(float[] pose) { check(pose.length==4,"pose has XYZ and physical yaw");for(float value:pose)check(!Float.isNaN(value)&&!Float.isInfinite(value),"all rendered coordinates remain finite"); }
    private static void close(float[] a,float[] b,double tolerance,String label) { for(int i=0;i<4;i++)check(Math.abs(i==3?delta(a[i],b[i]):a[i]-b[i])<=tolerance,label); }
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
}
