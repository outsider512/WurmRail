import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;
import org.wurmrail.RailwayBml;
import org.wurmrail.RailwayMenuActions;
import org.wurmrail.RailwaySignalActions;
import org.wurmrail.RailwaySignalUi;
import org.wurmrail.WurmRailWorld;

/** Real native context-menu interfaces after loader startup; no sockets or player sessions. */
public final class RailwayUiSmoke {
    private RailwayUiSmoke() { }

    private static final class Calls implements RailwayMenuActions.Api {
        boolean builder;
        int builds, vehicles, cycles, x, y, layer;
        long target;
        String verb;
        Creature actor;
        @Override public boolean canBuild(Creature p) { return builder; }
        @Override public void openBuilder(Creature p, int x, int y, int layer) {
            builds++; this.actor=p; this.x=x; this.y=y; this.layer=layer;
        }
        @Override public void openVehicle(Creature p, Item target, String verb) {
            vehicles++; this.actor=p; this.target=target.getWurmId(); this.verb=verb;
        }
        @Override public void cycleAlignment(Creature p, Item target) {
            cycles++; this.actor=p; this.target=target.getWurmId();
        }
    }

    @SuppressWarnings("unchecked")
    public static void run() throws Exception {
        Creature actor=UsabilitySmoke.actor(7741L,202,202);
        Item engine=WurmRailWorld.spawnLocomotive(null,202,202,0);
        Item closestCart=WurmRailWorld.spawnCart(null,203,202,0);
        Item clickedCart=WurmRailWorld.spawnCart(null,206,202,0);
        Item marker=WurmRailWorld.spawnTrackMarker(null,202,202,0);
        Item active=com.wurmonline.server.items.ItemFactory.createItem(
                com.wurmonline.server.items.ItemList.plank,50.0f,"Railway UI fixture");
        int firstId=ModActions.getNextActionId();
        Field providersField=ModActions.class.getDeclaredField("behaviourProviders");
        providersField.setAccessible(true);
        List<BehaviourProvider> providers=(List<BehaviourProvider>)providersField.get(null);
        int firstProvider=providers.size();
        Calls calls=new Calls();
        RailwayMenuActions.register(calls);
        check(ModActions.getNextActionId()==firstId+5 && providers.size()==firstProvider+5,
                "native menu registration keeps five rail controls and delegates boarding/storage to native actions");
        BehaviourProvider terrain=providers.get(firstProvider);
        BehaviourProvider track=providers.get(firstProvider+1);
        short groundId=(short)firstId;
        ActionPerformer ground=performer(groundId);
        int surface=com.wurmonline.server.Server.surfaceMesh.getTile(50,50);
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null,
                "builder ACL must hide terrain entry");
        check(track.getBehavioursFor(actor,marker)==null,"builder ACL must hide track edits");
        ground.action(null,actor,50,50,true,surface,groundId,1f);
        check(calls.builds==0,"forged terrain action must recheck builder ACL");
        calls.builder=true;
        check(has(terrain.getBehavioursFor(actor,50,50,true,surface),groundId),
                "surface tile menu without active source");
        check(has(terrain.getBehavioursFor(actor,active,50,50,true,surface),groundId),
                "surface tile menu with active source");
        ground.action(null,actor,50,50,true,surface,groundId,1f);
        check(calls.builds==1 && calls.x==50 && calls.y==50 && calls.layer==0 && calls.actor==actor,
                "ground action must preserve clicked coordinates and actor");
        ground.action(null,actor,active,50,50,true,0,surface,groundId,1f);
        check(calls.builds==2,"active ground action uses native heightOffset,tile argument order");
        ground.action(null,actor,active,50,50,true,30,surface,groundId,1f);
        ground.action(null,actor,50,50,true,surface,(short)(groundId+1),1f);
        check(calls.builds==2,"elevated and mismatched action IDs cannot invoke builder");
        check(terrain.getBehavioursFor(actor,50,50,true,true,surface,0)==null,
                "tile corner menus must remain separate from ground railway construction");
        check(has(track.getBehavioursFor(actor,marker),(short)(firstId+1))
                        && has(track.getBehavioursFor(actor,active,marker),(short)(firstId+1)),
                "fixed track menu must work with or without active source");
        performer((short)(firstId+1)).action(null,actor,active,marker,(short)(firstId+1),1f);
        check(calls.builds==3 && calls.x==marker.getTileX() && calls.y==marker.getTileY(),
                "track menu must anchor editing at the clicked track");

