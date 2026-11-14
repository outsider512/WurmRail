package org.wurmrail.core;

import java.util.*;
import org.wurmrail.core.SteamWaterPumps.Pump;

/** Scheduled producers: backpressure keeps intent, never a bank of free water. */
public final class SteamWaterPumpService {
    public static final double INTERVAL_SECONDS=1,MAX_ELAPSED_SECONDS=1;
    public static final int MAX_ACTIONS_PER_POLL=32;
    public interface World {Result pump(Pump pump,long maxMillilitres);}
    public static final class Result {
        public final long added;public final boolean fault;public final String status;
        private Result(long amount,boolean fault,String status){if(amount<0)throw new IllegalArgumentException("Invalid pump quantity.");added=amount;this.fault=fault;this.status=oneLine(status);}
        public static Result waiting(String status){return new Result(0,false,status);}
        public static Result pumped(long amount,String status){return new Result(amount,false,status);}
        public static Result fault(long amount,String status){return new Result(amount,true,status);}
    }
    private static final class Clock {final long id,revision;double due;Clock(long id,long revision,double due){this.id=id;this.revision=revision;this.due=due;}}
    private final SteamWaterPumps store;private final World world;private final long allowance;
    private final Map<Long,Clock> clocks=new HashMap<Long,Clock>();
    private final PriorityQueue<Clock> due=new PriorityQueue<Clock>(Comparator.comparingDouble((Clock c)->c.due).thenComparingLong(c->c.id));
    private long observedEpoch=Long.MIN_VALUE;private double now;private boolean dirty;
    public SteamWaterPumpService(SteamWaterPumps store,World world,double litresPerSecond){this.store=Objects.requireNonNull(store);this.world=Objects.requireNonNull(world);if(!Double.isFinite(litresPerSecond)||litresPerSecond<.001||litresPerSecond>100000)throw new IllegalArgumentException("Pump rate must be between 0.001 and 100000 litres per second.");allowance=(long)Math.floor(litresPerSecond*1000+0.000001);}
    public boolean dirty(){return dirty;}public void clearDirty(){dirty=false;}public void reset(long id){clocks.remove(id);observedEpoch=Long.MIN_VALUE;}
    public void close(){clocks.clear();due.clear();observedEpoch=Long.MIN_VALUE;}
    public void start(long id){Pump pump=required(id).withRevision(store.nextRevision());reset(id);update(pump.withProgress(true,"Enabled. Waiting for the next pumping interval."));}
    public void stop(long id){Pump pump=required(id).withRevision(store.nextRevision());reset(id);update(pump.withProgress(false,"Stopped by operator."));}
    public void tick(double elapsed){if(!Double.isFinite(elapsed)||elapsed<0)throw new IllegalArgumentException("A finite non-negative pump interval is required.");synchronize();now+=Math.min(elapsed,MAX_ELAPSED_SECONDS);int remaining=MAX_ACTIONS_PER_POLL;
        while(remaining>0&&!due.isEmpty()&&due.peek().due<=now+0.000000001){Clock clock=due.remove();if(clocks.get(clock.id)!=clock)continue;Pump pump=store.get(clock.id);if(pump==null||!pump.running||pump.revision!=clock.revision){clocks.remove(clock.id);continue;}remaining--;
            try {Result result=world.pump(pump,allowance);if(result==null||result.added>allowance)throw new IllegalStateException("Native pump accounting requires operator inspection.");update(pump.withProgress(!result.fault,result.status));if(result.fault)clocks.remove(pump.itemId);}
            catch(RuntimeException failure){clocks.remove(pump.itemId);update(pump.withProgress(false,"Stopped: native pump error. "+oneLine(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage())));}
            Pump current=store.get(clock.id);if(clocks.get(clock.id)==clock&&current!=null&&current.running&&current.revision==clock.revision){clock.due=now+INTERVAL_SECONDS;due.add(clock);}
        }
    }
    private void synchronize(){if(observedEpoch==store.epoch())return;for(Iterator<Map.Entry<Long,Clock>> it=clocks.entrySet().iterator();it.hasNext();){Map.Entry<Long,Clock> e=it.next();Pump p=store.get(e.getKey());if(p==null||!p.running||p.revision!=e.getValue().revision)it.remove();}for(Pump p:store.pumps.values())if(p.running&&!clocks.containsKey(p.itemId)){Clock c=new Clock(p.itemId,p.revision,now+INTERVAL_SECONDS);clocks.put(p.itemId,c);due.add(c);}if(due.size()>clocks.size()*2+64){due.clear();due.addAll(clocks.values());}observedEpoch=store.epoch();}
    private void update(Pump pump){Pump old=store.get(pump.itemId);if(old!=null&&old.revision==pump.revision&&old.running==pump.running&&old.status.equals(pump.status))return;store.put(pump);dirty=true;}
    private Pump required(long id){Pump p=store.get(id);if(p==null)throw new IllegalArgumentException("Configure this Steam Water Pump first.");return p;}
    private static String oneLine(String text){if(text==null||text.trim().isEmpty())return "Waiting for pump availability.";StringBuilder out=new StringBuilder();for(int i=0;i<text.length()&&out.length()<400;i++){char c=text.charAt(i);out.append(Character.isISOControl(c)?' ':c);}return out.toString().trim();}
}
