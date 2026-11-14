package org.wurmrail.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Dependency-free integration tests: run with java org.wurmrail.core.RailNetworkSelfTest. */
public final class RailNetworkSelfTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        movementAndCurves(); junctions(); reversal(); coldStartOrientation(); automaticShuttle(); automaticBlockWaiting(); collisions(); coupling();
        worldGuard(); checkpoints(); persistence(); validation();
        eightDirections(); modelCenterlineAudit(); mixedCurveSpacing(); diagonalCrossings(); legacyMigration(); curvedCheckpoint(); curvedAutomation(); initialBendCoupling();
        passengerManualStops(); passengerCarAlignment(); passengerCurvesAndReverse(); passengerRoutes(); passengerCheckpoints();
        physicalJunctionDirections(); physicalJunctionLocks(); physicalJunctionMigration();
        symmetricTraction();engineLeadingAutomaticDeparture();stationaryMidTileDeparture();occupiedReversalPoints();savedBackingRecovery();
        followingHeadway();
        caveEntrances();
        System.out.println("RailNetworkSelfTest: " + assertions + " assertions passed.");
    }
    private static RailNetwork tunnelLine(int dx,int dy) {
        RailNetwork n=new RailNetwork();
        for(int i=0;i<=40;i++) {
            int layer=i>=15&&i<=24?-1:0;
            n.addNode("t"+i,100+i*dx,100+i*dy,layer);
            if(i>0) {
                if(n.nodes.get("t"+(i-1)).layer==layer)n.connect("t"+(i-1),"t"+i);
                else n.connectEntrance("t"+(i-1),"t"+i);
            }
        }
        return n;
    }
    private static void caveEntrances() throws Exception {
        for(int[] heading:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            int dx=heading[0],dy=heading[1];RailNetwork n=tunnelLine(dx,dy);
            RailNetwork.Train t=n.createTrain("tunnel",1,Arrays.asList(76001L,76002L,76003L),"t10","t11");
            boolean mixed=false;int transitions=0;
            for(int traction:new int[]{1,-1}) {
                if(traction<0)n.reverse(t.name);n.start(t.name);
                for(int tick=0;tick<900&&t.powered;tick++) {
                    List<RailNetwork.Pose> prior=t.poses;n.tick(.1);
                    Set<Integer> layers=new HashSet<Integer>();
                    for(int c=0;c<t.poses.size();c++) {
                        RailNetwork.Pose pose=t.poses.get(c),old=prior.get(c);
                        int tileX=(int)Math.floor(pose.x/4.0),tileY=(int)Math.floor(pose.y/4.0);
                        int ordinal=(tileX-100)*dx+(tileY-100)*dy;
                        check(pose.layer==(ordinal>=15&&ordinal<=24?-1:0),"each car changes layer at its native cardinal portal boundary in either direction");
                        check(n.nodes.get(pose.railName).layer==pose.layer,"portal pose identifies its owning layer and track");
                        check(Math.hypot(pose.x-old.x,pose.y-old.y)<=.5001,"cave entry never teleports vehicles or consumes excess movement");
                        if(c>0)eq(Math.hypot(pose.x-t.poses.get(c-1).x,pose.y-t.poses.get(c-1).y),4,"portal consist retains four metre spacing");
                        if(pose.layer!=old.layer)transitions++;
                        layers.add(pose.layer);
                    }
                    mixed|=layers.size()==2;
                }
                check(t.status.equals("End of track"),"multi-car train crosses both cave entrances to the terminal in both traction directions");
            }
            check(mixed&&transitions==12,"all three cars independently enter and leave both directions without a whole-consist layer flip");
        }
        RailNetwork n=tunnelLine(1,0);
        RailNetwork.Train spanning=n.createTrain("spanning",1,Arrays.asList(76101L,76102L,76103L),"t16","t17");
        check(spanning.poses.get(0).layer==-1&&spanning.poses.get(2).layer==0,"train claim can span an entrance");
        n.addCar(spanning.name,76104L);check(spanning.poses.get(3).layer==0,"coupling extends safely behind an entrance-spanning train");
        RailNetwork.Checkpoint checkpoint=n.checkpoint(spanning.name);n.start(spanning.name);n.tick(2);n.restore(checkpoint);
        check(spanning.poses.get(0).layer==-1&&spanning.poses.get(3).layer==0&&!spanning.powered,"rollback restores individual vehicle layers across an entrance");
        Path directory=Files.createTempDirectory("wurmrail-cave-entrances-");Path file=directory.resolve("state.properties");
        try {
            n.save(file);Properties p=properties(file);
            check("4".equals(p.getProperty("format"))&&"2".equals(p.getProperty("entrances")),"portal edges extend format 4 with explicit optional entrance records");
            RailNetwork restored=RailNetwork.load(file);RailNetwork.Train loaded=restored.trains.get(spanning.name);
            for(int i=0;i<loaded.poses.size();i++) {
                check(loaded.poses.get(i).layer==spanning.poses.get(i).layer,"save and restart preserve each entrance-spanning vehicle layer");
                eq(loaded.poses.get(i).x,spanning.poses.get(i).x,"portal save preserves vehicle position");
            }
            check(restored.nodes.get("t14").links.contains("t15"),"saved portal restores its explicit cross-layer graph edge");
            restored.setRoute(loaded.name,Arrays.asList("t35"),0);restored.start(loaded.name);restored.tick(40);
            check(loaded.status.equals("Destination reached")&&loaded.poses.get(0).layer==0,"automatic pathfinding reaches an outside destination through the saved tunnel");
            p.remove("entrances");p.remove("entrance.0.first");p.remove("entrance.0.second");p.remove("entrance.1.first");p.remove("entrance.1.second");writeState(file,p);
            try {RailNetwork.load(file);throw new AssertionError("undeclared cross-layer edge accepted");}
            catch(IOException expected) {check(expected.getMessage().contains("no saved cave entrance"),"cross-layer state without explicit portal data fails closed");}
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(directory)) {files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException error){throw new RuntimeException(error);}});}
        }
        RailNetwork validation=new RailNetwork();validation.addNode("surface",10,10,0);validation.addNode("cave",11,10,-1);
        validation.addNode("diagonal",11,11,-1);validation.addNode("distant",12,10,-1);validation.addNode("same",11,10,0);validation.addNode("below",10,10,-1);
        fails(()->validation.connect("surface","cave"),"ordinary rail linking cannot create a cave entrance");
        fails(()->validation.connectEntrance("surface","same"),"portal API rejects same-layer edges");
        fails(()->validation.connectEntrance("surface","diagonal"),"portal API rejects diagonal cave mouths");
        fails(()->validation.connectEntrance("surface","distant"),"portal API rejects nonadjacent cave mouths");
        fails(()->validation.connectEntrance("surface","below"),"portal API rejects same-tile vertical teleports");
        validation.connectEntrance("surface","cave");fails(()->validation.connect("surface","same"),"a portal cannot share an ambiguous compass side with another link");
        for(double tick:new double[]{.1,.25}) {
            RailNetwork headway=tunnelLine(1,0);
            RailNetwork.Train leader=headway.createTrain("leader",1,Arrays.asList(76201L),"t15","t16");
            headway.start(leader.name);headway.tick(.8);headway.stop(leader.name);
            RailNetwork.Train follower=headway.createTrain("follower",1,Arrays.asList(76202L),"t12","t13");
            headway.setRoute(follower.name,Arrays.asList("t35"),0);headway.start(follower.name);
            for(int step=0;step<250;step++) {
                headway.tick(tick);
                check(Math.hypot(leader.poses.get(0).x-follower.poses.get(0).x,leader.poses.get(0).y-follower.poses.get(0).y)>=RailNetwork.TRAIN_CLEARANCE-.0001,"following clearance spans the surface/cave layer boundary");
            }
            check(follower.powered&&follower.speed==0&&follower.poses.get(0).layer==0&&leader.poses.get(0).layer==-1,"follower waits outside a portal behind a stopped underground locomotive");
        }
    }
    private static void followingHeadway() {
        for(int traction:new int[]{1,-1})for(double tick:new double[]{.1,.25}) {
            RailNetwork n=line(60);
            RailNetwork.Train leader=n.createTrain("leader",1,Arrays.asList(75001L,75002L),"p25","p26");
            RailNetwork.Train follower=n.createTrain("follower",1,Arrays.asList(75003L),traction>0?"p18":"p32",traction>0?"p19":"p33");
            if(traction<0) { n.reverse(leader.name);n.reverse(follower.name); }
            n.setRoute(follower.name,Arrays.asList(traction>0?"p55":"p5"),0);n.start(follower.name);
            for(int step=0;step<500;step++) {
                n.tick(tick);
                for(RailNetwork.Pose other:leader.poses)check(Math.hypot(other.x-follower.poses.get(0).x,other.y-follower.poses.get(0).y)>=RailNetwork.TRAIN_CLEARANCE-.0001,"following train respects stopped vehicle clearance in both traction directions");
            }
            check(follower.powered&&follower.speed==0&&follower.status.contains("Waiting for space"),"automatic follower waits safely behind stopped leader without losing power");
            float before=follower.poses.get(0).x;n.start(leader.name);
            for(int step=0;step<100;step++) {
                n.tick(tick);
                for(RailNetwork.Pose other:leader.poses)check(Math.hypot(other.x-follower.poses.get(0).x,other.y-follower.poses.get(0).y)>=RailNetwork.TRAIN_CLEARANCE-.0001,"moving leader and follower preserve clearance across complete server polls");
            }
            check((follower.poses.get(0).x-before)*traction>1,"follower resumes automatically when the leading train moves away");
        }
    }
    private static final int[][] COMPASS={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
    private static RailNetwork junctionSpokes() {
        RailNetwork n=new RailNetwork(); n.addNode("hub",100,100,0);
        for(int side=0;side<8;side++)for(int length=1;length<=4;length++) {
            n.addNode("s"+side+"_"+length,100+COMPASS[side][0]*length,100+COMPASS[side][1]*length,0);
            n.connect(length==1?"hub":"s"+side+"_"+(length-1),"s"+side+"_"+length);
        }
        return n;
    }
    private static void physicalJunctionDirections() {
        RailNetwork choices=junctionSpokes();
        check(choices.getJunctionAlignment("hub")==0,"new multi-side junction begins safely unset");
        check(choices.junctionAlignments("hub").size()==28,"eight physical compass sides yield exactly 28 unordered pairs");
        for(int invalid:new int[]{-1,1,7,255,256})fails(()->choices.setJunctionAlignment("hub",invalid),"invalid physical pair mask rejected");
        List<Integer> pairs=choices.junctionAlignments("hub");
        for(int mask:pairs)check(choices.cycleJunctionAlignment("hub")==mask,"cycling visits stable absolute compass pairs");
        check(choices.cycleJunctionAlignment("hub")==pairs.get(0),"junction cycling wraps to the first pair");
        choices.setSwitch("hub","s4_1");check(choices.getJunctionAlignment("hub")==17,"legacy single-side shim chooses its absolute opposite without a train heading");
        for(int first=0;first<8;first++)for(int second=first+1;second<8;second++) {
            int mask=(1<<first)|(1<<second);
            for(int incoming:new int[]{first,second}) {
                int outgoing=incoming==first?second:first; RailNetwork n=junctionSpokes();
                n.setJunctionAlignment("hub",mask);
                RailNetwork.Train t=n.createTrain("pair",1,Arrays.asList(3001L),"s"+incoming+"_3","s"+incoming+"_2");
                n.start(t.name); n.tick(60);
                eq(t.poses.get(0).x,n.nodes.get("s"+outgoing+"_4").worldX(),"absolute pair routes incoming side "+incoming+" to side "+outgoing+" X");
                eq(t.poses.get(0).y,n.nodes.get("s"+outgoing+"_4").worldY(),"absolute pair routes incoming side "+incoming+" to side "+outgoing+" Y");
                check(t.status.equals("End of track")&&n.getJunctionAlignment("hub")==mask,"pair selection remains physically unchanged after forward travel");
                n.reverse(t.name);n.start(t.name);n.tick(60);
                eq(t.poses.get(0).x,n.nodes.get("s"+incoming+"_4").worldX(),"same pair automatically supports return approach X");
                eq(t.poses.get(0).y,n.nodes.get("s"+incoming+"_4").worldY(),"same pair automatically supports return approach Y");
                check(n.getJunctionAlignment("hub")==mask,"reverse travel never remaps physical compass sides");
            }
        }
        for(int incoming=0;incoming<8;incoming++) {
            RailNetwork n=junctionSpokes(); int first=(incoming+1)%8,second=(incoming+3)%8;
            n.setJunctionAlignment("hub",(1<<first)|(1<<second));
            RailNetwork.Train t=n.createTrain("wrongLeg",1,Arrays.asList(3002L),"s"+incoming+"_3","s"+incoming+"_2");
            n.start(t.name);n.tick(60);double magnitude=Math.hypot(COMPASS[incoming][0],COMPASS[incoming][1]);
            eq(t.poses.get(0).x,402+2*COMPASS[incoming][0]+.45*COMPASS[incoming][0]/magnitude,"wrong physical leg stops with nose outside junction X");
            eq(t.poses.get(0).y,402+2*COMPASS[incoming][1]+.45*COMPASS[incoming][1]/magnitude,"wrong physical leg stops with nose outside junction Y");
            check(!t.powered&&t.status.contains("different sides"),"wrong incoming side cannot use a remaining single exit");
            final RailNetwork blocked=n; final String wrong="s"+incoming+"_1";
            fails(()->blocked.createTrain("badOrigin",1,Arrays.asList(3003L),"hub",wrong),"initial facing cannot bypass a physical pair");
        }
        RailNetwork sparse=line(8); sparse.addNode("south",13,11,0); sparse.connect("p3","south");
        fails(()->sparse.setJunctionAlignment("p3",(1<<0)|(1<<2)),"pair cannot select an unconnected compass side");
    }
    private static void physicalJunctionLocks() {
        RailNetwork n=line(24); for(int i=1;i<=8;i++){n.addNode("south"+i,20,10+i,0);n.connect(i==1?"p10":"south"+(i-1),"south"+i);}
        RailNetwork.Train a=n.createTrain("autoLock",1,Arrays.asList(3101L,3102L,3103L),"p4","p5");
        RailNetwork.Train b=n.createTrain("otherLock",2,Arrays.asList(3104L),"p20","p21");
        fails(()->n.requestJunctionAlignment(b.name,"p10",0),"a train cannot reserve an unset junction as a route");
        n.setRoute(a.name,Arrays.asList("south8"),0); n.start(a.name);
        RailNetwork.Checkpoint beforeA=n.checkpoint(a.name),beforeB=n.checkpoint(b.name);
        RailNetwork.JunctionCheckpoint shared=n.checkpointJunctions();check(shared==n.checkpointJunctions(),"unchanged checkpoints share an immutable physical junction snapshot");
        float initialA=a.poses.get(0).x,initialB=b.poses.get(0).x;
        for(int i=0;i<1000&&n.nodes.get("p10").getAlignmentOwner()==null;i++) {
            RailNetwork.Pose prior=a.poses.get(0);n.tick(.1);RailNetwork.Pose now=a.poses.get(0);
            check(Math.hypot(now.x-prior.x,now.y-prior.y)<=a.maxSpeed*.1+.0001,"automatic alignment never teleports the approaching locomotive");
        }
        int westSouth=(1<<6)|(1<<4),eastSouth=(1<<2)|(1<<4);
        check(n.getJunctionAlignment("p10")==westSouth&&a.name.equals(n.nodes.get("p10").getAlignmentOwner()),"automatic routing requests and owns the actual incoming/outgoing pair");
        check(!n.requestJunctionAlignment(b.name,"p10",eastSouth),"another train cannot take a reserved alignment");
        fails(()->n.setJunctionAlignment("p10",eastSouth),"manual edit cannot override an automatic owner lock");
        long changed=n.getJunctionRevision();
        n.restore(beforeA);n.restore(beforeB);
        check(n.getJunctionAlignment("p10")==0&&n.nodes.get("p10").getAlignmentOwner()==null,"all-train rollback restores physical alignment and releases rolled-back reservations");
        eq(a.poses.get(0).x,initialA,"all-train rollback restores approaching train pose");eq(b.poses.get(0).x,initialB,"all-train rollback restores independent train pose");
        check(n.getJunctionRevision()>changed,"rollback increments the physical-state revision for world synchronization");
        n.start(a.name);
        for(int i=0;i<2000&&a.poses.get(0).y<48;i++)n.tick(.1);
        check(a.name.equals(n.nodes.get("p10").getAlignmentOwner())&&a.poses.get(2).y<44.45,"alignment remains locked after locomotive passes while rear wagons still span the curve");
        check(!n.releaseJunctionAlignment(a.name,"p10"),"owner cannot release its physical junction while its rear still occupies it");
        for(int i=0;i<2000&&n.nodes.get("p10").getAlignmentOwner()!=null;i++)n.tick(.1);
        check(n.nodes.get("p10").getAlignmentOwner()==null&&a.poses.get(2).y>=44.45-EPS,"automatic ownership releases only after the rear and buffer clear");
        n.setJunctionAlignment("p10",eastSouth);check(n.getJunctionAlignment("p10")==eastSouth,"a cleared junction can change even while retained in old path history");
        n.stop(a.name);check(n.requestJunctionAlignment(b.name,"p10",westSouth),"a stopped train may explicitly request a future clear alignment");
        n.stop(b.name);check(n.nodes.get("p10").getAlignmentOwner()==null,"operator STOP cancels an unentered approach reservation");
        n.requestJunctionAlignment(b.name,"p10",westSouth);n.setRoute(b.name,Collections.<String>emptyList());
        check(n.nodes.get("p10").getAlignmentOwner()==null,"changing a stopped train's route releases unused approach reservations");
        n.requestJunctionAlignment(b.name,"p10",westSouth);
        n.removeTrain(b.name);check(n.nodes.get("p10").getAlignmentOwner()==null,"removing a reservation owner releases its transient junction lock");

        RailNetwork occupied=line(12);occupied.addNode("south",15,11,0);occupied.connect("p5","south");
        occupied.setJunctionAlignment("p5",(1<<6)|(1<<2));
        occupied.createTrain("parkedOnPoints",1,Arrays.asList(3111L),"p5","p6");
        fails(()->occupied.setJunctionAlignment("p5",(1<<6)|(1<<4)),"a manually occupied switch cannot change beneath a parked vehicle");
    }
    private static void physicalJunctionMigration() throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-physical-junction-");Path file=directory.resolve("state.properties");
        try {
            RailNetwork n=junctionSpokes();n.setJunctionAlignment("hub",(1<<0)|(1<<2));n.save(file);
            Properties p=properties(file);legacy(p,"2");setLegacySide(p,"hub","s4_1");writeState(file,p);
            RailNetwork old=RailNetwork.load(file);check(old.getJunctionAlignment("hub")==17,"format 2 selected side migrates to its deterministic absolute opposite");
            setLegacySide(p,"hub","");writeState(file,p);check(RailNetwork.load(file).getJunctionAlignment("hub")==0,"unconfigured legacy junction remains safely unset");
            Files.delete(file);
            n=line(12);n.addNode("south",14,11,0);n.connect("p4","south");n.setJunctionAlignment("p4",(1<<6)|(1<<4));
            RailNetwork.Train t=n.createTrain("legacyOccupied",1,Arrays.asList(3201L,3202L),"p4","south");n.save(file);
            p=properties(file);legacy(p,"2");setLegacySide(p,"p4","p5");writeState(file,p);
            RailNetwork loaded=RailNetwork.load(file);
            check(loaded.getJunctionAlignment("p4")==((1<<6)|(1<<4)),"concrete occupied legacy train path takes precedence over a contradictory selected side");
            eq(loaded.trains.get(t.name).poses.get(0).x,t.poses.get(0).x,"legacy occupied switch preserves physical curve X");
            eq(loaded.trains.get(t.name).poses.get(0).y,t.poses.get(0).y,"legacy occupied switch preserves physical curve Y");
            Files.delete(file);loaded.save(file);Properties current=properties(file);
            check(current.getProperty("format").equals("4"),"physical pair state persists in signalling format 4");
            check(loaded.nodes.get("p4").getAlignmentOwner()==null&&RailNetwork.load(file).nodes.get("p4").getAlignmentOwner()==null,"transient ownership never survives persistence");

            Files.delete(file);RailNetwork ambiguous=new RailNetwork();
            ambiguous.addNode("j",30,30,0);ambiguous.addNode("east",31,30,0);ambiguous.addNode("north",30,29,0);ambiguous.addNode("south",30,31,0);
            ambiguous.connect("j","east");ambiguous.connect("j","north");ambiguous.connect("j","south");ambiguous.setJunctionAlignment("j",(1<<0)|(1<<2));
            ambiguous.createTrain("ambiguous",1,Arrays.asList(3203L),"j","north");ambiguous.save(file);
            p=properties(file);legacy(p,"2");p.setProperty("train.0.path","east,j");p.setProperty("train.0.distance","4.0");p.setProperty("train.0.towards","");writeState(file,p);
            try { RailNetwork.load(file);throw new AssertionError("ambiguous occupied legacy points were accepted"); }
            catch(IOException expected) {check(expected.getMessage().contains("ambiguous"),"ambiguous occupied legacy junction fails closed with a diagnostic");}
        } finally {try(java.util.stream.Stream<Path> paths=Files.walk(directory)){Iterator<Path> all=paths.sorted(Comparator.reverseOrder()).iterator();while(all.hasNext())Files.delete(all.next());}}
    }
    private static Properties properties(Path file) throws Exception {Properties p=new Properties();try(java.io.InputStream in=Files.newInputStream(file)){p.load(in);}return p;}
    private static void legacy(Properties p,String format) {
        p.setProperty("format",format);
        for(int i=0;i<Integer.parseInt(p.getProperty("nodes"));i++) {p.remove("node."+i+".alignment");p.setProperty("node."+i+".selected","");}
    }
    private static void setLegacySide(Properties p,String node,String side) {
        for(int i=0;i<Integer.parseInt(p.getProperty("nodes"));i++)if(node.equals(p.getProperty("node."+i+".name")))p.setProperty("node."+i+".selected",side);
    }
    private static void writeState(Path file,Properties p) throws Exception {
        java.lang.reflect.Method checksum=RailNetwork.class.getDeclaredMethod("checksum",Properties.class);checksum.setAccessible(true);p.setProperty("checksum",(String)checksum.invoke(null,p));
        try(java.io.OutputStream out=Files.newOutputStream(file)){p.store(out,"physical junction test fixture");}
    }

    private static void passengerManualStops() {
        RailNetwork n=line(20);
        RailNetwork.Train t=n.createTrain("passengerManual",1,Arrays.asList(2001L),"p1","p2");
        n.passengerStops=(train,rail,car)->rail.name.equals("p5")&&car==2001;
        t.maxSpeed=6; n.start(t.name); n.tick(60);
        check("p5".equals(t.passengerStopRail)&&t.passengerStopCarId==2001,"manual train exposes requested car and rail after a long tick");
        eq(t.poses.get(0).x,n.nodes.get("p5").worldX(),"manual passenger stop clamps at exact rail centre");
        check(t.powered&&t.speed==0&&t.passengerStopSerial==1,"passenger hold preserves power and assigns one arrival serial");
        n.tick(60); eq(t.poses.get(0).x,62,"unreleased arrival stays stationary across later polls");
        check(t.passengerStopSerial==1,"held arrival does not retrigger each poll");
        check(!n.releasePassengerStop(t.name,"p6",1),"wrong rail cannot release a passenger arrival");
        check(!n.releasePassengerStop(t.name,"p5",2),"wrong serial cannot release a passenger arrival");
        n.stop(t.name);
        check(n.releasePassengerStop(t.name,"p5",1),"served passenger arrival can be released after operator STOP");
        n.tick(5); check(!t.powered&&t.speed==0,"passenger release never overrides operator STOP");
        eq(t.poses.get(0).x,62,"operator-stopped train remains at its platform");
        n.start(t.name); n.tick(2);
        check(t.poses.get(0).x>62&&t.passengerStopRail==null&&t.passengerStopSerial==1,"release resumes without immediately stopping at the same centre");
        n.stop(t.name); n.reverse(t.name); n.start(t.name); n.tick(10);
        check("p5".equals(t.passengerStopRail)&&t.passengerStopSerial==2,"leaving and revisiting a platform creates a new arrival");
        eq(t.poses.get(0).x,62,"return trip also clamps at exact platform centre");
        check(!n.releasePassengerStop(t.name,"p5",1),"stale serial from a prior visit cannot release a new arrival");
        check(n.releasePassengerStop(t.name,"p5",2),"matching return-trip arrival releases");
        check(t.powered&&t.direction==-1&&!t.automated,"release retains manual mode, power and reversing direction");

        n=line(12); t=n.createTrain("parkedPickup",1,Arrays.asList(2002L),"p4","p5");
        n.passengerStops=(train,rail,car)->rail.name.equals("p4"); n.tick(.1);
        check("p4".equals(t.passengerStopRail)&&!t.powered,"an already parked train can service waiting passengers without powering on");
        n.start(t.name); n.tick(1); eq(t.poses.get(0).x,58,"powering on cannot leave an unreleased passenger hold");
        n.releasePassengerStop(t.name,"p4",t.passengerStopSerial); n.tick(1);
        check(t.powered&&t.passengerStopRail==null&&t.poses.get(0).x>58,"explicit power-on during boarding resumes after service completes");
    }

    private static void passengerCarAlignment() {
        RailNetwork n=line(30);
        RailNetwork.Train t=n.createTrain("rearDropoff",1,Arrays.asList(2101L,2102L,2103L),"p4","p5");
        n.passengerStops=(train,rail,car)->rail.name.equals("p8")&&car==2103;
        n.start(t.name); n.tick(60);
        check(t.passengerStopCarId==2103&&"p8".equals(t.passengerStopRail),"dropoff holds the actual rider's rear wagon");
        eq(t.poses.get(2).x,74,"rear wagon aligns with dropoff platform");
        eq(t.poses.get(0).x,82,"locomotive remains a full consist length ahead of rear dropoff");

        n=line(30); t=n.createTrain("freeWagonPickup",1,Arrays.asList(2111L,2112L,2113L),"p4","p5");
        n.passengerStops=(train,rail,car)->rail.name.equals("p8")&&car==2112;
        n.start(t.name); n.tick(60);
        check(t.passengerStopCarId==2112,"a full leading vehicle can pass until the available wagon arrives");
        eq(t.poses.get(1).x,74,"the available wagon stops beside the pickup tile");
        eq(t.poses.get(0).x,78,"pickup does not incorrectly align a full locomotive");

        n=line(30); t=n.createTrain("successivePickups",1,Arrays.asList(2121L,2122L,2123L),"p4","p5");
        n.passengerStops=(train,rail,car)->rail.name.equals("p8"); n.start(t.name);
        for(int car=0;car<3;car++) {
            n.tick(60);
            check(t.passengerStopCarId==2121+car&&t.passengerStopSerial==car+1,"successive cars receive separate platform arrivals");
            eq(t.poses.get(car).x,74,"each requested car is aligned before releasing");
            check(n.releasePassengerStop(t.name,"p8",car+1),"each individual car arrival releases once");
        }
        n.tick(60);
        check(t.passengerStopRail==null&&t.passengerStopSerial==3,"served cars do not repeat their stop after moving past the platform");

        n=line(30); t=n.createTrain("reverseRider",1,Arrays.asList(2131L,2132L,2133L),"p12","p13");
        n.passengerStops=(train,rail,car)->rail.name.equals("p5")&&car==2131;
        n.reverse(t.name); n.start(t.name); n.tick(60);
        check(t.passengerStopCarId==2131&&t.direction==-1,"reverse travel can target the trailing locomotive rather than the leading wagon");
        eq(t.poses.get(0).x,62,"reverse rider's actual car aligns with destination");
        eq(t.poses.get(2).x,54,"reverse leading wagon keeps four-metre coupling spacing");

        n=new RailNetwork();
        for(int i=0;i<8;i++) { n.addNode("diagonalPickup"+i,30+i,30-i,0); if(i>0)n.connect("diagonalPickup"+(i-1),"diagonalPickup"+i); }
        t=n.createTrain("diagonalRearDropoff",1,Arrays.asList(2141L,2142L),"diagonalPickup2","diagonalPickup3");
        n.passengerStops=(train,rail,car)->rail.name.equals("diagonalPickup4")&&car==2142;
        n.start(t.name); n.tick(60);
        check(t.passengerStopCarId==2142,"diagonal platform aligns the requested trailing wagon");
        eq(t.poses.get(1).x,138,"diagonal passenger stop clamps exact node centre X");
        eq(t.poses.get(1).y,106,"diagonal passenger stop clamps exact node centre Y");
        check(Math.abs(Math.hypot(t.poses.get(0).x-t.poses.get(1).x,t.poses.get(0).y-t.poses.get(1).y)-4)<.0001,
                "diagonal passenger stop preserves real metre coupling spacing");
    }

    private static void passengerCurvesAndReverse() {
        RailNetwork n=line(9);
        n.addNode("branch1",13,11,0); n.addNode("branch2",13,12,0); n.addNode("branch3",13,13,0);
        n.connect("p3","branch1"); n.connect("branch1","branch2"); n.connect("branch2","branch3");
        RailNetwork.Train t=n.createTrain("curvedPlatform",1,Arrays.asList(2201L),"p1","p2");
        n.passengerStops=(train,rail,car)->rail.name.equals("p3");
        n.start(t.name); n.tick(20);
        check(t.passengerStopRail==null&&t.status.startsWith("Set junction"),"a pickup does not bypass an unset junction or choose a route");
        n.setJunctionAlignment("p3",(1<<6)|(1<<4)); n.start(t.name); t.maxSpeed=6; n.tick(30);
        check("p3".equals(t.passengerStopRail),"passenger stop is recognised after planning a branch bend");
        eq(t.poses.get(0).x,53.5,"bend stop uses the exact quadratic rail midpoint X");
        eq(t.poses.get(0).y,42.5,"bend stop uses the exact quadratic rail midpoint Y");
        n.releasePassengerStop(t.name,"p3",t.passengerStopSerial); n.tick(30);
        check(t.status.equals("End of track"),"manual route continues to its branch terminal after pickup");
        n.reverse(t.name); n.start(t.name); n.tick(30);
        check("p3".equals(t.passengerStopRail)&&t.passengerStopSerial==2,"reverse branch journey revisits its passenger stop");
        eq(t.poses.get(0).x,53.5,"reverse bend stop X stays on the original curve");
        eq(t.poses.get(0).y,42.5,"reverse bend stop Y stays on the original curve");
    }

    private static void passengerRoutes() {
        RailNetwork n=line(20);
        RailNetwork.Train t=n.createTrain("routedPickup",1,Arrays.asList(2301L),"p1","p2");
        n.setStation("p6",true,2); n.setRoute(t.name,Arrays.asList("p6","p15"),2);
        n.passengerStops=(train,rail,car)->rail.name.equals("p6"); n.start(t.name);
        for(int poll=0;poll<400&&t.passengerStopRail==null;poll++) n.tick(.1);
        check("p6".equals(t.passengerStopRail)&&t.routeIndex==1,"coincident route and passenger arrival advances the route exactly once");
        check(t.powered&&t.automated&&t.dwellRemaining>1.9,"passenger arrival preserves automation and station dwell");
        n.tick(1); eq(t.dwellRemaining,1,"station dwell elapses concurrently with passenger boarding");
        check(t.routeIndex==1,"boarding polls do not advance station route again");
        n.releasePassengerStop(t.name,"p6",t.passengerStopSerial);
        check(t.status.equals("Station dwell")&&t.powered,"early passenger release waits for the remaining station dwell");
        n.tick(.5); eq(t.poses.get(0).x,66,"train remains at station until its dwell expires");
        n.tick(2); check(t.poses.get(0).x>66&&t.routeIndex==1,"automatic train resumes its existing next stop after boarding");
        check(t.route.equals(Arrays.asList("p6","p15")),"passenger stops never replace the operator's station route");

        n=line(20); t=n.createTrain("intermediateDropoff",1,Arrays.asList(2311L),"p1","p2");
        n.setRoute(t.name,Arrays.asList("p15"),0); n.passengerStops=(train,rail,car)->rail.name.equals("p4");
        n.start(t.name); n.tick(30);
        check("p4".equals(t.passengerStopRail)&&t.routeIndex==0&&t.automated,"an intermediate passenger stop does not consume a future route destination");
        n.releasePassengerStop(t.name,"p4",t.passengerStopSerial); n.tick(60);
        check(t.passengerStopRail==null&&t.status.equals("Destination reached"),"automatic destination remains reachable after an intermediate stop");
        eq(t.poses.get(0).x,102,"automatic route still ends at its original destination");

        n=line(12); t=n.createTrain("finalDropoff",1,Arrays.asList(2321L),"p1","p2");
        n.setRoute(t.name,Arrays.asList("p6"),0); n.passengerStops=(train,rail,car)->rail.name.equals("p6");
        n.start(t.name); n.tick(30);
        check("p6".equals(t.passengerStopRail)&&!t.powered,"single-stop route powers off while holding its final passenger arrival");
        n.releasePassengerStop(t.name,"p6",t.passengerStopSerial);
        check(!t.powered&&t.status.equals("Destination reached"),"final dropoff release retains the completed route's stopped state");
    }

    private static void passengerCheckpoints() {
        RailNetwork n=line(20);
        RailNetwork.Train t=n.createTrain("passengerRollback",1,Arrays.asList(2401L),"p1","p2");
        n.passengerStops=(train,rail,car)->rail.name.equals("p5"); n.start(t.name); n.tick(2);
        RailNetwork.Checkpoint moving=n.checkpoint(t.name); float x=t.poses.get(0).x;
        n.tick(20); check("p5".equals(t.passengerStopRail)&&t.passengerStopSerial==1,"rollback fixture reaches its first passenger arrival");
        n.restore(moving);
        check(t.passengerStopRail==null&&t.passengerStopCarId==-10L&&t.passengerStopSerial==0,"checkpoint restores all pre-arrival passenger fields");
        eq(t.poses.get(0).x,x,"world transaction rollback restores pre-arrival native target pose");
        n.tick(20); check("p5".equals(t.passengerStopRail)&&t.passengerStopSerial==1,"rolled-back arrival is safely detected on retry");
        RailNetwork.Checkpoint held=n.checkpoint(t.name);
        n.releasePassengerStop(t.name,"p5",1); n.tick(2);
        check(t.poses.get(0).x>62&&t.passengerStopRail==null,"fixture departs after passenger service");
        n.restore(held);
        check("p5".equals(t.passengerStopRail)&&t.passengerStopSerial==1&&t.passengerStopCarId==2401,
                "checkpoint restores a held arrival's identity and serial");
        check(t.powered&&t.speed==0&&t.status.equals("Passenger stop"),"checkpoint restores the held arrival's power and status");
        n.releasePassengerStop(t.name,"p5",1); n.tick(1);
        check(t.passengerStopRail==null&&t.passengerStopSerial==1&&t.poses.get(0).x>62,"checkpoint restores served-stop suppression so release cannot immediately retrigger");

        n=line(20); t=n.createTrain("blockedPassenger",1,Arrays.asList(2411L),"p1","p2");
        n.passengerStops=(train,rail,car)->rail.name.equals("p5");
        n.movementGuard=poses->poses.get(0).x<62; n.start(t.name); n.tick(20);
        check(t.passengerStopRail==null&&t.passengerStopSerial==0&&!t.powered,"an unsafe world pose cannot publish a passenger arrival");
        check(t.poses.get(0).x<62,"blocked passenger request stays before the unsafe platform");
    }
    private static void symmetricTraction() {
        for(int cars:new int[]{1,3}) {
            RailNetwork forward=line(220),backward=line(220);
            List<Long> ids=cars==1?Arrays.asList(8101L):Arrays.asList(8101L,8102L,8103L);
            RailNetwork.Train f=forward.createTrain("Forward",1,ids,"p110","p111"),b=backward.createTrain("Backward",1,ids,"p110","p111");
            eq(f.maxSpeed,5,"new locomotive has 5 metre per second default speed");eq(f.acceleration,.8,"default acceleration remains common to both traction directions");
            float start=f.poses.get(0).x;backward.reverse(b.name);forward.start(f.name);backward.start(b.name);
            for(int step=1;step<=100;step++) {
                forward.tick(.1);backward.tick(.1);
                eq(f.speed,b.speed,"both traction directions have identical acceleration at step "+step);
                eq(f.poses.get(0).x-start,start-b.poses.get(0).x,"forward and backward cover equal distance at step "+step);
                check(f.speed<=5+EPS&&b.speed<=5+EPS,"both traction directions obey the same selected speed cap");
                for(int car=0;car<cars;car++) {
                    eq(f.poses.get(car).rotation,b.poses.get(car).rotation,"backward traction retains every car's physical facing");
                    eq(b.poses.get(0).x-b.poses.get(car).x,car*4,"backward speed preserves complete consist spacing");
                }
            }
            eq(f.speed,5,"forward reaches new default cap");eq(b.speed,5,"reverse reaches the identical cap");
        }
    }
    private static RailNetwork loop(int width,int height) {
        List<int[]> points=new ArrayList<int[]>();
        for(int x=0;x<width;x++)points.add(new int[]{100+x,100});
        for(int y=0;y<height;y++)points.add(new int[]{100+width,100+y});
        for(int x=width;x>0;x--)points.add(new int[]{100+x,100+height});
        for(int y=height;y>0;y--)points.add(new int[]{100,100+y});
        RailNetwork n=track(points.toArray(new int[points.size()][]));n.connect("m"+(points.size()-1),"m0");return n;
    }
    private static void engineLeadingAutomaticDeparture() {
        for(boolean reversing:new boolean[]{false,true}) {
            RailNetwork n=loop(4,2);RailNetwork.Train t=n.createTrain("Preference",1,Arrays.asList(8201L),"m1","m2");
            if(reversing)n.reverse(t.name);
            List<RailNetwork.Pose> initial=new ArrayList<RailNetwork.Pose>(t.poses);
            n.setRoute(t.name,Arrays.asList("m0"),0);n.start(t.name);
            int direction=1;
            check(t.direction==direction,"automatic departure prefers engine-leading traction, including recovery from an already backing train");
            eq(t.poses.get(0).x,initial.get(0).x,"selecting travel direction leaves stationary X unchanged");eq(t.poses.get(0).rotation,initial.get(0).rotation,"selecting travel direction never spins the body");
            for(int i=0;i<800&&t.powered;i++) { n.tick(.1);check(t.direction==direction,"a stop behind the engine is reached by following the real loop"); }
            check(!t.powered&&t.status.equals("Destination reached"),"direction-preserving loop service reaches its chosen stop");
        }
        RailNetwork large=loop(12,6);RailNetwork.Train t=large.createTrain("ShorterBacking",1,Arrays.asList(8211L),"m1","m2");
        RailNetwork.Pose original=t.poses.get(0);large.setRoute(t.name,Arrays.asList("m0","m3"),0);large.start(t.name);
        check(t.direction==1,"a long physical loop still takes priority over a one-tile backing shortcut");eq(t.poses.get(0).rotation,original.rotation,"engine-leading departure preserves stationary locomotive facing");
        check(large.signalIntendedNext(t,"m1","m0",0).equals("m2"),"signal intention follows the engine-leading physical loop");
        check(large.signalIntendedNext(t,"m3","m2",0).equals("m4"),"passing an old itinerary stop cannot manufacture a future reversal");
        large.signals().add("pastOldStop","m3",large.signalSide("m3","m2"),-10);
        large.signals().add("targetExit","m0",large.signalSide("m0","m35"),-10);
        SignalRouteDiscovery.Route traced=SignalRouteDiscovery.discover(large,t.name,large.signals().get("pastOldStop"));
        check(traced.path.get(1).equals("m4")&&traced.path.get(traced.path.size()-1).equals("m0"),"signal lookahead passes an old stop along the same directional route as movement");

        RailNetwork shuttle=line(14);t=shuttle.createTrain("FutureStation",1,Arrays.asList(8221L),"p2","p3");shuttle.setRoute(t.name,Arrays.asList("p8","p1"),0);
        shuttle.signals().add("beforeStation","p4",6,-10);
        traced=SignalRouteDiscovery.discover(shuttle,t.name,shuttle.signals().get("beforeStation"));
        check(traced.path.get(traced.path.size()-1).equals("p8")&&traced.exitSignalId==null&&t.routeIndex==0,"signal lookahead permits a scheduled future station reversal without changing live itinerary progress");
    }
    private static void savedBackingRecovery() throws Exception {
        for(int cars:new int[]{1,3}) {
            RailNetwork n=loop(18,8);List<Long> ids=cars==1?Arrays.asList(8251L):Arrays.asList(8251L,8252L,8253L);
            RailNetwork.Train t=n.createTrain("SavedBacking",1,ids,"m10","m11");n.reverse(t.name);
            n.setRoute(t.name,Arrays.asList("m6","m2"),0);
            Path file=Files.createTempFile("wurmrail-forward-recovery-",".properties");
            Files.delete(file); // The production saver deliberately refuses to overwrite corrupt existing state.
            try {
                n.save(file);n=RailNetwork.load(file);t=n.trains.get("SavedBacking");
                check(t.direction==-1&&!t.powered,"saved backing traction remains compatible and safely stopped on load");
                List<RailNetwork.Pose> before=new ArrayList<RailNetwork.Pose>(t.poses);n.start(t.name);
                check(t.direction==1&&t.powered,"powering on saved backwards train restores engine-leading loop travel");
                for(int car=0;car<cars;car++) { eq(t.poses.get(car).x,before.get(car).x,"recovering saved traction leaves every car X unchanged");eq(t.poses.get(car).y,before.get(car).y,"recovering saved traction leaves every car Y unchanged");eq(t.poses.get(car).rotation,before.get(car).rotation,"recovering saved traction leaves every car physical heading unchanged"); }
                int visits=0,old=t.routeIndex;boolean curved=false;
                for(int tick=0;tick<2200&&visits<2;tick++) {
                    RailNetwork.Pose prior=t.poses.get(0);n.tick(.1);RailNetwork.Pose after=t.poses.get(0);
                    check(t.direction==1,"recovered automatic service remains engine-leading throughout the long physical circle");
                    if(t.routeIndex!=old) { visits++;old=t.routeIndex; }
                    if(Math.abs(after.rotation-prior.rotation)>.001) { check(Math.hypot(after.x-prior.x,after.y-prior.y)>.001,"turnaround rotation occurs only while moving through a bend");curved=true; }
                }
                check(visits>=2&&curved,"saved backwards service completes two real long-circle station arrivals");
            } finally { Files.deleteIfExists(file.resolveSibling(file.getFileName()+".bak"));Files.deleteIfExists(file); }
            n=loop(18,8);t=n.createTrain("RunningBacking",1,ids,"m10","m11");n.setRoute(t.name,Arrays.asList("m6","m2"),.2);n.reverse(t.name);
            n.signals().add("backingStationApproach","m7",n.signalSide("m7","m8"),-10);
            SignalRouteDiscovery.Route approach=SignalRouteDiscovery.discover(n,t.name,n.signals().get("backingStationApproach"));
            check(approach.path.get(approach.path.size()-1).equals("m6")&&approach.exitSignalId==null,"backing signal lookahead ends at a scheduled station recovery before virtually changing the locomotive's traction");
            // Reproduce an older world's already moving automatic backing service.
            t.powered=true;t.speed=.8;boolean arrived=false,recovered=false;
            for(int tick=0;tick<1000&&!recovered;tick++) {
                List<RailNetwork.Pose> before=new ArrayList<RailNetwork.Pose>(t.poses);int oldDirection=t.direction;double oldSpeed=t.speed;
                n.tick(.1);arrived|=t.routeIndex==1;
                if(t.direction!=oldDirection) {
                    check(arrived&&oldSpeed<EPS,"moving backing service restores forward traction only after a stationary station arrival");
                    for(int car=0;car<cars;car++) { eq(t.poses.get(car).x,before.get(car).x,"station recovery preserves every car stationary X");eq(t.poses.get(car).y,before.get(car).y,"station recovery preserves every car stationary Y");eq(t.poses.get(car).rotation,before.get(car).rotation,"station recovery preserves every car physical heading"); }
                    recovered=true;
                } else if(!arrived)check(t.direction==-1,"legacy moving service never flips its traction before the next safe stop");
            }
            check(recovered&&t.direction==1,"already moving backwards automatic service recovers engine-leading operation after station dwell");
        }
        // The desired branch lies between the old leading cart and the locomotive.
        // Recovery must plan from the locomotive, which can only reach it around the loop.
        RailNetwork n=line(19);
        for(int i=1;i<=3;i++) { n.addNode("recoveryBranch"+i,16,10+i,0);n.connect(i==1?"p6":"recoveryBranch"+(i-1),"recoveryBranch"+i); }
        String previous="p18";
        for(int y=11;y<=13;y++) { String next="recoveryDown"+y;n.addNode(next,28,y,0);n.connect(previous,next);previous=next; }
        for(int x=27;x>=17;x--) { String next="recoveryAcross"+x;n.addNode(next,x,13,0);n.connect(previous,next);previous=next; }
        n.connect(previous,"recoveryBranch3");n.setJunctionAlignment("p6",(1<<2)|(1<<6));
        RailNetwork.Train t=n.createTrain("BranchRecovery",1,Arrays.asList(8261L,8262L,8263L),"p7","p8");n.reverse(t.name);
        n.setRoute(t.name,Arrays.asList("recoveryBranch1"),0);n.start(t.name);
        check(t.direction==1&&t.powered,"coupled recovery uses the actual engine-leading loop rather than a branch under its own consist");
        for(int tick=0;tick<2000&&t.powered;tick++) { n.tick(.1);check(t.direction==1,"coupled branch recovery stays engine-leading"); }
        check(!t.powered&&t.status.equals("Destination reached"),"coupled branch recovery reaches the station after clearing occupied points via physical circuit");
        n=line(20);n.addNode("bareRecoveryBranch",15,11,0);n.connect("p5","bareRecoveryBranch");
        int alignment=(1<<2)|(1<<6);n.setJunctionAlignment("p5",alignment);
        t=n.createTrain("BareJunctionRecovery",1,Arrays.asList(8271L),"p5","p6");n.reverse(t.name);
        RailNetwork.Pose before=t.poses.get(0);n.setRoute(t.name,Arrays.asList("p12"),0);n.start(t.name);
        check(t.powered&&t.direction==1&&n.getJunctionAlignment("p5")==alignment,"bare locomotive on straight three-way points recovers using its actual paired incoming side");
        eq(t.poses.get(0).rotation,before.rotation,"bare junction recovery preserves its physical facing");
    }
    private static void stationaryMidTileDeparture() {
        for(int cars:new int[]{1,3})for(boolean initiallyBacking:new boolean[]{false,true}) {
            RailNetwork n=line(60);List<Long> ids=cars==1?Arrays.asList(8301L):Arrays.asList(8301L,8302L,8303L);
            RailNetwork.Train t=n.createTrain("MidTile",1,ids,"p30","p31");if(initiallyBacking)n.reverse(t.name);
            n.start(t.name);n.tick(1);n.stop(t.name);List<RailNetwork.Pose> before=new ArrayList<RailNetwork.Pose>(t.poses);
            int intended=initiallyBacking?1:-1;String target=initiallyBacking?"p50":"p5";
            n.setRoute(t.name,Arrays.asList(target),0);n.start(t.name);
            check(t.direction==intended,"stopped mid-tile train can select necessary opposite traction before a false disconnected frontier");
            for(int car=0;car<cars;car++) { eq(t.poses.get(car).x,before.get(car).x,"stationary mid-tile selection cannot snap any car");eq(t.poses.get(car).rotation,before.get(car).rotation,"stationary mid-tile selection cannot spin any car"); }
            n.tick(2);check(intended*(t.poses.get(0).x-before.get(0).x)>0,"mid-tile route starts moving in the selected traction direction");
            for(int i=0;i<1800&&t.powered;i++)n.tick(.1);
            check(!t.powered&&t.status.equals("Destination reached"),"single or coupled mid-tile restart reaches its target without disconnected halt");
        }
    }
    private static void occupiedReversalPoints() {
        for(boolean forwardAlternative:new boolean[]{false,true}) {
            RailNetwork n=line(forwardAlternative?19:12);
            for(int i=1;i<=3;i++) { n.addNode("branch"+i,15,10+i,0);n.connect(i==1?"p5":"branch"+(i-1),"branch"+i); }
            if(forwardAlternative) {
                String previous="p18";
                for(int y=11;y<=13;y++) { String next="outerDown"+y;n.addNode(next,28,y,0);n.connect(previous,next);previous=next; }
                for(int x=27;x>=16;x--) { String next="outerAcross"+x;n.addNode(next,x,13,0);n.connect(previous,next);previous=next; }
                n.connect(previous,"branch3");
            }
            int original=(1<<2)|(1<<6);n.setJunctionAlignment("p5",original);
            RailNetwork.Train t=n.createTrain("RearOnPoints",1,Arrays.asList(8401L,8402L,8403L),"p7","p8");
            List<RailNetwork.Pose> before=new ArrayList<RailNetwork.Pose>(t.poses);n.setRoute(t.name,Arrays.asList("branch1"),0);n.start(t.name);
            check(t.direction==1,"automatic route rejects backing that would change a switch already beneath a coupled rear");
            check(n.getJunctionAlignment("p5")==original,"unsafe backing never remaps the occupied physical alignment");
            for(int car=0;car<3;car++) { eq(t.poses.get(car).x,before.get(car).x,"rejected backing leaves each car stationary");eq(t.poses.get(car).rotation,before.get(car).rotation,"rejected backing leaves each car facing unchanged"); }
            if(forwardAlternative) {
                check(t.powered,"a feasible forward circuit is used instead of an unsafe shorter backing route");
                check(n.signalIntendedNext(t,"p7","p6",0).equals("p8"),"a signal at the departure centre shares the physically feasible forward choice");
                for(int i=0;i<2200&&t.powered;i++)n.tick(.1);
                check(!t.powered&&t.status.equals("Destination reached"),"feasible oriented circuit reaches branch without shunting or self-lock");
            } else {
                check(!t.powered&&t.status.contains("Move the whole train clear of junction p5"),"unsupported shunt stops with an explicit clearance instruction instead of false red reservation wait");
                n.tick(10);eq(t.poses.get(0).x,before.get(0).x,"unsupported clearance maneuver remains safely stopped");
            }
        }
    }
    private static void movementAndCurves() {
        RailNetwork n = new RailNetwork();
        for(int i=0;i<=8;i++)n.addNode("a"+i,10+i,10,0);
        for(int i=1;i<=6;i++)n.addNode("b"+i,18,10+i,0);
        for(int i=1;i<=8;i++)n.connect("a"+(i-1),"a"+i);
        n.connect("a8","b1");for(int i=2;i<=6;i++)n.connect("b"+(i-1),"b"+i);
        RailNetwork.Train t=n.createTrain("curve",1,Arrays.asList(101L,102L,103L),"a2","a3");
        eq(t.poses.get(0).x,50,"locomotive starts at requested rail");
        eq(t.poses.get(2).x,42,"every trailing cart starts on rail");
        n.start(t.name);
        boolean sawCurve=false;
        for(int i=0;i<500;i++) {
            n.tick(.1);
            for(RailNetwork.Pose p:t.poses) {
                check(p.x>=42-EPS&&p.x<=74+EPS&&p.y>=42-EPS&&p.y<=66+EPS,"pose remains on bounded track");
                boolean straight=(p.x<=72+EPS&&Math.abs(p.y-42)<EPS)||(p.y>=44-EPS&&Math.abs(p.x-74)<EPS);
                boolean bend=p.x>=72-EPS&&p.y<=44+EPS&&
                    Math.abs(Math.sqrt(Math.max(0,(74-p.x)/2))+Math.sqrt(Math.max(0,(p.y-42)/2))-1)<.002;
                check(straight||bend,"curve poses follow the quadratic model centreline");
            }
            if(t.poses.get(0).y>42&&t.poses.get(2).y==42)sawCurve=true;
        }
        check(sawCurve,"cars traverse corner at different times");
        check(!t.powered&&t.status.equals("End of track"),"dead end stops safely");
        eq(t.poses.get(0).y,66,"stops at final node without overshoot");
        eq(t.poses.get(2).y,58,"rear retains on-track spacing after corner");
    }
    private static void junctions() {
        RailNetwork n=line(7);n.addNode("branch",13,11,0);n.connect("p3","branch");
        n.setJunctionAlignment("p3",(1<<6)|(1<<4));
        RailNetwork.Train t=n.createTrain("switch",2,Arrays.asList(201L),"p1","p2");
        n.start(t.name);n.tick(20);
        eq(t.poses.get(0).y,46,"manual junction takes selected branch");
        check(t.status.equals("End of track"),"branch end stops");
        n=line(7);n.addNode("branch",13,11,0);n.connect("p3","branch");
        t=n.createTrain("unset",2,Arrays.asList(202L),"p1","p2");n.start(t.name);n.tick(20);
        check(t.status.startsWith("Set junction"),"unconfigured fork stops instead of choosing unpredictably");
        eq(t.poses.get(0).x,51.55,"unconfigured junction stops before its tile entrance, including the vehicle nose");
    }
    private static void reversal() {
        RailNetwork n=line(12);RailNetwork.Train t=n.createTrain("reverse",3,Arrays.asList(301L,302L,303L),"p3","p4");
        n.start(t.name);n.tick(4);n.stop(t.name);
        float before=t.poses.get(0).x;List<Long> ids=new ArrayList<Long>(t.carIds);
        n.reverse(t.name);n.start(t.name);n.tick(2);
        check(t.poses.get(0).x<before,"reverse moves engine backwards");check(t.carIds.equals(ids),"reverse preserves locomotive-first identity");
        eq(t.poses.get(0).x-t.poses.get(2).x,8,"reverse preserves coupling spacing");
        n.tick(30);check(!t.powered,"reverse dead end stops");eq(t.poses.get(2).x,42,"rear cart leads up to terminal");
    }
    private static void coldStartOrientation() {
        for(int side=0;side<8;side++)for(int cars:new int[]{1,3}) {
            RailNetwork n=new RailNetwork();
            for(int i=0;i<16;i++) {
                n.addNode("d"+i,100+COMPASS[side][0]*i,100+COMPASS[side][1]*i,0);
                if(i>0)n.connect("d"+(i-1),"d"+i);
            }
            List<Long> ids=cars==1?Arrays.asList(1801L):Arrays.asList(1801L,1802L,1803L);
            RailNetwork.Train t=n.createTrain("coldReverse",1,ids,"d8","d9");
            List<RailNetwork.Pose> original=new ArrayList<RailNetwork.Pose>(t.poses);
            n.reverse(t.name);
            for(int car=0;car<cars;car++) {
                eq(t.poses.get(car).rotation,original.get(car).rotation,"cold manual reverse preserves physical heading in every compass direction");
                eq(t.poses.get(car).x,original.get(car).x,"cold manual reverse preserves engine and cart X");
                eq(t.poses.get(car).y,original.get(car).y,"cold manual reverse preserves engine and cart Y");
            }
            n.start(t.name);n.tick(1);n.stop(t.name);
            RailNetwork.Pose moved=t.poses.get(0),start=original.get(0);
            check((moved.x-start.x)*COMPASS[side][0]+(moved.y-start.y)*COMPASS[side][1]<0,"cold reverse backs away from its original facing");
            for(int car=0;car<cars;car++)eq(t.poses.get(car).rotation,original.get(car).rotation,"backward movement preserves every car's physical heading");
            n.reverse(t.name);n.start(t.name);n.tick(.5);n.stop(t.name);
            for(int car=0;car<cars;car++)eq(t.poses.get(car).rotation,original.get(car).rotation,"changing traction again never turns a stationary consist");

            n=new RailNetwork();
            for(int i=0;i<16;i++) {
                n.addNode("d"+i,100+COMPASS[side][0]*i,100+COMPASS[side][1]*i,0);
                if(i>0)n.connect("d"+(i-1),"d"+i);
            }
            t=n.createTrain("coldAutomatic",1,ids,"d8","d9");original=new ArrayList<RailNetwork.Pose>(t.poses);
            n.setRoute(t.name,Arrays.asList("d1"),0);n.start(t.name);
            for(int car=0;car<cars;car++) {
                eq(t.poses.get(car).rotation,original.get(car).rotation,"automatic departure behind the train preserves physical heading");
                eq(t.poses.get(car).x,original.get(car).x,"automatic power on never moves the train X");
                eq(t.poses.get(car).y,original.get(car).y,"automatic power on never moves the train Y");
            }
            n.tick(2);
            check(t.direction==-1,"a first automatic destination behind the engine selects reverse traction");
            for(int car=0;car<cars;car++)eq(t.poses.get(car).rotation,original.get(car).rotation,"automatic backing never flips locomotive or wagon models");
        }
        RailNetwork curve=track(new int[][]{{10,10},{11,10},{12,10},{12,11},{12,12},{12,13}});
        RailNetwork.Train t=curve.createTrain("physicalBend",1,Arrays.asList(1811L),"m0","m1");
        curve.start(t.name);boolean turned=false;
        for(int i=0;i<400;i++) {
            RailNetwork.Pose before=t.poses.get(0);curve.tick(.05);RailNetwork.Pose after=t.poses.get(0);
            if(Math.abs(after.rotation-before.rotation)>.001) {
                check(Math.hypot(after.x-before.x,after.y-before.y)>0,"a physical heading change requires movement along the bend");
                turned=true;
            }
        }
        check(turned,"normal movement still rotates the locomotive through a real track bend");
    }
    private static void automaticShuttle() {
        RailNetwork n=line(14);RailNetwork.Train t=n.createTrain("auto",4,Arrays.asList(401L,402L,403L),"p3","p4");
        n.setStation("p13",true,1);n.setStation("p0",true,1);
        n.setRoute(t.name,Arrays.asList("p13","p0"),1);n.start(t.name);
        int visits=0;String prior="";boolean reverse=false;
        for(int i=0;i<1000;i++) {
            n.tick(.1);
            if(t.status.equals("Station dwell")&&!prior.equals(t.status))visits++;
            reverse|=t.direction<0;prior=t.status;
            check(t.powered,"automatic shuttle remains powered");
        }
        check(visits>=3,"automatic shuttle repeatedly visits both stations");check(reverse,"automatic terminal reverses consist");
    }
    private static void collisions() {
        RailNetwork n=line(20);
        RailNetwork.Train a=n.createTrain("east",5,Arrays.asList(501L,502L),"p3","p4");
        RailNetwork.Train b=n.createTrain("west",6,Arrays.asList(503L,504L),"p16","p17");
        n.reverse(b.name);n.start(a.name);n.start(b.name);
        for(int i=0;i<600;i++) {
            n.tick(.1);
            check(a.poses.get(0).x<b.poses.get(1).x,"head-on consists never overlap");
        }
        check(!a.powered&&!b.powered,"opposing trains stop at occupied blocks");
        // A stationary tail occupies every track block between the first and last car.
        n=line(14);n.createTrain("long",5,Arrays.asList(505L,506L,507L,508L),"p7","p8");
        final RailNetwork occupied=n;
        fails(()->occupied.createTrain("middle",6,Arrays.asList(509L),"p5","p6"),"spawn inside train blocked");
    }
    private static void automaticBlockWaiting() {
        RailNetwork n=line(20);
        RailNetwork.Train a=n.createTrain("following",5,Arrays.asList(551L),"p3","p4");
        RailNetwork.Train b=n.createTrain("parked",5,Arrays.asList(552L),"p7","p8");
        n.setRoute(a.name,Arrays.asList("p13"),0);n.start(a.name);n.tick(15);
        check(a.powered&&a.speed==0&&(a.status.equals("Track occupied")||a.status.equals("Waiting for space behind train parked")),"automatic trains wait with power on at an occupied block");
        n.start(b.name);n.tick(30);
        check(a.status.equals("Destination reached"),"automatic train resumes when the block clears");
        eq(a.poses.get(0).x,94,"automatic follower reaches intended station");
    }
    private static void coupling() {
        RailNetwork n=line(10);RailNetwork.Train t=n.createTrain("couple",7,Arrays.asList(701L),"p3","p4");
        n.addCar(t.name,702);n.addCar(t.name,703);n.addCar(t.name,704);
        eq(t.poses.get(3).x,42,"coupling extends history backwards over real rails");
        fails(()->n.addCar("couple",705),"coupling fails if track is too short");
        check(t.carIds.size()==4,"failed coupling rolls back consist");eq(t.poses.get(0).x,54,"failed coupling leaves engine position unchanged");
        fails(()->n.removeCar("couple",702),"middle cart removal rejected");n.removeCar(t.name,704);check(t.carIds.size()==3,"last cart uncouples");
        n.start(t.name);fails(()->n.addCar("couple",705),"moving train cannot couple");n.stop(t.name);
        fails(()->n.createTrain("duplicate",7,Arrays.asList(703L),"p9","p8"),"same vehicle cannot join two trains");
        RailNetwork terminal=line(5);terminal.createTrain("terminal",7,Arrays.asList(711L),"p0","p1");
        fails(()->terminal.addCar("terminal",712),"coupling at a terminal cannot place a cart in front of the locomotive");
    }
    private static void worldGuard() {
        RailNetwork n=line(14);RailNetwork.Train t=n.createTrain("guard",8,Arrays.asList(801L,802L),"p3","p4");
        final float start=t.poses.get(0).x;n.movementGuard=poses->poses.get(0).x<=start+.5;
        n.start(t.name);n.tick(10);
        check(!t.powered&&t.status.equals("Movement blocked by the world"),"world guard stops unsafe movement");
        check(t.poses.get(0).x<=start+.5,"world rejection does not commit unsafe poses");
        float stopped=t.poses.get(0).x;n.tick(10);eq(t.poses.get(0).x,stopped,"stopped train remains stationary");
        n=line(6);t=n.createTrain("rollback",8,Arrays.asList(811L),"p2","p3");
        n.movementGuard=poses->false;n.start(t.name);n.tick(.1);
        n.disconnect("p2","p3");check(!n.nodes.get("p2").links.contains("p3"),"guard rejection restores tentative track history and reservation");
    }
    private static void persistence() throws Exception {
        Path dir=Files.createTempDirectory("wurmrail-test-");Path file=dir.resolve("network.properties");
        try {
            RailNetwork n=line(14);n.nodes.get("p2").markerId=92345;
            RailNetwork.Train t=n.createTrain("save",99,Arrays.asList(901L,902L,903L),"p4","p5");
            n.setRoute(t.name,Arrays.asList("p13","p0"),2.5);n.start(t.name);n.tick(4.23);n.save(file);
            RailNetwork restored=RailNetwork.load(file);RailNetwork.Train r=restored.trains.get(t.name);
            eq(r.poses.get(0).x,t.poses.get(0).x,"sub-tile location survives reload");
            check(r.carIds.equals(t.carIds)&&r.ownerId==99,"ownership and coupling survive reload");
            check(!r.powered&&r.speed==0,"restart always stops powered trains");
            check(r.automated&&r.route.equals(t.route),"route survives stopped restart");
            check(restored.nodes.get("p2").markerId==92345,"world rail marker identity survives reload");
            n.save(file);check(Files.exists(dir.resolve("network.properties.bak")),"previous state backup exists");
            byte[] bytes=Files.readAllBytes(file);String corrupted=new String(bytes,StandardCharsets.ISO_8859_1).replace("node.0.x=10","node.0.x=99");
            Files.write(file,corrupted.getBytes(StandardCharsets.ISO_8859_1));
            try {RailNetwork.load(file);throw new AssertionError("corrupt state accepted");}catch(IOException expected){assertions++;}
            try {n.save(file);throw new AssertionError("corrupt state overwrote backup");}catch(IOException expected){assertions++;}
            check(Files.exists(dir.resolve("network.properties.bak")),"good backup retained after corrupt current file");
            Files.delete(file);
            try {RailNetwork.load(file);throw new AssertionError("missing primary state silently reset a backed-up network");}catch(IOException expected){assertions++;}
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(dir)) {
                Iterator<Path> all=files.sorted(Comparator.reverseOrder()).iterator();while(all.hasNext())Files.delete(all.next());
            }
        }
    }
    private static void checkpoints() {
        RailNetwork n=line(16);
        RailNetwork.Train t=n.createTrain("transaction",9,Arrays.asList(851L,852L),"p4","p5");
        n.start(t.name);n.tick(2.3);
        RailNetwork.Checkpoint before=n.checkpoint(t.name);
        float x=t.poses.get(0).x;double speed=t.speed;String status=t.status;
        n.tick(3);check(t.poses.get(0).x>x,"movement advances beyond checkpoint");
        n.restore(before);
        eq(t.poses.get(0).x,x,"checkpoint restores engine position");eq(t.poses.get(1).x,before.poses.get(1).x,"checkpoint restores tail position");
        eq(t.speed,speed,"checkpoint restores speed");check(t.powered&&t.status.equals(status),"checkpoint restores running state");
        n.tick(.1);check(t.poses.get(0).x>x,"restored train resumes from restored track history");
        n.stop(t.name);before=n.checkpoint(t.name);n.reverse(t.name);n.start(t.name);n.tick(1);
        n.restore(before);check(t.direction==1&&!t.powered&&t.speed==0,"checkpoint rolls back reversal and power changes");
        n.setRoute(t.name,Arrays.asList("p15","p0"),3);n.start(t.name);
        for(int i=0;i<500&&!t.status.equals("Station dwell");i++)n.tick(.1);
        check(t.status.equals("Station dwell"),"test reaches automatic station dwell");
        before=n.checkpoint(t.name);int routeIndex=t.routeIndex;double dwell=t.dwellRemaining;
        n.tick(5);check(t.dwellRemaining<dwell,"station dwell elapses after checkpoint");
        n.restore(before);check(t.automated&&t.routeIndex==routeIndex,"checkpoint restores route progress");eq(t.dwellRemaining,dwell,"checkpoint restores station dwell");
        n.stop(t.name);n.setRoute(t.name,Arrays.asList("p4","p10"),19);n.start(t.name);
        check(t.route.equals(Arrays.asList("p4","p10"))&&t.routeDwellSeconds==19&&t.routeIndex==0,"test changes the automatic route and route dwell before transaction rollback");
        n.restore(before);
        check(t.route.equals(Arrays.asList("p15","p0")),"checkpoint restores the complete ordered automatic route after a failed route save");
        eq(t.routeDwellSeconds,3,"checkpoint restores route dwell tuning after route replacement");
        check(t.powered&&t.automated&&t.routeIndex==routeIndex&&t.status.equals("Station dwell"),"route rollback restores power, automation, progress and status together");
        eq(t.dwellRemaining,dwell,"route rollback preserves the original remaining station wait");
        n.stop(t.name);n.setRoute(t.name,Collections.<String>emptyList(),0);n.restore(before);
        check(t.route.equals(Arrays.asList("p15","p0"))&&t.automated&&t.powered,"checkpoint route snapshot stays independent when a later manual edit clears the route");
        final RailNetwork.Checkpoint stale=before;n.stop(t.name);n.removeCar(t.name,852);
        fails(()->n.restore(stale),"checkpoint rejects changed consist identity");
        RailNetwork.Checkpoint removed=n.checkpoint(t.name);n.removeTrain(t.name);
        n.createTrain("transaction",9,Arrays.asList(853L),"p4","p5");
        fails(()->n.restore(removed),"checkpoint rejects reused train name");
    }
    private static void validation() {
        RailNetwork n=line(8);fails(()->n.addNode("bad name",1,1,0),"invalid names rejected");
        fails(()->n.addNode("duplicate",10,10,0),"duplicate tile rejected");
        fails(()->n.connect("p0","p2"),"nonadjacent track rejected");
        fails(()->n.tick(Double.NaN),"non-finite time rejected");fails(()->n.tick(61),"excessive tick rejected");
        RailNetwork.Train t=n.createTrain("protect",1,Arrays.asList(1001L,1002L),"p3","p4");
        fails(()->n.disconnect("p2","p3"),"occupied track removal rejected");
        fails(()->n.removeNode("p2"),"occupied rail removal rejected");
        n.start(t.name);fails(()->n.removeTrain("protect"),"moving train disband rejected");n.stop(t.name);n.removeTrain(t.name);
        check(n.trains.isEmpty(),"stopped train disbands");
    }
    private static void eightDirections() {
        int[][] directions={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
        for(int d=0;d<8;d++) {
            int dx=directions[d][0],dy=directions[d][1];RailNetwork n=new RailNetwork();
            for(int i=0;i<10;i++)n.addNode("d"+i,100+i*dx,100+i*dy,0);
            for(int i=1;i<10;i++)n.connect("d"+(i-1),"d"+i);
            RailNetwork.Train t=n.createTrain("heading"+d,1,Arrays.asList(1101L,1102L),"d2","d3");
            RailNetwork.Pose initial=t.poses.get(0);eq(initial.rotation,d*45,"compass heading "+d);
            check(RailNetwork.direction(dx,dy)==d,"public direction agrees with model bit "+d);
            check(n.trackMask("d2")==((1<<d)|(1<<((d+4)%8))),"straight track mask "+d);
            n.start(t.name);n.tick(1);
            RailNetwork.Pose moved=t.poses.get(0);
            eq(Math.hypot(moved.x-initial.x,moved.y-initial.y),.44,"metre-based acceleration on heading "+d);
            eq(Math.hypot(moved.x-t.poses.get(1).x,moved.y-t.poses.get(1).y),4,"four metre spacing on heading "+d);
            n.tick(4);moved=t.poses.get(0);
            eq(Math.hypot(moved.x-initial.x,moved.y-initial.y),10.2,"tile boundary transitions consume the full metric movement budget "+d);
            n.tick(30);eq(t.poses.get(0).x,n.nodes.get("d9").worldX(),"terminal X "+d);
            eq(t.poses.get(0).y,n.nodes.get("d9").worldY(),"terminal Y "+d);
            check(t.status.equals("End of track"),"all eight headings reach the terminal safely");
        }
        RailNetwork n=new RailNetwork();n.addNode("center",10,10,0);
        for(int d=0;d<8;d++){n.addNode("exit"+d,10+directions[d][0],10+directions[d][1],0);n.connect("center","exit"+d);}
        check(n.trackMask("center")==255,"full eight-way junction mask");
        final RailNetwork validationNetwork=n;
        fails(()->validationNetwork.connect("center","center"),"self links rejected");
        n.addNode("cave",11,11,-1);fails(()->validationNetwork.connect("center","cave"),"diagonal cross-layer links rejected");
        n=new RailNetwork();n.addNode("start",10,10,0);n.addNode("end",11,9,0);n.connect("start","end");
        RailNetwork.Train boundary=n.createTrain("diagonalBoundary",1,Arrays.asList(1111L),"start","end");
        boundary.maxSpeed=3;n.start(boundary.name);boundary.speed=3;n.tick(Math.sqrt(8)/3);
        RailNetwork.Pose corner=boundary.poses.get(0);
        eq(corner.x,44,"northeast edge midpoint is the shared tile corner X");
        eq(corner.y,40,"northeast edge midpoint is the shared tile corner Y");
        check("start".equals(corner.railName),"diagonal corner pose identifies its actual owning rail marker");
        check(((int)corner.x/4)!=n.nodes.get(corner.railName).tileX,"diagonal owner survives flooring into an untracked side tile");
    }
    /** Independent copy of the art generator's 32-section rail centreline, for every switch pair. */
    private static void modelCenterlineAudit() {
        int[][] compass={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
        double worst=0;int samples=0;
        for(int a=0;a<8;a++)for(int b=a+1;b<8;b++) {
            int ax=compass[a][0],ay=compass[a][1],bx=compass[b][0],by=compass[b][1];
            RailNetwork n=track(new int[][]{{100+2*ax,100+2*ay},{100+ax,100+ay},{100,100},{100+bx,100+by},{100+2*bx,100+2*by}});
            RailNetwork.Train train=n.createTrain("modelPair"+a+"_"+b,1,Arrays.asList(600000L+a*8+b),"m0","m1");
            List<double[]> rail=new ArrayList<double[]>();double distance=0;
            for(int i=0;i<=32;i++) {
                double t=i/32.0,u=1-t,x=402+2*ax*u*u+2*bx*t*t,y=402+2*ay*u*u+2*by*t*t;
                if(!rail.isEmpty()) { double[] old=rail.get(rail.size()-1);distance+=Math.hypot(x-old[0],y-old[1]); }
                rail.add(new double[]{x,y,distance});
            }
            for(int travel=0;travel<2;travel++) {
                if(travel!=0)n.reverse(train.name);n.start(train.name);train.speed=5;
                int observed=0;
                for(int step=0;step<300&&train.powered;step++) {
                    n.tick(.025);RailNetwork.Pose pose=train.poses.get(0);
                    if(!"m2".equals(pose.railName))continue;
                    double error=project(rail,pose)[1];worst=Math.max(worst,error);samples++;observed++;
                    check(error<.002,"server vehicle anchor follows native asset rail centreline for compass pair "+a+"/"+b+" in travel "+travel+": "+error);
                }
                check(observed>10,"all compass switch shapes are physically traversed in both directions");
                check(train.status.equals("End of track"),"centreline audit reaches the genuine endpoint without a routing shortcut");
            }
        }
        System.out.println("Model centreline: all 28 compass pairs in both directions, "+samples+" moving poses, worst 32-section mesh error "+worst+" metres.");
    }
    private static void mixedCurveSpacing() {
        // Cardinal, diagonal and 45/90/135 degree direction changes, with no repeated tile.
        int[][] tiles={{10,10},{11,10},{12,10},{13,11},{14,12},{14,13},{15,13},{16,12},{17,13},{16,13},{16,14},{17,14},{18,14},{19,14},{20,14}};
        RailNetwork n=track(tiles);RailNetwork.Train t=n.createTrain("mixed",2,Arrays.asList(1201L,1202L,1203L),"m2","m3");
        List<double[]> reference=referenceLine(tiles);n.start(t.name);double previous=project(reference,t.poses.get(0))[0];
        boolean sawDiagonal=false,sawBend=false;
        for(int tick=0;tick<700;tick++) {
            n.tick(.05);double prior=Double.NaN;
            for(RailNetwork.Pose pose:t.poses) {
                double[] projected=project(reference,pose);
                check(projected[1]<.0005,"each car remains on the generated track model centerline");
                if(!Double.isNaN(prior))check(Math.abs(prior-projected[0]-4)<.002,"fixed four metre arc spacing across mixed bends");
                prior=projected[0];
                sawDiagonal|=Math.abs(pose.rotation-135)<.1;
                double heading=pose.rotation/45.0;sawBend|=Math.abs(heading-Math.rint(heading))>.05;
            }
            double distance=project(reference,t.poses.get(0))[0];
            check(distance>=previous-.001&&distance-previous<=t.maxSpeed*.05+.001,"curve movement has no snapping or excess speed");
            previous=distance;
        }
        check(sawDiagonal&&sawBend,"mixed route exercised diagonal straights and smooth turning headings");
        check(t.status.equals("End of track"),"mixed consist reaches endpoint");
        n.reverse(t.name);n.start(t.name);
        for(int tick=0;tick<700;tick++) {
            n.tick(.05);double prior=Double.NaN;
            for(RailNetwork.Pose pose:t.poses) {
                double[] projected=project(reference,pose);
                check(projected[1]<.0005,"reverse consist remains on model centerline");
                if(!Double.isNaN(prior))check(Math.abs(prior-projected[0]-4)<.002,"reverse keeps four metre arc spacing");
                prior=projected[0];
            }
        }
        check(t.status.equals("End of track"),"mixed consist reverses through every curve to its starting terminal");
        eq(t.poses.get(2).x,42,"rear reaches first mixed-route node after reversal");
    }
    private static void diagonalCrossings() {
        RailNetwork n=new RailNetwork();
        for(int i=0;i<7;i++) {
            n.addNode("a"+i,8+i,8+i,0);n.addNode("b"+i,8+i,13-i,0);
            if(i>0){n.connect("a"+(i-1),"a"+i);n.connect("b"+(i-1),"b"+i);}
        }
        RailNetwork.Train a=n.createTrain("crossA",1,Arrays.asList(1301L),"a1","a2");
        RailNetwork.Train b=n.createTrain("crossB",2,Arrays.asList(1302L),"b1","b2");
        n.start(a.name);n.start(b.name);boolean waited=false;
        for(int i=0;i<500;i++) {
            n.tick(.1);RailNetwork.Pose pa=a.poses.get(0),pb=b.poses.get(0);
            check(Math.hypot(pa.x-pb.x,pa.y-pb.y)>.9,"unconnected diagonal X crossings cannot collide");
            waited|=a.status.equals("Track occupied")||b.status.equals("Track occupied")||a.status.startsWith("Waiting for space behind train ")||b.status.startsWith("Waiting for space behind train ");
        }
        check(waited,"the diagonal crossing is reserved even without a shared graph node");
        check(a.status.equals("End of track")||b.status.equals("End of track"),"one train clears an unconnected crossing");
        n=new RailNetwork();n.addNode("a",10,10,0);n.addNode("b",11,11,0);n.connect("a","b");
        n.addNode("c",10,11,-1);n.addNode("d",11,10,-1);n.connect("c","d");
        n.createTrain("surface",1,Arrays.asList(1311L),"a","b");
        n.createTrain("underground",1,Arrays.asList(1312L),"c","d");
        check(n.trains.size()==2,"diagonal crossings on different layers have separate reservations");
    }
    private static void legacyMigration() throws Exception {
        Path dir=Files.createTempDirectory("wurmrail-legacy-");Path file=dir.resolve("state.properties");
        try {
            RailNetwork n=track(new int[][]{{10,10},{11,10},{12,10},{12,11},{12,12}});
            n.createTrain("legacy",1,Arrays.asList(1401L,1402L),"m2","m3");n.save(file);
            Properties p=new Properties();try(java.io.InputStream in=Files.newInputStream(file)){p.load(in);}
            p.setProperty("format","1");p.setProperty("train.0.path","m0,m1,m2,m3,m4");p.setProperty("train.0.distance","8.0");
            for(int index=0;index<Integer.parseInt(p.getProperty("nodes"));index++)p.setProperty("node."+index+".selected","");
            java.lang.reflect.Method checksum=RailNetwork.class.getDeclaredMethod("checksum",Properties.class);checksum.setAccessible(true);
            p.setProperty("checksum",(String)checksum.invoke(null,p));
            try(java.io.OutputStream out=Files.newOutputStream(file)){p.store(out,"format 1 fixture");}
            RailNetwork migrated=RailNetwork.load(file);RailNetwork.Train t=migrated.trains.get("legacy");
            eq(t.poses.get(0).x,49.5,"old corner position migrates to the new curve midpoint X");
            eq(t.poses.get(0).y,42.5,"old corner position migrates to the new curve midpoint Y");
            check(!t.powered&&t.carIds.size()==2,"migration preserves consist and stopped restart");
            migrated.start(t.name);migrated.tick(20);check(t.status.equals("End of track"),"legacy train can continue using new distance geometry");
            migrated.save(file);p.clear();try(java.io.InputStream in=Files.newInputStream(file)){p.load(in);}
            check(p.getProperty("format").equals("4"),"a migrated network saves physical alignment and arc-distance signalling format");
            RailNetwork reloaded=RailNetwork.load(file);eq(reloaded.trains.get("legacy").poses.get(1).y,t.poses.get(1).y,"migrated consist round trips in format 3");
        } finally {try(java.util.stream.Stream<Path> files=Files.walk(dir)){Iterator<Path> all=files.sorted(Comparator.reverseOrder()).iterator();while(all.hasNext())Files.delete(all.next());}}
    }
    private static void curvedCheckpoint() throws Exception {
        RailNetwork n=track(new int[][]{{10,10},{11,10},{12,11},{13,12},{13,13},{14,13},{15,12},{16,12}});
        RailNetwork.Train t=n.createTrain("checkpointCurve",1,Arrays.asList(1501L,1502L),"m1","m2");
        n.start(t.name);n.tick(4.7);RailNetwork.Checkpoint checkpoint=n.checkpoint(t.name);
        n.tick(1.9);List<RailNetwork.Pose> expected=t.poses;n.restore(checkpoint);n.tick(1.9);
        for(int i=0;i<t.poses.size();i++) {
            eq(t.poses.get(i).x,expected.get(i).x,"curve checkpoint preserves resumed X");
            eq(t.poses.get(i).y,expected.get(i).y,"curve checkpoint preserves resumed Y");
            eq(t.poses.get(i).rotation,expected.get(i).rotation,"curve checkpoint preserves resumed tangent");
        }
        Path file=Files.createTempFile("wurmrail-curve-",".properties");Files.delete(file);
        try {n.save(file);RailNetwork restored=RailNetwork.load(file);RailNetwork.Train r=restored.trains.get(t.name);
            for(int i=0;i<t.poses.size();i++){eq(r.poses.get(i).x,t.poses.get(i).x,"curved save X");eq(r.poses.get(i).y,t.poses.get(i).y,"curved save Y");eq(r.poses.get(i).rotation,t.poses.get(i).rotation,"curved save heading");}
        }finally{Files.deleteIfExists(file);}
    }
    private static void curvedAutomation() {
        int[][] tiles={{10,10},{11,10},{12,11},{13,12},{13,13},{14,13},{15,12},{16,12},{17,12},{18,12}};
        RailNetwork n=track(tiles);RailNetwork.Train t=n.createTrain("curveShuttle",1,Arrays.asList(1601L,1602L),"m1","m2");
        n.setRoute(t.name,Arrays.asList("m9","m0"),.2);n.start(t.name);
        List<double[]> reference=referenceLine(tiles);int visits=0;String previous="";
        for(int i=0;i<800;i++) {
            n.tick(.1);check(t.powered,"automatic mixed-curve shuttle stays powered");
            if(t.status.equals("Station dwell")&&!previous.equals(t.status))visits++;
            previous=t.status;
            for(RailNetwork.Pose pose:t.poses)check(project(reference,pose)[1]<.0005,"automatic consist follows mixed curves in both directions");
        }
        check(visits>=3,"automatic mixed-curve shuttle repeatedly reaches both terminals");
    }
    private static void initialBendCoupling() {
        int[][] tiles={{10,10},{11,10},{12,10},{12,11},{12,12},{13,13},{14,13}};
        RailNetwork n=track(tiles);RailNetwork.Train t=n.createTrain("claimBend",1,Arrays.asList(1701L),"m2","m3");
        RailNetwork.Pose claimed=t.poses.get(0);
        eq(claimed.x,49.5,"a single-car claim starts on the actual bend X");
        eq(claimed.y,42.5,"a single-car claim starts on the actual bend Y");
        eq(claimed.rotation,135,"a single-car claim starts on the bend tangent");
        RailNetwork.Checkpoint beforeCoupling=n.checkpoint(t.name);
        n.movementGuard=poses->false;
        final RailNetwork initialNetwork=n;
        fails(()->initialNetwork.addCar("claimBend",1702L),"unsafe initial bend coupling is rejected");
        eq(t.poses.get(0).x,claimed.x,"failed bend coupling restores engine X");
        eq(t.poses.get(0).y,claimed.y,"failed bend coupling restores engine Y");
        check(t.carIds.equals(Arrays.asList(1701L)),"failed bend coupling restores the original car identity");
        n.restore(beforeCoupling);
        n.disconnect("m0","m1");check(!n.nodes.get("m0").links.contains("m1"),"failed bend coupling releases tentative rear history");
        n.connect("m0","m1");n.movementGuard=poses->true;n.addCar(t.name,1702L);
        eq(t.poses.get(0).x,claimed.x,"successful coupling leaves the bend engine X fixed");
        eq(t.poses.get(0).y,claimed.y,"successful coupling leaves the bend engine Y fixed");
        fails(()->initialNetwork.restore(beforeCoupling),"a pre-coupling bend checkpoint rejects a changed consist");
        List<double[]> reference=referenceLine(tiles);n.start(t.name);n.tick(.1);
        check(Math.hypot(t.poses.get(0).x-claimed.x,t.poses.get(0).y-claimed.y)<.0081,"starting a newly coupled bend train cannot snap");
        n.stop(t.name);RailNetwork.Pose stopped=t.poses.get(0);n.reverse(t.name);n.start(t.name);n.tick(.1);
        check(Math.hypot(t.poses.get(0).x-stopped.x,t.poses.get(0).y-stopped.y)<.0081,"reversing a newly coupled bend train cannot snap");
        double engine=project(reference,t.poses.get(0))[0],cart=project(reference,t.poses.get(1))[0];
        check(Math.abs(engine-cart-4)<.002,"newly coupled bend train preserves four metre arc spacing after reversal");
        check(t.carIds.equals(Arrays.asList(1701L,1702L)),"initial bend reversal keeps locomotive-first identities");

        // A new connection can turn a formerly terminal claim into a bend before coupling.
        n=new RailNetwork();n.addNode("origin",12,10,0);n.addNode("ahead",12,11,0);n.connect("origin","ahead");
        t=n.createTrain("newBend",1,Arrays.asList(1711L),"origin","ahead");
        n.addNode("behind",11,10,0);n.addNode("tail",10,10,0);n.connect("tail","behind");n.connect("behind","origin");
        n.addCar(t.name,1712L);eq(t.poses.get(0).x,49.5,"coupling resolves a newly built initial bend X");
        eq(t.poses.get(0).y,42.5,"coupling resolves a newly built initial bend Y");
        stopped=t.poses.get(0);n.start(t.name);n.tick(.1);
        check(Math.hypot(t.poses.get(0).x-stopped.x,t.poses.get(0).y-stopped.y)<.0081,"starting after a new bend coupling cannot snap");
    }
    private static RailNetwork track(int[][] tiles) {
        RailNetwork n=new RailNetwork();for(int i=0;i<tiles.length;i++){n.addNode("m"+i,tiles[i][0],tiles[i][1],0);if(i>0)n.connect("m"+(i-1),"m"+i);}return n;
    }
    private static List<double[]> referenceLine(int[][] tiles) {
        List<double[]> points=new ArrayList<double[]>();double distance=0,px=tiles[0][0]*4+2,py=tiles[0][1]*4+2;
        points.add(new double[]{px,py,0});
        for(int i=0;i<tiles.length;i++) {
            double x=tiles[i][0]*4+2,y=tiles[i][1]*4+2;
            double ax=i==0?x:(tiles[i-1][0]*4+2+x)*.5,ay=i==0?y:(tiles[i-1][1]*4+2+y)*.5;
            double bx=i==tiles.length-1?x:(tiles[i+1][0]*4+2+x)*.5,by=i==tiles.length-1?y:(tiles[i+1][1]*4+2+y)*.5;
            double cx=i==0||i==tiles.length-1?(ax+bx)*.5:x,cy=i==0||i==tiles.length-1?(ay+by)*.5:y;
            for(int k=1;k<=512;k++){double t=k/512.0,u=1-t,qx=u*u*ax+2*u*t*cx+t*t*bx,qy=u*u*ay+2*u*t*cy+t*t*by;
                distance+=Math.hypot(qx-px,qy-py);points.add(new double[]{qx,qy,distance});px=qx;py=qy;}
        }
        return points;
    }
    private static double[] project(List<double[]> line,RailNetwork.Pose pose) {
        double best=Double.POSITIVE_INFINITY,distance=0;
        for(int i=1;i<line.size();i++) {
            double[] a=line.get(i-1),b=line.get(i);double dx=b[0]-a[0],dy=b[1]-a[1],length2=dx*dx+dy*dy;
            if(length2==0)continue;double t=Math.max(0,Math.min(1,((pose.x-a[0])*dx+(pose.y-a[1])*dy)/length2));
            double error=Math.hypot(pose.x-a[0]-t*dx,pose.y-a[1]-t*dy);
            if(error<best){best=error;distance=a[2]+t*(b[2]-a[2]);}
        }
        return new double[]{distance,best};
    }
    private static RailNetwork line(int count) {
        RailNetwork n=new RailNetwork();for(int i=0;i<count;i++)n.addNode("p"+i,10+i,10,0);
        for(int i=1;i<count;i++)n.connect("p"+(i-1),"p"+i);return n;
    }
    private static final double EPS=.001;
    private static void eq(double actual,double expected,String label){check(Math.abs(actual-expected)<EPS,label+": "+actual+" != "+expected);}
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static void fails(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){assertions++;}}
}
