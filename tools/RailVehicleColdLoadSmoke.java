import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javassist.ClassPool;
import javassist.Loader;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Fresh-launch regression for helpers first resolved after client bootstrap has ended.
 * Deliberately contains no typed reference to the movement helper or its Packet class. */
public final class RailVehicleColdLoadSmoke {
    private static final String MOTION="org.wurmrail.client.RailVehicleMotion";
    private static final String PACKET=MOTION+"$Packet";

    public static void main(String[] args) {
        try {
            Loader loader=HookManager.getInstance().getLoader();
            for(String prefix:Arrays.asList("javafx.","com.sun.","org.controlsfx.",
                    "impl.org.controlsfx.","org.gotti.wurmunlimited.modloader.classhooks.","javassist."))
                loader.delegateLoadingOf(prefix);
            Thread.currentThread().setContextClassLoader(loader);
            loader.run("RailVehicleColdLoadSmoke$Startup",args);
            System.exit(0);
        } catch(Throwable failure) {
            failure.printStackTrace();System.exit(1);
        }
    }

    public static final class Startup {
        public static void main(String[] args) throws Throwable {
            boolean expected=Arrays.asList(args).contains("--expect-missing-packet");
            HookManager.getInstance().getClassPool().get("com.wurmonline.client.renderer.WorldRender")
                    .getClassInitializer().setBody("{}");
            HookManager.getInstance().getClassPool().get("com.wurmonline.client.renderer.gui.text.TextQuad")
                    .getDeclaredMethod("isSupported").setBody("{return false;}");
            BootState state=discoverAndRelease();
            require(!cached(PACKET),"Packet must not have been preloaded by a typed smoke fixture");
            // The official DelegatedLauncher discards the returned mods list. A
            // separate method frame ensures that this test does the same.
            for(int attempt=0;attempt<20&&!collected(state.loader);attempt++) {
                System.gc();System.runFinalization();Thread.sleep(20);
            }
            boolean naturallyCollected=collected(state.loader);
            // Exercise loss of that same weak bootstrap source deterministically,
            // even on a VM that elects not to perform an explicitly requested GC.
            // No archive, application, game or fixture classpath is removed.
            for(Reference<?> source:state.transientSources)source.clear();
            System.out.println("Cold bootstrap released: isolated loader collected="+naturallyCollected
                    +"; transient weak helper sources cleared="+state.transientSources.size());
            require(!cached(PACKET),"Packet metadata must remain cold until the first real movement packet");
            try {
                HookManager.getInstance().getLoader().run("RailVehicleColdLoadSmoke$FirstPacket",new String[0]);
            } catch(Throwable failure) {
                if(expected&&missingPacket(failure)) {
                    System.out.println("PASS reproduced original cold first-packet crash: "+PACKET);return;
                }
                throw failure;
            }
            require(!expected,"the negative-control jar unexpectedly survived the original cold-load failure");
            System.out.println("COLD CLIENT HELPER SMOKE PASSED (official discovery, released bootstrap, first native movement, deferred nested class)");
        }
    }

    private static final class BootState {
        final WeakReference<ClassLoader> loader;
        final List<Reference<?>> transientSources;
        BootState(ClassLoader loader,List<Reference<?>> sources) {
            this.loader=new WeakReference<ClassLoader>(loader);transientSources=sources;
        }
    }

    private static BootState discoverAndRelease() throws Exception {
        org.gotti.wurmunlimited.modloader.ModLoader bootstrap=new org.gotti.wurmunlimited.modloader.ModLoader();
        List<? extends org.gotti.wurmunlimited.modloader.interfaces.ModEntry<
                org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod>> mods=bootstrap.loadModsFromModDir(Paths.get("mods"));
        require(mods.size()==1&&"wurmrailmodels".equals(mods.get(0).getName()),"exactly the packaged railway client mod must be discovered");
        ClassLoader isolated=mods.get(0).getWurmMod().getClass().getClassLoader();
        require(isolated!=HookManager.getInstance().getLoader(),"regression must exercise the official isolated mod classloader");
        List<Reference<?>> sources=new ArrayList<Reference<?>>();
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        Object pool=HookManager.getInstance().getClassPool();
        while(pool!=null&&seen.add(pool)) {
            Object source=get(pool,"source"),path=get(source,"pathList");
            while(path!=null) {
                Object entry=get(path,"path");
                if(entry.getClass().getName().equals("javassist.LoaderClassPath")) {
                    Reference<?> reference=(Reference<?>)get(entry,"clref");
                    if(reference.get()==isolated)sources.add(reference);
                }
                path=get(path,"next");
            }
            pool=get(pool,"parent");
        }
        return new BootState(isolated,sources);
    }

