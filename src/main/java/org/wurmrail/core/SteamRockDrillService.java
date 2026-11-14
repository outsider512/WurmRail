package org.wurmrail.core;

import java.util.*;
import java.util.function.Supplier;
import org.wurmrail.core.SteamRockDrills.Drill;

/** Slow, bounded, server-thread machine work; no world discovery or offline production. */
public final class SteamRockDrillService {
    public static final double MAX_ELAPSED_SECONDS=5;
    public static final int MAX_ACTIONS_PER_POLL=32;
    public interface World {
        /** Production steam controllers keep enabled machines waiting for deliveries/work. */
        default boolean automaticWaiting(){return false;}
        default boolean automaticWaiting(String reason){return automaticWaiting();}
        /** Revalidate the native machine anchor, permissions and selected output storage. */
        String unavailableReason(Drill drill);
        /** Only adjacent N/E/S/W mine faces, as a four-bit mask. */
        int availableFaces(Drill drill);
        /** Perform at most one normal native action, retaining every produced real resource. */
        Result mine(Drill drill,int face);
    }
    public static final class Result {
        public enum Kind { MINED, SKIP, WAIT, STOP }
        public final Kind kind;
        public final String detail;
        /** A completed action advances the cursor even when its output requires recovery. */
        public final boolean completedAction;
        private Result(Kind kind,String detail,boolean completedAction){this.kind=kind;this.detail=line(detail);this.completedAction=completedAction;}
        public static Result mined(){return mined("");}
        public static Result mined(String detail){return new Result(Kind.MINED,detail,true);}
        public static Result skip(String detail){return new Result(Kind.SKIP,detail,false);}
        public static Result stop(String detail){return new Result(Kind.STOP,detail,false);}
        public static Result stopAfterMining(String detail){return new Result(Kind.STOP,detail,true);}
        public static Result waiting(String detail){return new Result(Kind.WAIT,detail,false);}
        public static Result waitAfterMining(String detail){return new Result(Kind.WAIT,detail,true);}
    }
    private static final class Clock {
        final long id,revision;
        double due;
        Clock(long id,long revision,double due){this.id=id;this.revision=revision;this.due=due;}
    }
    private final Supplier<SteamRockDrills> store;
    private final World world;
    private final double interval;
    private final Map<Long,Clock> clocks=new HashMap<Long,Clock>();
    private final PriorityQueue<Clock> due=new PriorityQueue<Clock>(Comparator.comparingDouble((Clock clock)->clock.due).thenComparingLong(clock->clock.id));
    private SteamRockDrills observedStore;
    private long observedEpoch=Long.MIN_VALUE;
    private double now;
    private boolean dirty;

    public SteamRockDrillService(SteamRockDrills store,World world,double intervalSeconds) {
        this(()->store,world,intervalSeconds);
    }
    public SteamRockDrillService(Supplier<SteamRockDrills> store,World world,double intervalSeconds) {
        this.store=Objects.requireNonNull(store);this.world=Objects.requireNonNull(world);
        if(!Double.isFinite(intervalSeconds)||intervalSeconds<.1||intervalSeconds>86400)
            throw new IllegalArgumentException("The mining interval must be between 0.1 and 86,400 seconds.");
        interval=intervalSeconds;
    }
    public double intervalSeconds(){return interval;}
    public boolean dirty(){return dirty;}
    /** Clear only after the independently checksummed machine state was saved successfully. */
    public void clearDirty(){dirty=false;}
    public void reset(long id){clocks.remove(id);observedEpoch=Long.MIN_VALUE;}
    public void close(){clocks.clear();due.clear();observedStore=null;observedEpoch=Long.MIN_VALUE;}

    /** Controls execute on the server thread after the caller checks ownership and range. */
    public void start(long id) {
        SteamRockDrills registry=registry();Drill drill=required(registry,id).withRevision(registry.nextRevision());
        clocks.remove(id);
        try {
            String unavailable=unavailable(drill);
            int available=unavailable==null?world.availableFaces(drill):0;
            int face=SteamRockDrills.selectFace(drill.enabledMask,available,drill.nextFace);
            if((unavailable!=null||face<0)&&!world.automaticWaiting(unavailable==null?noFaces(drill):unavailable)){stopped(drill,unavailable==null?noFaces(drill):unavailable);return;}
            Clock clock=new Clock(id,drill.revision,now+interval);clocks.put(id,clock);due.add(clock);
            if(unavailable!=null||face<0)waitOrStop(drill,unavailable==null?noFaces(drill):unavailable);
            else update(drill.withProgress(true,drill.nextFace,"RUNNING. Next face: "+SteamRockDrills.faceName(face)+"."));
        }catch(RuntimeException failure){stopped(drill,error(failure));}
    }
    public void stop(long id) {
        SteamRockDrills registry=registry();Drill drill=required(registry,id).withRevision(registry.nextRevision());
        stopped(drill,"Stopped by operator.");
    }
    /** An explicit inspection may refresh the next physical face without consuming any time or resources. */
    public int currentFace(long id) {
        Drill drill=registry().get(id);if(drill==null||!drill.running)return -1;
        try{return SteamRockDrills.selectFace(drill.enabledMask,world.availableFaces(drill),drill.nextFace);}
        catch(RuntimeException unavailable){return -1;}
    }
    public double secondsUntilAction(long id) {
        Drill drill=registry().get(id);if(drill==null||!drill.running)return 0;
        Clock clock=clocks.get(id);
        return clock==null||clock.revision!=drill.revision?interval:Math.max(0,clock.due-now);
    }