        BehaviourProvider cycle=providers.get(firstProvider+4);
        short cycleId=(short)(firstId+4);
        ActionPerformer cycleAction=performer(cycleId);
        check(cycle.getBehavioursFor(actor,marker)==null&&cycle.getBehavioursFor(actor,engine)==null,
                "ordinary track and vehicles have no physical junction cycle action");
        marker.setAuxData((byte)21); // N, E, S form a three-ended junction.
        check(has(cycle.getBehavioursFor(actor,marker),cycleId)
                        &&has(cycle.getBehavioursFor(actor,active,marker),cycleId),
                "three-ended junction exposes a direct cycle with and without active item");
        cycleAction.action(null,actor,marker,cycleId,1f);
        cycleAction.action(null,actor,active,marker,cycleId,1f);
        check(calls.cycles==2&&calls.target==marker.getWurmId()&&calls.actor==actor,
                "direct cycle preserves the clicked junction identity without opening a window");
        calls.builder=false;
        check(cycle.getBehavioursFor(actor,marker)==null,"builder ACL hides direct alignment control");
        cycleAction.action(null,actor,marker,cycleId,1f);
        calls.builder=true;
        cycleAction.action(null,actor,marker,(short)(cycleId-1),1f);
        check(calls.cycles==2,"forged ACL or mismatched action cannot change junction alignment");
        marker.setAuxData((byte)17);
        cycleAction.action(null,actor,marker,cycleId,1f);
        check(calls.cycles==2&&cycle.getBehavioursFor(actor,marker)==null,
                "a two-ended straight cannot be treated as a junction");
        marker.setAuxData((byte)255);
        check(has(cycle.getBehavioursFor(actor,marker),cycleId),"unsigned eight-direction junction mask remains selectable");
        marker.setAuxData((byte)21);
        org.wurmrail.core.RailNetwork graph=new org.wurmrail.core.RailNetwork();
        org.wurmrail.core.RailNetwork.Node node=graph.addNode("uiJunction",50,50,0);
        node.markerId=marker.getWurmId();
        graph.addNode("uiNorth",50,49,0); graph.addNode("uiEast",51,50,0); graph.addNode("uiSouth",50,51,0);
        graph.connect(node.name,"uiNorth"); graph.connect(node.name,"uiEast"); graph.connect(node.name,"uiSouth");
        Item indicator=WurmRailWorld.spawnJunctionIndicator(node);
        indicator.setAuxData((byte)5); // The physical N-to-E pair has only two visible ends.
        check(has(cycle.getBehavioursFor(actor,indicator),cycleId)
                        &&has(cycle.getBehavioursFor(actor,active,indicator),cycleId),
                "the separate physical alignment indicator exposes the same direct cycle");
        cycleAction.action(null,actor,active,indicator,cycleId,1f);
        check(calls.cycles==3&&calls.target==indicator.getWurmId(),"indicator cycle keeps its own clicked item identity");
        check(has(track.getBehavioursFor(actor,indicator),(short)(firstId+1)),"physical indicator also opens its track builder");
        performer((short)(firstId+1)).action(null,actor,indicator,(short)(firstId+1),1f);
        check(calls.builds==4&&calls.x==50&&calls.y==50,"indicator builder anchors at its junction tile");

