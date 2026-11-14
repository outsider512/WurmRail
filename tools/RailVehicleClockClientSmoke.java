import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import java.lang.reflect.Method;
import org.wurmrail.client.RailVehicleMotion;

/** Real native ticks must not advance one vehicle beyond its consist's shared frame. */
public final class RailVehicleClockClientSmoke {
    private static int assertions;
    private RailVehicleClockClientSmoke(){ }

    public static void run()throws Exception {
        World prior=CellRenderable.world;
        try {
            staggeredTicks(1);staggeredTicks(-1);
            System.out.println("PASS native shared rail playback clock: real staggered unmounted gameTick calls, forward/reverse, fixed frame timestamp, exact consist gap and unchanged authoritative packet buffers ("+assertions+" assertions)");
        }finally{CellRenderable.world=prior;}
    }
    private static void staggeredTicks(int direction)throws Exception {
        RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
        CreatureCellRenderable engine=vehicle("model.wurmrail.locomotive.",50),wagon=vehicle("model.wurmrail.cart.large.",46);
        // All packet timestamps precede or equal real wall time. Unlike the
        // older future-dated fixtures, a native tick can overtake this frame.
        long frame=System.nanoTime(),start=frame-2000000000L;
        RailVehicleMotion front=new RailVehicleMotion(50,46,5,90,start),rear=new RailVehicleMotion(46,46,5,90,start);
        for(int i=1;i<=40;i++) {
            long time=start+i*50000000L;float travelled=direction*i*.25f;
            front.accept(50+travelled,46,5,90,time);rear.accept(46+travelled,46,5,90,time);
        }
        set(engine,"wurmRailMotion",front);set(wagon,"wurmRailMotion",rear);
        set(engine,"wurmRailHasExplicitHeight",true);set(wagon,"wurmRailHasExplicitHeight",true);
        set(CellRenderable.world,"wurmRailFrameNanos",frame);
        float[] authoritativeFront=((float[])get(engine,"posTargets")).clone(),authoritativeRear=((float[])get(wagon,"posTargets")).clone();
        wagon.gameTick();
        // Model preparation/GC/large cell updates may delay another vehicle's
        // tick. Keep the World frame fixed, exactly as native World.tick does
        // while iterating its cells. A sleep tests the actual installed hook.
        Thread.sleep(300);
        engine.gameTick();
        call(engine,"setRenderPos",new Class<?>[]{float.class},.5f);call(wagon,"setRenderPos",new Class<?>[]{float.class},.5f);
        float ex=(Float)get(engine,"x"),wx=(Float)get(wagon,"x");
        check(Math.abs(ex-wx-4)<.001,"staggered native ticks preserve the 4m gap in direction "+direction+"; rendered gap="+(ex-wx));
        float expected=50+direction*8.75f;
        check(Math.abs(ex-expected)<.001&&Math.abs(wx-(expected-4))<.001,"both native models show the same quarter-second-delayed frame rather than their individual tick wall times");
        for(float alpha:new float[]{0,.5f,1})check(engine.getXPosValue(alpha)==ex&&wagon.getXPosValue(alpha)==wx,"frame/camera getters retain the same fixed consist gap at fraction "+alpha);
        float[] frontTargets=(float[])get(engine,"posTargets"),rearTargets=(float[])get(wagon,"posTargets");
        for(int i=0;i<4;i++)check(frontTargets[i]==authoritativeFront[i]&&rearTargets[i]==authoritativeRear[i],"render synchronization leaves native movement targets unchanged on axis "+i);
        float[] frontEnd=front.sample(frame+1000000000L),rearEnd=rear.sample(frame+1000000000L);
        check(frontEnd[0]==50+direction*10&&rearEnd[0]==46+direction*10,"the actual authoritative final packet coordinates are preserved without manufacturing coupling positions");
    }
    private static CreatureCellRenderable vehicle(String model,float x)throws Exception {
        Method factory=RailVehicleRiderClientSmoke.class.getDeclaredMethod("vehicle",String.class,int.class,float.class,float.class,float.class,float.class);factory.setAccessible(true);
        return (CreatureCellRenderable)factory.invoke(null,model,0,x,46f,5f,90f);
    }
    private static Object get(Object o,String f)throws Exception{return RailVehicleTerrainClientSmoke.get(o,f);}
    private static void set(Object o,String f,Object v)throws Exception{RailVehicleTerrainClientSmoke.set(o,f,v);}
    private static Object call(Object o,String method,Class<?>[] types,Object...args)throws Exception{return RailVehicleTerrainClientSmoke.call(o,method,types,args);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