    public void tick(double elapsedSeconds) {
        if(!Double.isFinite(elapsedSeconds)||elapsedSeconds<0)
            throw new IllegalArgumentException("A finite non-negative machine interval is required.");
        SteamRockDrills registry=registry();synchronize(registry);
        now+=Math.min(elapsedSeconds,MAX_ELAPSED_SECONDS);
        int budget=MAX_ACTIONS_PER_POLL;
        while(budget>0&&!due.isEmpty()&&due.peek().due<=now+0.000000001) {
            Clock clock=due.remove();
            if(clocks.get(clock.id)!=clock)continue;
            Drill drill=registry.get(clock.id);
            if(drill==null||!drill.running||drill.revision!=clock.revision){clocks.remove(clock.id);continue;}
            budget--;
            try{perform(drill);}catch(RuntimeException failure){
                Drill current=registry.get(clock.id);stopped(current==null?drill:current,error(failure));
            }
            Drill current=registry.get(clock.id);
            if(clocks.get(clock.id)==clock&&current!=null&&current.running&&current.revision==clock.revision) {
                // Discard elapsed backlog: neither server pauses nor a busy
                // action queue can produce several actions in a single turn.
                clock.due=now+interval;due.add(clock);
            }
        }
    }
    private void synchronize(SteamRockDrills registry) {
        if(registry==observedStore&&registry.epoch()==observedEpoch)return;
        for(Iterator<Map.Entry<Long,Clock>> it=clocks.entrySet().iterator();it.hasNext();) {
            Map.Entry<Long,Clock> entry=it.next();Drill drill=registry.get(entry.getKey());
            if(drill==null||!drill.running||drill.revision!=entry.getValue().revision)it.remove();
        }
        // Configuration/start/stop changes are rare. Normal polls consult only
        // the due queue and never enumerate registered or native world items.
        for(Drill drill:registry.drills.values())if(drill.running&&!clocks.containsKey(drill.itemId)) {
            Clock clock=new Clock(drill.itemId,drill.revision,now+interval);clocks.put(drill.itemId,clock);due.add(clock);
        }
        if(due.size()>clocks.size()*2+64){due.clear();due.addAll(clocks.values());}
        observedStore=registry;observedEpoch=registry.epoch();
    }
    private void perform(Drill drill) {
        String unavailable=unavailable(drill);
        if(unavailable!=null){waitOrStop(drill,unavailable);return;}
        int available=world.availableFaces(drill)&SteamRockDrills.ALL_FACES;
        int attempted=0;
        for(int tries=0;tries<4;tries++) {
            int face=SteamRockDrills.selectFace(drill.enabledMask,available&~attempted,drill.nextFace);
            if(face<0){waitOrStop(drill,noFaces(drill));return;}
            attempted|=1<<face;
            Result result=world.mine(drill,face);
            if(result==null)throw new IllegalStateException("The native mining action returned no result.");
            int cursor=result.completedAction?(face+1)&3:drill.nextFace;
            if(result.kind==Result.Kind.MINED) {
                Drill progress=drill.withProgress(true,cursor,"Running. Mined "+SteamRockDrills.faceName(face)+"."
                        +(result.detail.isEmpty()?"":" "+result.detail));
                update(progress);
                // A completed native action can exhaust the last face. This
                // adjacent-face read happens once per action, never per poll.
                if(SteamRockDrills.selectFace(progress.enabledMask,world.availableFaces(progress),cursor)<0)
                    waitOrStop(progress,noFaces(progress));
                return;
            }
            if(result.kind==Result.Kind.WAIT) {
                update(drill.withProgress(true,cursor,result.detail.isEmpty()?"Waiting for steam supplies or output storage.":result.detail));return;
            }
            if(result.kind==Result.Kind.STOP) {
                stopped(drill.withProgress(false,cursor,drill.status),result.detail.isEmpty()?"Mining output is unavailable.":result.detail);return;
            }
            // A face can change between inspection and the native action. Try
            // each remaining physical side only once within this same turn.
        }
        waitOrStop(drill,noFaces(drill));
    }
    private String unavailable(Drill drill) {
        if(drill.hopperId==0)return "No Bulk Hopper is linked.";
        if(drill.enabledMask==0)return "No mining faces are enabled.";
        return world.unavailableReason(drill);
    }
    private static String noFaces(Drill drill) {
        return drill.enabledMask==0?"No mining faces are enabled.":"No enabled mineable faces remain.";
    }
    private void stopped(Drill drill,String reason) {
        clocks.remove(drill.itemId);String detail=line(reason);
        update(drill.withProgress(false,drill.nextFace,detail.startsWith("Stopped")?detail:"Stopped: "+detail));
    }
    private void waitOrStop(Drill drill,String reason) {
        if(!world.automaticWaiting(reason)){stopped(drill,reason);return;}
        String detail=line(reason);if(detail.isEmpty())detail="Waiting for work or storage.";
        if(detail.equals(noFaces(drill)))detail="NO VALID WORK: "+detail;
        update(drill.withProgress(true,drill.nextFace,detail));
    }
    private void update(Drill drill){registry().put(drill);dirty=true;}
    private SteamRockDrills registry(){return Objects.requireNonNull(store.get(),"A machine registry is required.");}
    private static Drill required(SteamRockDrills store,long id) {
        Drill drill=store.get(id);if(drill==null)throw new IllegalArgumentException("Configure this Steam Rock Drill first.");return drill;
    }
    private static String error(RuntimeException failure) {
        String message=line(failure.getMessage());return "Native mining error: "+(message.isEmpty()?failure.getClass().getSimpleName():message);
    }
    private static String line(String text) {
        if(text==null)return "";StringBuilder result=new StringBuilder();
        for(int i=0;i<text.length()&&result.length()<400;i++) {char c=text.charAt(i);result.append(Character.isISOControl(c)?' ':c);}
        return result.toString().trim();
    }
}
