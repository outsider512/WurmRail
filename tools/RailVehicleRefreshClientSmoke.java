import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import java.lang.reflect.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.wurmrail.client.RailVehicleMotion;

/** A repeated native create/metadata packet is not a teleport of an existing car. */
public final class RailVehicleRefreshClientSmoke {
    private static int checks;
    public static void run()throws Exception {
        World previous=CellRenderable.world;
        try {
            for(boolean reverse:new boolean[]{false,true})for(boolean refreshWagon:new boolean[]{false,true})
                for(String rear:new String[]{"model.wurmrail.cart.large.","model.wurmrail.tankcar.","model.wurmrail.logcar."})
                    refresh(reverse,refreshWagon,rear);
            System.out.println("PASS native existing-car refresh: real addTheCreature metadata packets preserve delayed locomotive/first-car spacing, forward/reverse, cargo/tank/log cars, no rider, retained history and true teleport reset ("+checks+" assertions)");
        }finally{CellRenderable.world=previous;}
    }
    private static void refresh(boolean reverse,boolean refreshWagon,String rear)throws Exception {
        RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
        CreatureCellRenderable engine=RailVehicleTerrainClientSmoke.makeVehicle("model.wurmrail.locomotive.",0,54,54,5,90);
        CreatureCellRenderable wagon=RailVehicleTerrainClientSmoke.makeVehicle(rear,0,50,54,5,90);
        Class<?> type=Class.forName("com.wurmonline.client.comm.ServerConnectionListenerClass");
        Method allocate=RailVehicleTerrainClientSmoke.class.getDeclaredMethod("allocate",Class.class);allocate.setAccessible(true);
        Object listener=allocate.invoke(null,type);set(listener,"world",CellRenderable.world);
        Map<Long,CreatureCellRenderable> creatures=new HashMap<Long,CreatureCellRenderable>();creatures.put(engine.getId(),engine);creatures.put(wagon.getId(),wagon);
        set(listener,"creatures",creatures);set(listener,"pendingAttachments",new ConcurrentHashMap<Long,Object>());set(listener,"phobiaList",Collections.emptyList());
        // Keep a full second of the actual authoritative path in both buffers.
        // Only the repeated metadata packet uses the native listener's live clock.
        long now=System.nanoTime(),start=now-1000000000L;float direction=reverse?-1:1;
        RailVehicleMotion[] motions={new RailVehicleMotion(54,54,5,90,start),new RailVehicleMotion(50,54,5,90,start)};
        for(int packet=1;packet<=20;packet++)for(int car=0;car<2;car++)motions[car].accept(54-4*car+direction*packet*.25f,54,5,90,start+packet*50000000L);
        set(engine,"wurmRailMotion",motions[0]);set(wagon,"wurmRailMotion",motions[1]);set(engine,"wurmRailHasExplicitHeight",true);set(wagon,"wurmRailHasExplicitHeight",true);
        set(CellRenderable.world,"wurmRailFrameNanos",now);
        render(engine);render(wagon);float before=engine.getXPosValue(.5f)-wagon.getXPosValue(.5f);
        check(Math.abs(before-4)<.0001,"both cars initially show the same delayed instant");
        CreatureCellRenderable car=refreshWagon?wagon:engine;String model=refreshWagon?rear:"model.wurmrail.locomotive.";
        float liveX=54-(refreshWagon?4:0)+direction*5;
        metadata(type,listener,car,model,liveX,5,90);
        render(engine);render(wagon);
        float gap=engine.getXPosValue(.5f)-wagon.getXPosValue(.5f);
        check(Math.abs(gap-4)<.005,"native metadata refresh must not reset only one car to live time; reverse="+reverse+", rear="+refreshWagon+", gap="+gap);
        check(get(car,"wurmRailMotion")==motions[refreshWagon?1:0],"refresh retains the existing physical pose stream");
        float[] targets=(float[])get(car,"posTargets");
        check(targets[0]==liveX&&targets[1]==54&&targets[2]==5,"metadata refresh still updates native authoritative position targets");
        check(((java.util.Deque<?>)get(motions[refreshWagon?1:0],"packets")).size()>2,"ordinary refresh keeps useful interpolation history rather than merely reusing an emptied buffer object");
        metadata(type,listener,car,model,liveX,5.2f,135);
        float[] refreshed=motions[refreshWagon?1:0].sample(now+1000000000L);
        check(targets[0]==liveX&&targets[1]==54&&targets[2]==5.2f&&targets[3]==45&&refreshed[0]==liveX&&refreshed[2]==5.2f&&refreshed[3]==135,"same-position metadata refresh retains authoritative height and correctly converted heading");
        // The metadata stream may refresh names and heading while preserving
        // pose history; actual explicit positioning still means a teleport.
        car.setPosImmediately(70,54,5,false,false);
        float[] teleported=((RailVehicleMotion)get(car,"wurmRailMotion")).sample(now+1000000000L);
        check(teleported[0]==70&&teleported[1]==54,"a real direct position reset still snaps to its authoritative location");
    }
    private static void metadata(Class<?> type,Object listener,CreatureCellRenderable car,String model,float x,float h,float yaw)throws Exception {
        type.getMethod("addTheCreature",long.class,String.class,String.class,String.class,byte.class,float.class,float.class,float.class,float.class,byte.class,boolean.class,byte.class,boolean.class,int.class,byte.class,byte.class,byte.class,long.class,byte.class)
                .invoke(listener,car.getId(),model,"Train refreshed","Metadata refresh",(byte)0,x,54f,h,yaw,(byte)0,false,(byte)2,false,0,(byte)0,(byte)0,(byte)0,-10L,(byte)0);
    }
    private static void render(CreatureCellRenderable car)throws Exception{RailVehicleTerrainClientSmoke.call(car,"setRenderPos",new Class<?>[]{float.class},.5f);}
    private static Object get(Object object,String key)throws Exception{return RailVehicleTerrainClientSmoke.get(object,key);}
    private static void set(Object object,String key,Object value)throws Exception{RailVehicleTerrainClientSmoke.set(object,key,value);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}