        // Cave requests have an additional direction: zero is floor, one is ceiling.
        actor.getStatus().getPosition().setLayer(-1);
        actor.getStatus().getPosition().setPosZ(2,false);
        int cave=com.wurmonline.server.Server.caveMesh.getTile(50,50);
        check(has(terrain.getBehavioursFor(actor,50,50,false,cave,0),groundId)
                        && has(terrain.getBehavioursFor(actor,active,50,50,false,cave,0),groundId),
                "both native cave floor request overloads must expose the builder");
        check(terrain.getBehavioursFor(actor,50,50,false,cave,1)==null
                        && terrain.getBehavioursFor(actor,active,50,50,false,cave,2)==null,
                "cave ceilings and walls must not expose floor construction");
        ground.action(null,actor,50,50,false,cave,0,groundId,1f);
        ground.action(null,actor,active,50,50,false,0,cave,0,groundId,1f);
        check(calls.builds==6 && calls.layer==-1,"both cave floor action overloads must preserve cave layer");
        ground.action(null,actor,50,50,false,cave,1,groundId,1f);
        ground.action(null,actor,active,50,50,false,0,cave,2,groundId,1f);
        check(calls.builds==6,"forged cave ceiling/wall actions cannot open the builder");
        cycleAction.action(null,actor,marker,cycleId,1f);
        check(calls.cycles==3&&cycle.getBehavioursFor(actor,marker)==null,
                "direct alignment cannot cross surface and cave layers");
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null,
                "surface construction must be unavailable from cave layer");

        actor.getStatus().getPosition().setLayer(0);
        actor.getStatus().getPosition().setPosZ(5,false);
        for(int offset=2;offset<4;offset++) {
            short id=(short)(firstId+offset);
            BehaviourProvider provider=providers.get(firstProvider+offset);
            ActionPerformer performer=performer(id);
            String expected=new String[]{"panel","couple"}[offset-2];
            check(has(provider.getBehavioursFor(actor,engine),id)
                            && has(provider.getBehavioursFor(actor,active,clickedCart),id),
                    "vehicle menu with and without active source: "+expected);
            check(provider.getBehavioursFor(actor,active)==null
                            && provider.getBehavioursFor(actor,marker)==null,
                    "vanilla items and tracks must not expose vehicle actions: "+expected);
            int before=calls.vehicles;
            performer.action(null,actor,active,clickedCart,id,1f);
            check(calls.vehicles==before+1 && calls.target==clickedCart.getWurmId()
                            && calls.target!=closestCart.getWurmId() && expected.equals(calls.verb),
                    "active-item action must retain the clicked wagon instead of nearest wagon: "+expected);
            performer.action(null,actor,engine,id,1f);
            check(calls.vehicles==before+2 && calls.target==engine.getWurmId(),
                    "no-source action must retain the clicked engine: "+expected);
            performer.action(null,actor,active,id,1f);
            check(calls.vehicles==before+2,"forged vanilla target must not invoke callback");
        }

        actor.getStatus().getPosition().setPosX(230);
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null
                        && providers.get(firstProvider+2).getBehavioursFor(actor,clickedCart)==null
                        && cycle.getBehavioursFor(actor,marker)==null,
                "distant terrain and vehicles must not expose railway actions");
        int beforeBuilds=calls.builds, beforeVehicles=calls.vehicles;
        ground.action(null,actor,50,50,true,surface,groundId,1f);
        performer((short)(firstId+2)).action(null,actor,engine,(short)(firstId+2),1f);
        cycleAction.action(null,actor,marker,cycleId,1f);
        check(calls.builds==beforeBuilds && calls.vehicles==beforeVehicles,
                "forged out-of-range actions must not invoke callbacks");
        check(calls.cycles==3,"out-of-range direct alignment is rejected before the callback");
        actor.getStatus().getPosition().setPosX(202);
        actor.getStatus().getPosition().setPosZ(9,false);
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null
                        && providers.get(firstProvider+2).getBehavioursFor(actor,engine)==null
                        && cycle.getBehavioursFor(actor,marker)==null,
                "different floor must not expose ground actions");
        actor.getStatus().getPosition().setPosZ(5,false);
        actor.getStatus().getPosition().setBridgeId(987654321L);
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null
                        && providers.get(firstProvider+2).getBehavioursFor(actor,engine)==null
                        && cycle.getBehavioursFor(actor,marker)==null,
                "different bridge must not expose ground actions");
        check(engine.getPosX()==202 && clickedCart.getPosX()==206,
                "menu callbacks must not move world items");
        String hostile="Depot,one;'};button{id='start'};{\\\"\n";
        String safe=RailwayBml.text(hostile), option=RailwayBml.option(hostile);
        check(safe.indexOf('{')<0 && safe.indexOf('}')<0 && safe.indexOf(';')<0
                        && safe.indexOf('"')<0 && safe.indexOf('\'')<0 && safe.indexOf('\\')<0
                        && safe.indexOf('\n')<0 && option.indexOf(',')<0,
                "hostile display names cannot change BML structure or dropdown indexes");
        signalActions(marker,indicator,engine,active,providers);
        signalQuestionReplay();
        System.out.println("PASS native railway menus and direct physical junction cycling: clicked tracks/indicators, surface/cave active and inactive source overloads, ACL, range, level, bridge and forged actions");
    }

    private static void signalActions(Item marker,Item indicator,Item engine,Item active,List<BehaviourProvider> providers) throws Exception {
        Creature actor=TrackMaterialsSmoke.actor(720004544L,true,0);
        actor.getStatus().getPosition().setPosX(202); actor.getStatus().getPosition().setPosY(202);
        Item signal=WurmRailWorld.spawnSignal(marker.getWurmId(),202,202,0,0);
        final int[] calls={0}; final long[] clicked={-10L};
        int first=ModActions.getNextActionId(),providerIndex=providers.size();
        RailwaySignalActions.register(new RailwaySignalActions.Api() {
            @Override public void open(Creature p,long clickedItemId) {
                check(p==actor,"signal action retains the real actor"); calls[0]++; clicked[0]=clickedItemId;
            }
        });
        check(ModActions.getNextActionId()==first+1 && providers.size()==providerIndex+1,
                "signals register exactly one additional inspect/configure action");
        short id=(short)first; BehaviourProvider provider=providers.get(providerIndex); ActionPerformer action=performer(id);
        for(Item target:new Item[]{marker,indicator,signal}) {
            check(has(provider.getBehavioursFor(actor,target),id) && has(provider.getBehavioursFor(actor,active,target),id),
                    "track, junction indicator and signal expose both native item action overloads");
            action.action(null,actor,target,id,1f); action.action(null,actor,active,target,id,1f);
            check(clicked[0]==target.getWurmId(),"signal menu queues the exact clicked item rather than the nearest post");
        }
        check(calls[0]==6,"one signal action callback is issued for each valid direct interaction");
        action.action(null,actor,engine,id,1f); action.action(null,actor,active,id,1f);
        action.action(null,actor,signal,(short)(id+1),1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,engine)==null && provider.getBehavioursFor(actor,active)==null,
                "wrong templates and forged action IDs cannot open a signal window");
        actor.getStatus().getPosition().setPosX(211);
        check(provider.getBehavioursFor(actor,signal)==null,"signal inspection uses eight metre native item reach");
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6,"forged distant signal action rechecks reach");
        actor.getStatus().getPosition().setPosX(202);
        actor.getStatus().getPosition().setLayer(-1);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"signal inspection cannot cross surface/cave layers");
        actor.getStatus().getPosition().setLayer(0);
        actor.getStatus().getPosition().setPosZ(9,false);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"elevated performers cannot inspect a ground signal");
        actor.getStatus().getPosition().setPosZ(5,false);
        actor.getStatus().getPosition().setBridgeId(987654321L);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"bridge performers cannot target a ground signal");
        actor.getStatus().getPosition().setBridgeId(-10L);
        // Native DbItem.setParentId's boolean is the item's surface layer, not a save flag.
        signal.setParentId(actor.getInventory().getWurmId(),true);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"contained signal items cannot invoke configuration");
        signal.setParentId(-10L,true);
        check(signal.isOnSurface() && has(provider.getBehavioursFor(actor,signal),id),
                "restoring the contained fixture preserves its original surface layer and readable ground context");
        signal.setBanked(true);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"banked signal items cannot invoke configuration");
        signal.setBanked(false); signal.setMailed(true);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"mailed signal items cannot invoke configuration");
        signal.setMailed(false);
        signal.setOwnerId(actor.getWurmId());
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"owned signal items cannot invoke ground configuration");
        signal.setOwnerId(-10L);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==7,"ordinary players may inspect signal diagnostics without builder permissions");
        com.wurmonline.server.Items.destroyItem(signal.getWurmId());
        System.out.println("PASS native signal inspect action: exact clicked track/indicator/post, active/inactive source overloads, visitor access, forged IDs, range, layer, floor, bridge and contained/banked/mailed/owned guards");
    }

    private static void signalQuestionReplay() throws Exception {
        final Creature actor=TrackMaterialsSmoke.actor(720004288L,true,0);
        final RailwaySignalUi.View view=new RailwaySignalUi.View("capturedSignalTrack",987L,4L,"Replay fixture",
                java.util.Arrays.asList(0,4),java.util.Collections.<RailwaySignalUi.Signal>emptyList(),true,1,false,0);
        final int[] submitted={0};
        RailwaySignalUi.Api api=new RailwaySignalUi.Api() {
            @Override public RailwaySignalUi.View snapshot(Creature p,long id) { return view; }
            @Override public void submit(Creature p,long id,RailwaySignalUi.Request request) {
                check(p==actor && id==987L && request.expectedMarkerId==987L && request.revision==4L
                                && request.expectedNodeKey.equals("capturedSignalTrack") && request.approach==4,
                        "native signal form retains captured clicked identity, definition revision and absolute approach");
                submitted[0]++;
            }
        };
        RailwaySignalUi.show(actor,987L,api);
        com.wurmonline.server.questions.Question question=latestQuestion(actor);
        java.util.Properties place=new java.util.Properties();
        place.setProperty("place","true"); place.setProperty("approach_choice","1");
        place.setProperty("approach","0"); place.setProperty("expectedMarkerId","999");
        question.answer(place); question.answer(place);
        check(submitted[0]==1,"native signal ModQuestionImpl repeated answer queues only one construction request");
        System.out.println("PASS native signal ModQuestionImpl captured approach and single-use answer replay");
    }

    @SuppressWarnings("unchecked")
    private static com.wurmonline.server.questions.Question latestQuestion(Creature actor) throws Exception {
        Field registry=com.wurmonline.server.questions.Questions.class.getDeclaredField("questions"); registry.setAccessible(true);
        com.wurmonline.server.questions.Question latest=null;
        for(com.wurmonline.server.questions.Question question:((Map<Integer,com.wurmonline.server.questions.Question>)registry.get(null)).values())
            if(question.getResponder()==actor && (latest==null || question.getId()>latest.getId())) latest=question;
        check(latest!=null,"native signal question is registered for its real responder"); return latest;
    }

    @SuppressWarnings("unchecked")
    private static ActionPerformer performer(short id) throws Exception {
        Field registry=ModActions.class.getDeclaredField("actionPerformers");
        registry.setAccessible(true);
        Object chain=((Map<Short,?>)registry.get(null)).get(id);
        Field entries=chain.getClass().getDeclaredField("actionPerformers");
        entries.setAccessible(true);
        List<ActionPerformer> performers=(List<ActionPerformer>)entries.get(chain);
        check(performers.size()==1,"fixture action ID must resolve exactly one performer");
        return performers.get(0);
    }

    private static boolean has(List<ActionEntry> entries, short id) {
        if(entries==null) return false;
        for(ActionEntry entry:entries) if(entry.getNumber()==id) return true;
        return false;
    }

    private static void check(boolean condition,String reason) {
        if(!condition) throw new AssertionError(reason);
    }
}
