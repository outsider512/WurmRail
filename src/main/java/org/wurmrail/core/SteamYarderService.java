package org.wurmrail.core;

import java.util.*;
import java.util.function.Supplier;
import org.wurmrail.core.SteamYarders.Yarder;
import org.wurmrail.core.SteamYarders.Offset;

/** Bounded harvest opportunities; ordinary Wurm alone advances tree age. */
public final class SteamYarderService {
    public static final double MAX_ELAPSED_SECONDS=5;
    public static final int MAX_ACTIONS_PER_POLL=32;
    public interface World {
        default boolean automaticWaiting(){return false;}
        default boolean automaticWaiting(String reason){return automaticWaiting();}
        /** Revalidate machine placement, durable owner and linked output storage. */
        String unavailableReason(Yarder yarder);
        /** Coordinates always belong to this yarder's fixed circle; native code rechecks them. */
        Result harvest(Yarder yarder,int tileX,int tileY);
    }
    public static final class Result {
        public enum Kind { HARVESTED,SKIP,WAIT,STOP }
        public final Kind kind;public final String detail;public final boolean completedAction;
        private Result(Kind kind,String detail,boolean completedAction){this.kind=kind;this.detail=line(detail);this.completedAction=completedAction;}
        public static Result harvested(){return harvested("");}
        public static Result harvested(String detail){return new Result(Kind.HARVESTED,detail,true);}
        public static Result skip(String detail){return new Result(Kind.SKIP,detail,false);}
        public static Result stop(String detail){return new Result(Kind.STOP,detail,false);}
        public static Result stopAfterHarvest(String detail){return new Result(Kind.STOP,detail,true);}
        public static Result waiting(String detail){return new Result(Kind.WAIT,detail,false);}
        public static Result waitAfterHarvest(String detail){return new Result(Kind.WAIT,detail,true);}
    }
    private static final class Clock {
        final long id,revision;double due;
        Clock(long id,long revision,double due){this.id=id;this.revision=revision;this.due=due;}
    }
    private final Supplier<SteamYarders> store;
    private final World world;
    private final double interval;
    private final Map<Long,Clock> clocks=new HashMap<Long,Clock>();
    private final PriorityQueue<Clock> due=new PriorityQueue<Clock>(Comparator.comparingDouble((Clock clock)->clock.due).thenComparingLong(clock->clock.id));
    private SteamYarders observedStore;
    private long observedEpoch=Long.MIN_VALUE;
    private double now;
    private boolean dirty;
    public SteamYarderService(SteamYarders store,World world,double intervalSeconds){this(()->store,world,intervalSeconds);}
    public SteamYarderService(Supplier<SteamYarders> store,World world,double intervalSeconds) {
        this.store=Objects.requireNonNull(store);this.world=Objects.requireNonNull(world);
        if(!Double.isFinite(intervalSeconds)||intervalSeconds<.1||intervalSeconds>86400)throw new IllegalArgumentException("The harvest interval must be between 0.1 and 86400 seconds.");
        interval=intervalSeconds;
    }
    public double intervalSeconds(){return interval;}
    public boolean dirty(){return dirty;}
    public void clearDirty(){dirty=false;}
    public void reset(long id){clocks.remove(id);observedEpoch=Long.MIN_VALUE;}
    public void close(){clocks.clear();due.clear();observedStore=null;observedEpoch=Long.MIN_VALUE;}
    public void start(long id) {
        SteamYarders registry=registry();Yarder yarder=required(registry,id).withRevision(registry.nextRevision());clocks.remove(id);
        try {
            String reason=unavailable(yarder);if(reason!=null&&!world.automaticWaiting(reason)){stopped(yarder,reason);return;}
            Clock clock=new Clock(id,yarder.revision,now+interval);clocks.put(id,clock);due.add(clock);
            if(reason!=null)waitOrStop(yarder,reason);
            else update(yarder.withProgress(true,yarder.nextTile,"RUNNING. Waiting for the next harvest opportunity."));
        }catch(RuntimeException failure){stopped(yarder,error(failure));}
    }
    public void stop(long id){SteamYarders registry=registry();stopped(required(registry,id).withRevision(registry.nextRevision()),"Stopped by operator.");}
    public double secondsUntilAction(long id) {
        Yarder yarder=registry().get(id);if(yarder==null||!yarder.running)return 0;
        Clock clock=clocks.get(id);return clock==null||clock.revision!=yarder.revision?interval:Math.max(0,clock.due-now);
    }
    public void tick(double elapsedSeconds) {
        if(!Double.isFinite(elapsedSeconds)||elapsedSeconds<0)throw new IllegalArgumentException("A finite non-negative yarder interval is required.");
        SteamYarders registry=registry();synchronize(registry);now+=Math.min(elapsedSeconds,MAX_ELAPSED_SECONDS);int budget=MAX_ACTIONS_PER_POLL;
        while(budget>0&&!due.isEmpty()&&due.peek().due<=now+0.000000001) {
            Clock clock=due.remove();if(clocks.get(clock.id)!=clock)continue;
            Yarder yarder=registry.get(clock.id);
            if(yarder==null||!yarder.running||yarder.revision!=clock.revision){clocks.remove(clock.id);continue;}
            budget--;
            try{perform(yarder);}catch(RuntimeException failure){Yarder current=registry.get(clock.id);stopped(current==null?yarder:current,error(failure));}
            Yarder current=registry.get(clock.id);
            if(clocks.get(clock.id)==clock&&current!=null&&current.running&&current.revision==clock.revision){clock.due=now+interval;due.add(clock);}
        }
    }
    private void synchronize(SteamYarders registry) {
        if(registry==observedStore&&registry.epoch()==observedEpoch)return;
        for(Iterator<Map.Entry<Long,Clock>> it=clocks.entrySet().iterator();it.hasNext();) {
            Map.Entry<Long,Clock> entry=it.next();Yarder yarder=registry.get(entry.getKey());
            if(yarder==null||!yarder.running||yarder.revision!=entry.getValue().revision)it.remove();
        }
        for(Yarder yarder:registry.yarders.values())if(yarder.running&&!clocks.containsKey(yarder.itemId)) {
            Clock clock=new Clock(yarder.itemId,yarder.revision,now+interval);clocks.put(yarder.itemId,clock);due.add(clock);
        }
        if(due.size()>clocks.size()*2+64){due.clear();due.addAll(clocks.values());}
        observedStore=registry;observedEpoch=registry.epoch();
    }
    private void perform(Yarder yarder) {
        String reason=unavailable(yarder);if(reason!=null){waitOrStop(yarder,reason);return;}
        int count=SteamYarders.WOODLOT.size();
        for(int attempt=0;attempt<count;attempt++) {
            int index=(yarder.nextTile+attempt)%count;Offset offset=SteamYarders.WOODLOT.get(index);
            int x=Math.addExact(yarder.tileX,offset.dx),y=Math.addExact(yarder.tileY,offset.dy);
            if(!SteamYarders.contains(yarder,x,y))throw new IllegalStateException("The selected tree is outside this woodlot.");
            Result result=world.harvest(yarder,x,y);if(result==null)throw new IllegalStateException("The native forestry action returned no result.");
            int cursor=result.completedAction?(index+1)%count:yarder.nextTile;
            if(result.kind==Result.Kind.HARVESTED) {
                update(yarder.withProgress(true,cursor,"Running. Harvested and replanted tile "+x+", "+y+"."+(result.detail.isEmpty()?"":" "+result.detail)));return;
            }
            if(result.kind==Result.Kind.WAIT) {
                update(yarder.withProgress(true,cursor,result.detail.isEmpty()?"Waiting for steam supplies or output storage.":result.detail));return;
            }
            if(result.kind==Result.Kind.STOP) {
                stopped(yarder.withProgress(false,cursor,yarder.status),result.detail.isEmpty()?"Forestry output is unavailable.":result.detail);return;
            }
        }
        // No suitable trees is a normal wait, never a reason to grow or fell
        // younger trees, plant bare ground, or leave this circle.
        update(yarder.withProgress(true,yarder.nextTile,"NO VALID WORK: Waiting for managed trees to reach "+yarder.harvestAge.displayName+"; Wurm growth remains natural."));
    }
    private String unavailable(Yarder yarder){return yarder.bunkId==0?"No Log Bunk is linked.":world.unavailableReason(yarder);}
    private void stopped(Yarder yarder,String reason) {
        clocks.remove(yarder.itemId);String detail=line(reason);if(detail.isEmpty())detail="Forestry is unavailable.";
        update(yarder.withProgress(false,yarder.nextTile,detail.startsWith("Stopped")?detail:"Stopped: "+detail));
    }
    private void waitOrStop(Yarder yarder,String reason) {
        if(!world.automaticWaiting(reason)){stopped(yarder,reason);return;}
        String detail=line(reason);update(yarder.withProgress(true,yarder.nextTile,detail.isEmpty()?"Waiting for work or storage.":detail));
    }
    private void update(Yarder yarder) {
        Yarder previous=registry().get(yarder.itemId);
        if(previous!=null&&previous.revision==yarder.revision&&previous.running==yarder.running&&previous.nextTile==yarder.nextTile&&previous.status.equals(yarder.status))return;
        registry().put(yarder);dirty=true;
    }
    private SteamYarders registry(){return Objects.requireNonNull(store.get(),"A yarder registry is required.");}
    private static Yarder required(SteamYarders registry,long id){Yarder yarder=registry.get(id);if(yarder==null)throw new IllegalArgumentException("Configure this Steam Yarder first.");return yarder;}
    private static String error(RuntimeException failure){String message=line(failure.getMessage());return "Native forestry error: "+(message.isEmpty()?failure.getClass().getSimpleName():message);}
    private static String line(String text) {
        if(text==null)return "";StringBuilder out=new StringBuilder();for(int i=0;i<text.length()&&out.length()<400;i++){char c=text.charAt(i);out.append(Character.isISOControl(c)?' ':c);}return out.toString().trim();
    }
}