    /** Standalone negative-control runner against an extracted baseline package. */
    public static void main(String[] args) {
        try {
            javassist.Loader loader=org.gotti.wurmunlimited.modloader.classhooks.HookManager.getInstance().getLoader();
            for(String prefix:Arrays.asList("javafx.","com.sun.","org.controlsfx.","impl.org.controlsfx.","org.gotti.wurmunlimited.modloader.classhooks.","javassist."))loader.delegateLoadingOf(prefix);
            Thread.currentThread().setContextClassLoader(loader);loader.run("RailVehicleRefreshClientSmoke$Startup",args);System.exit(0);
        }catch(Throwable failed){failed.printStackTrace();System.exit(1);}
    }
    public static final class Startup {
        public static void main(String[] args)throws Throwable {
            org.gotti.wurmunlimited.modloader.classhooks.HookManager.getInstance().getClassPool().get("com.wurmonline.client.renderer.WorldRender").getClassInitializer().setBody("{}");
            org.gotti.wurmunlimited.modloader.classhooks.HookManager.getInstance().getClassPool().get("com.wurmonline.client.renderer.gui.text.TextQuad").getDeclaredMethod("isSupported").setBody("{return false;}");
            new org.gotti.wurmunlimited.modloader.ModLoader().loadModsFromModDir(Paths.get("mods"));
            org.gotti.wurmunlimited.modloader.classhooks.HookManager.getInstance().getLoader().run("RailVehicleRefreshClientSmoke$Checks",args);
        }
    }
    public static final class Checks {public static void main(String[] args)throws Exception{RailVehicleRefreshClientSmoke.run();}}
}
