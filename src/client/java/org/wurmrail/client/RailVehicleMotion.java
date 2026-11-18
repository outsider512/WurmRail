package org.wurmrail.client;

import java.util.ArrayDeque;
import java.util.Deque;

/** A bounded, common-delay playback buffer for authoritative railway item poses. */
public final class RailVehicleMotion {
    private static final long MIN_DELAY=50000000L,MAX_DELAY=250000000L;
    private static final int MAX_PACKETS=32,ARC_SAMPLES=32;
    private static final float GROUND_HEIGHT=-3000f;
    private final Deque<Packet> packets=new ArrayDeque<Packet>();
    private long cadence=MIN_DELAY,lastPlayback;
    private boolean measuredCadence;

    private static final class Packet {
        final float x,y;
        final long time;
        float height,yaw;
        Packet(float x,float y,float height,float yaw,long time) {
            this.x=x;this.y=y;this.height=height;this.yaw=yaw;this.time=time;
        }
    }

    public RailVehicleMotion(float x,float y,float height,float yaw,long now) {
        reset(x,y,height,yaw,now);
    }

    /** Creation, an actual teleport or a layer discontinuity begins a fresh buffer. */
    public synchronized void reset(float x,float y,float height,float yaw,long now) {
        float previous=packets.isEmpty()?0:packets.getLast().height;
        packets.clear();packets.add(new Packet(finite(x)?x:0,finite(y)?y:0,
                finite(height)&&height!=GROUND_HEIGHT?height:previous,finite(yaw)?angle(yaw):0,now));
        cadence=MIN_DELAY;lastPlayback=now;measuredCadence=false;
    }

    /** Times are monotonic nanoseconds. Paired native XY/Z packets share one pose. */
    public synchronized void accept(float x,float y,float height,float yaw,long now) {
        if(!finite(x)||!finite(y)||!finite(yaw))return;
        Packet latest=packets.getLast();
        float h=finite(height)&&height!=GROUND_HEIGHT?height:latest.height;
        float rotation=angle(yaw);
        double distance=Math.hypot(x-latest.x,y-latest.y);
        if(distance<.00001) {
            latest.height=h;latest.yaw=rotation;return;
        }
        if(now<=latest.time)return;
        long elapsed=now-latest.time;
        if(distance>8||(elapsed>2000000000L&&distance>2)) {
            reset(x,y,h,rotation,now);return;
        }
        // An idle train emits no movement packets. Do not spread its first
        // resumed movement backwards across the entire stationary interval.
        // Only insert a hold when this displacement fits a normal packet at
        // the previous cadence; actual lost moving packets retain their time.
        boolean resumed=elapsed>MAX_DELAY*2&&distance<=8.0*cadence/1.0e9+.0001;
        if(resumed&&now-cadence>latest.time)
            packets.addLast(new Packet(latest.x,latest.y,latest.height,latest.yaw,now-cadence));
        long interval=resumed?cadence:Math.max(MIN_DELAY,Math.min(MAX_DELAY,elapsed));
        cadence=measuredCadence?(cadence*3+interval)/4:interval;measuredCadence=true;
        packets.addLast(new Packet(x,y,h,rotation,now));
        while(packets.size()>MAX_PACKETS)packets.removeFirst();
    }

    /** Visibility announcements can precede or trail the movement stream. */
    public synchronized void refresh(float x,float y,float height,float yaw,long now) {
        Packet latest=packets.getLast();
        double distance=Math.hypot(x-latest.x,y-latest.y);
        // A reannouncement supplies metadata, not another point on the curve.
        // Keep the existing teleport threshold for genuine discontinuities.
        if(distance<.00001||distance>8)accept(x,y,height,yaw,now);
    }

    synchronized void acceptFrame(float x,float y,float height,float yaw,long time) {
        Packet latest=packets.getLast();
        double distance=Math.hypot(x-latest.x,y-latest.y);
        if (distance>8) {
            reset(x,y,height,yaw,time);
            return;
        }
        // The server skips unchanged parked trains. Preserve their stationary
        // pose up to the next movement tick instead of interpolating a long gap.
        if (time-latest.time>500000000L)
            packets.addLast(new Packet(latest.x,latest.y,latest.height,latest.yaw,time-MIN_DELAY));
        packets.addLast(new Packet(x,y,height,angle(yaw),time));
        while (packets.size()>MAX_PACKETS) packets.removeFirst();
    }