    private static boolean collected(WeakReference<ClassLoader> reference) { return reference.get()==null; }
    private static boolean cached(String name) throws Exception {
        return ((Map<?,?>)get(HookManager.getInstance().getClassPool(),"classes")).containsKey(name);
    }

    /** Loaded only after discovery references and temporary weak paths have gone. */
    public static final class FirstPacket {
        public static void main(String[] args) throws Exception {
            ClassLoader game=HookManager.getInstance().getLoader();
            require(!cached(PACKET),"no native fixture may preload the nested packet class");
            Class<?> fixtures=Class.forName("RailVehicleTerrainClientSmoke",true,game);
            fixtures.getMethod("setupWorld",float.class,float.class,float.class,float.class,float.class,float.class)
                    .invoke(null,0f,0f,5f,0f,0f,5f);
            Object car=fixtures.getMethod("makeVehicle",String.class,int.class,float.class,float.class,float.class,float.class)
                    .invoke(null,"model.wurmrail.logcar.",0,46f,46f,5f,180f);
            Class<?> creature=car.getClass();
            long id=(Long)creature.getMethod("getId").invoke(car);
            Class<?> listenerType=Class.forName("com.wurmonline.client.comm.ServerConnectionListenerClass",true,game);
            Method allocate=fixtures.getDeclaredMethod("allocate",Class.class);allocate.setAccessible(true);
            Object listener=allocate.invoke(null,listenerType);
            Class<?> cell=Class.forName("com.wurmonline.client.renderer.cell.CellRenderable",true,game);
            set(listener,"world",cell.getField("world").get(null));
            Map<Long,Object> creatures=new HashMap<Long,Object>();creatures.put(id,car);set(listener,"creatures",creatures);
            Method packet=listenerType.getDeclaredMethod("moveCreature",long.class,float.class,float.class,float.class,byte.class);
            packet.setAccessible(true);
            require(!cached(PACKET),"native fixture setup must not preload the deferred nested packet class");
            try {
                // The real listener performs signed-byte compass conversion and
                // invokes the native move() method containing the installed hook.
                packet.invoke(listener,id,46f,45.75f,5f,(byte)0);
            } catch(InvocationTargetException wrapper) { throwUnchecked(wrapper.getCause()); }
            Object buffer=get(car,"wurmRailMotion");
            require(buffer!=null&&buffer.getClass().getName().equals(MOTION),"the first native packet must create the movement helper");
            require(buffer.getClass().getClassLoader()==game,"game hook and helper must share a runtime classloader");
            Class<?> nested=Class.forName(PACKET,false,game);
            require(nested.getClassLoader()==buffer.getClass().getClassLoader(),"deferred nested helper must have the outer helper's class identity");
            float[] pose=(float[])buffer.getClass().getMethod("sample",long.class).invoke(buffer,System.nanoTime()+1000000000L);
            require(pose.length==4&&pose[0]==46f&&pose[1]==45.75f&&pose[2]==5f&&pose[3]==0f,
                    "first cold-loaded movement retains authoritative position, height and compass heading");
            System.out.println("PASS first native packet created outer and nested helpers after bootstrap release");
            // Resolve the separate scenery helper only now, after the isolated
            // mod loader and every transient weak source have been discarded.
            Class.forName("RailTrackTerrainClientSmoke",true,game)
                    .getMethod("coldFirstTrack").invoke(null);
        }
    }

    private static boolean missingPacket(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if((cause instanceof NoClassDefFoundError||cause instanceof ClassNotFoundException)
                    &&String.valueOf(cause.getMessage()).replace('/','.').contains(PACKET))return true;
        return false;
    }
    private static Object get(Object object,String name) throws Exception {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try {
            Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);
        } catch(NoSuchFieldException missing) { }
        throw new NoSuchFieldException(name);
    }
    private static void set(Object object,String name,Object value) throws Exception {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try {
            Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(object,value);return;
        } catch(NoSuchFieldException missing) { }
        throw new NoSuchFieldException(name);
    }
    private static void throwUnchecked(Throwable failure) throws Exception {
        if(failure instanceof Exception)throw (Exception)failure;
        if(failure instanceof Error)throw (Error)failure;
        throw new RuntimeException(failure);
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