    /** No extrapolation: when packets stop, the latest authoritative pose is held. */
    public synchronized float[] sample(long now) {
        // Every vehicle in a consist must show the same moment. Independent
        // adaptive delays compress reversing consists when packet rates differ.
        // A quarter second covers the supported 4-Hz stream without prediction.
        return sampleAt(now-MAX_DELAY);
    }

    synchronized float[] sampleAt(long at) {
        Packet first=packets.getFirst(),latest=packets.getLast();
        long playback=Math.max(lastPlayback,at);
        playback=Math.max(first.time,Math.min(latest.time,playback));lastPlayback=playback;
        while(packets.size()>2) {
            Packet second=second();if(second.time>playback)break;packets.removeFirst();
        }
        first=packets.getFirst();
        if(packets.size()==1||playback<=first.time)return pose(first);
        Packet next=second();
        if(playback>=next.time)return pose(next);
        double progress=(playback-first.time)/(double)(next.time-first.time);
        return interpolate(first,next,progress);
    }

    private Packet second() {
        java.util.Iterator<Packet> iterator=packets.iterator();iterator.next();return iterator.next();
    }
    private static float[] pose(Packet p) { return new float[]{p.x,p.y,p.height,p.yaw}; }

    private static float[] interpolate(Packet a,Packet b,double progress) {
        double dx=b.x-a.x,dy=b.y-a.y,chord=Math.hypot(dx,dy);
        double ar=Math.toRadians(a.yaw),br=Math.toRadians(b.yaw);
        double ax=Math.sin(ar),ay=-Math.cos(ar),bx=Math.sin(br),by=-Math.cos(br);
        double sign=dx*(ax+bx)+dy*(ay+by)>=0?1:-1;
        ax*=sign;ay*=sign;bx*=sign;by*=sign;
        double determinant=ax*by-ay*bx;
        double x=a.x+dx*progress,y=a.y+dy*progress;
        double yaw=a.yaw+delta(a.yaw,b.yaw)*progress;
        if(chord>.00001&&Math.abs(determinant)>.0001&&Math.abs(delta(a.yaw,b.yaw))<150) {
            double start=(dx*by-dy*bx)/determinant,end=(ax*dy-ay*dx)/determinant;
            // Only an intersection ahead of the first tangent and behind the
            // second is a plausible local piece of the original quadratic rail.
            if(start>0&&end>0&&start<chord*2.5&&end<chord*2.5&&start+end<chord*3) {
                double cx=a.x+ax*start,cy=a.y+ay*start;
                double[] arc=new double[ARC_SAMPLES+1];double px=a.x,py=a.y;
                for(int i=1;i<=ARC_SAMPLES;i++) {
                    double t=i/(double)ARC_SAMPLES,u=1-t;
                    double qx=u*u*a.x+2*u*t*cx+t*t*b.x,qy=u*u*a.y+2*u*t*cy+t*t*b.y;
                    arc[i]=arc[i-1]+Math.hypot(qx-px,qy-py);px=qx;py=qy;
                }
                double distance=arc[ARC_SAMPLES]*progress;int index=1;
                while(index<ARC_SAMPLES&&arc[index]<distance)index++;
                double length=arc[index]-arc[index-1];
                double t=(index-1+(length<.0000001?0:(distance-arc[index-1])/length))/ARC_SAMPLES,u=1-t;
                x=u*u*a.x+2*u*t*cx+t*t*b.x;y=u*u*a.y+2*u*t*cy+t*t*b.y;
                double tx=u*(cx-a.x)+t*(b.x-cx),ty=u*(cy-a.y)+t*(b.y-cy);
                yaw=Math.toDegrees(Math.atan2(tx*sign,-ty*sign));
            }
        }
        return new float[]{(float)x,(float)y,(float)(a.height+(b.height-a.height)*progress),angle((float)yaw)};
    }
    private static boolean finite(float value) { return !Float.isNaN(value)&&!Float.isInfinite(value); }
    private static float angle(float value) { return (value%360+360)%360; }
    private static double delta(float from,float to) { return (to-from+540)%360-180; }
}
