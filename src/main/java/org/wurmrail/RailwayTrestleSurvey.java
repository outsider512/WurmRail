package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.WurmId;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.questions.Question;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.TrestleAlignment;
import org.wurmrail.core.TrestleAlignment.Point;
import org.wurmrail.core.TrestleAlignment.RadiusFailure;
import org.wurmrail.core.TrestleHeightFit;
import org.wurmrail.shared.TrestleDeckSurface;

/** Session-local survey drafts. All changes and terrain reads run on the server poll. */
final class RailwayTrestleSurvey {
    private static final long PREVIEW_NANOS = 60_000_000_000L;
    private static final int MAX_PREVIEW_BENTS = 12;
    private static final int POLES_PER_PAGE = 5;
    private static final double BANK_JOIN_LENGTH = TrestleAlignment.TILE / 2;
    private static final double[] CURVED_OFFSETS = {
            -TrestleDeckSurface.HALF_WIDTH, -TrestleDeckSurface.HALF_WIDTH*.5,
            TrestleDeckSurface.HALF_WIDTH*.5, TrestleDeckSurface.HALF_WIDTH
    };
    interface Api {
        Node railAt(int x, int y);
        boolean hasDeck(int x, int y);
        Node rail(String name);
        boolean trackPresent(Node rail);
        String trestleAt(int x, int y, long marker);
        void validateCrossings(Plan plan) throws Exception;
        void remove(Creature actor, String span) throws Exception;
        void build(Creature actor, Plan plan) throws Exception;
        void submit(Creature actor, Response response);
    }

    interface Response { void apply() throws Exception; }

    private final ConcurrentHashMap<Long, Draft> drafts = new ConcurrentHashMap<>();
    private final Map<Long, PreviewMarkers> previews = new HashMap<>();
    private final Api api;
    private final double maxLength, maxGradient, supportSpacing, curveRadius;

    RailwayTrestleSurvey(Api api, int maxTiles, double maxGradient, int spacingTiles, int radiusTiles) {
        this.api = api;
        this.maxLength = maxTiles * TrestleAlignment.TILE;
        this.maxGradient = maxGradient;
        this.supportSpacing = spacingTiles * TrestleAlignment.TILE;
        this.curveRadius = radiusTiles * TrestleAlignment.TILE;
    }

    boolean hasSurvey(Creature actor) { return drafts.containsKey(actor.getWurmId()); }

    void handle(Creature actor, RailwayTrestleActions.Operation operation, long tool, int x, int y,
                long target) throws Exception {
        boolean reachable = operation == RailwayTrestleActions.Operation.REMOVE
                ? RailwayTrestleActions.reachableForRemoval(actor,x,y) : RailwayTrestleActions.reachable(actor,x,y);
        if (!RailwayTrestleActions.ownsTool(actor, Items.getItem(tool), ItemList.dioptra)
                || !reachable)
            throw new IllegalArgumentException("Carry the dioptra and stand near the survey target.");
        if (operation == RailwayTrestleActions.Operation.OPEN) {
            show(actor, requireDraft(actor), tool, null);
            return;
        }
        Node rail = api.railAt(x, y);
        Item item = target == -10L ? null : Items.getItem(target);
        if (item != null) {
            if (item.getTileX() != x || item.getTileY() != y || !item.isOnSurface()
                    || item.getFloorLevel() != 0 || item.getBridgeId() != -10L
                    || item.getParentId() != -10L || item.getOwnerId() != -10L
                    || item.isBanked() || item.isTraded() || item.isMailed())
                throw new IllegalArgumentException("The survey target moved. Select it again.");
            if ((operation == RailwayTrestleActions.Operation.MARK || operation == RailwayTrestleActions.Operation.WAYPOINT)
                    && item.getTemplateId() != ItemList.rangePole)
                throw new IllegalArgumentException("Select a range pole placed on the ground.");
            if (operation == RailwayTrestleActions.Operation.START
                    && (rail == null || rail.markerId != target || !api.trackPresent(rail)))
                throw new IllegalArgumentException("Choose a current railway track marker.");
            if (operation == RailwayTrestleActions.Operation.REMOVE && api.trestleAt(x,y,target) == null)
                throw new IllegalArgumentException("Choose the trestle track or its ground tile.");
        } else if (operation == RailwayTrestleActions.Operation.MARK || operation == RailwayTrestleActions.Operation.WAYPOINT) {
            throw new IllegalArgumentException("Choose the placed range pole.");
        }
        if (operation == RailwayTrestleActions.Operation.REMOVE) {
            showRemoval(actor,tool,x,y,target);
            return;
        }
        if (operation != RailwayTrestleActions.Operation.WAYPOINT) checkEndpoint(actor,x,y,rail);
        if (operation == RailwayTrestleActions.Operation.WAYPOINT) {
            Draft draft = requireDraft(actor);
            if (draft.start.isAt(x, y) || draft.end != null && draft.end.isAt(x, y)
                    || draft.waypoints.stream().anyMatch(p -> onTile(p.point, x, y)))
                throw new IllegalArgumentException("That tile is already a survey point.");
            if (draft.waypoints.size() >= maxLength / TrestleAlignment.TILE)
                throw new IllegalArgumentException("The survey already has as many poles as its maximum span length in tiles.");
            List<Waypoint> poles = new ArrayList<>(draft.waypoints);
            poles.add(new Waypoint(new Point(item.getPosX(), item.getPosY(), 0), null));
            Draft next = new Draft(draft.start, draft.end, poles, (poles.size()-1)/POLES_PER_PAGE, draft.zoom, 0);
            drafts.put(actor.getWurmId(), next);
            show(actor, next, tool, null);
            return;
        }
        Point point = new Point(x * 4.0 + 2, y * 4.0 + 2, groundCentreHeight(x,y));
        Site site = new Site(x, y, point, rail == null ? null : rail.name, rail == null ? 0 : rail.markerId,
                rail == null ? 0 : heading(rail));
        Draft next;
        if (operation == RailwayTrestleActions.Operation.START) next = new Draft(site, null);
        else {
            Draft draft = requireDraft(actor);
            if (draft.waypoints.stream().anyMatch(p -> onTile(p.point, x, y)))
                throw new IllegalArgumentException("Choose a destination on a different tile from the intermediate poles.");
            next = new Draft(draft.start, site, draft.waypoints, draft.page, draft.zoom, 0);
        }
        drafts.put(actor.getWurmId(), next);
        if (operation == RailwayTrestleActions.Operation.START)
            actor.getCommunicator().sendNormalServerMessage("Trestle survey started. Place a range pole at the destination and mark it with the dioptra.");
        show(actor, next, tool, null);
    }

    private static boolean onTile(Point point, int x, int y) {
        return Math.floor(point.x / TrestleAlignment.TILE) == x
                && Math.floor(point.y / TrestleAlignment.TILE) == y;
    }

    private int heading(Node rail) {
        if (rail.links.isEmpty()) return 0;
        Node linked = api.rail(rail.links.iterator().next());
        if (linked == null || linked.layer != 0)
            throw new IllegalArgumentException("Choose an approach that leads away from the cave entrance.");
        return 1 + (RailNetwork.direction(linked.tileX - rail.tileX, linked.tileY - rail.tileY) + 4) % 8;
    }

    private void checkEndpoint(Creature actor, int x, int y, Node rail) {
        if (api.hasDeck(x,y))
            throw new IllegalArgumentException("Choose a ground approach outside an existing trestle deck.");
        String issue = WurmRailWorld.validateTile(x, y, 0);
        if (issue != null) throw new IllegalArgumentException("Survey endpoint at " + x + ", " + y + ". " + issue);
        if (RailwayBridgeDeck.partAt(x, y, 0) != null)
            throw new IllegalArgumentException("Choose an approach on the ground beside the native bridge.");
        if (rail != null && (rail.links.size() > 1 || !api.trackPresent(rail)))
            throw new IllegalArgumentException("Choose an intact railway endpoint with at most one existing connection.");
        if (actor.getPower() < 2 && !Methods.isActionAllowed(actor, Actions.BUILD, true, x, y, 0, 0))
            throw new IllegalArgumentException("You need construction permission at " + x + ", " + y + ".");
    }

    private Draft requireDraft(Creature actor) {
        Draft draft = drafts.get(actor.getWurmId());
        if (draft == null) throw new IllegalArgumentException("Start a trestle survey with a dioptra first.");
        return draft;
    }

    private void showRemoval(Creature actor, long tool, int x, int y,long marker) {
        if (actor.getPower() < 2) throw new IllegalArgumentException("Removing a trestle requires GM power 2.");
        String span = api.trestleAt(x,y,marker);
        if (span == null) throw new IllegalArgumentException("Choose a tile belonging to one registered trestle.");
        StringBuilder content = new StringBuilder();
        line(content,"Remove " + span + " at " + x + ", " + y + "?",255,195,140);
        line(content,"This removes the span registration and any remaining pieces that belong to it.",240,240,240);
        button(content,"remove","Remove trestle");
        button(content,"cancel","Cancel");
        ModQuestions.createQuestion(actor,"Remove railway trestle","",tool,new ModQuestion() {
            private final AtomicBoolean answered = new AtomicBoolean();

            @Override public void sendQuestion(Question question) {
                sendBml(question,440,170,true,true,
                        ModQuestions.getBmlHeaderNoQuestion(question)+content+"}};null;null;}");
            }

            @Override public void answer(Question question, Properties answers) {
                if (question.getResponder() != actor || !answered.compareAndSet(false,true)
                        || !"true".equals(answers.getProperty("remove"))) return;
                api.submit(actor,() -> {
                    if (!RailwayTrestleActions.ownsTool(actor,Items.getItem(tool),ItemList.dioptra)
                            || !RailwayTrestleActions.reachableForRemoval(actor,x,y))
                        throw new IllegalArgumentException("Carry the dioptra and stand near the trestle tile.");
                    if (!span.equals(api.trestleAt(x,y,marker)))
                        throw new IllegalArgumentException("The registered trestle changed. Select the tile again.");
                    api.remove(actor,span);
                    actor.getCommunicator().sendNormalServerMessage("Removed trestle " + span + ".");
                });
            }
        }).sendQuestion();
    }

    private void show(Creature actor, Draft draft, long tool, String error) throws Exception {
        clearPreview(actor.getWurmId());
        StringBuilder body = new StringBuilder();
        line(body, "Start " + draft.start.label(), 240, 240, 240);
        TrestleAlignment horizontal = null, profile = null;
        RadiusFailure turn = null;
        Plan plan = null;
        String supportDetails = "";
        if (draft.end != null) {
            line(body, "Destination " + draft.end.label(), 240, 240, 240);
            try {
                horizontal = alignment(draft);
                profile = profile(draft, horizontal);
                plan = plan(actor, draft, profile);
                profile = plan.alignment;
            } catch (RadiusFailure tight) {
                turn = tight;
                line(body, tight.getMessage(), 255, 155, 145);
            } catch (IllegalArgumentException invalid) {
                line(body, invalid.getMessage(), 255, 155, 145);
            }
        }
        if (error != null) line(body, error, 255, 155, 145);
        if (draft.end == null) {
            line(body, "Use Mark Trestle Destination on a range pole to calculate the route and fit the heights.", 240, 240, 240);
        } else if (plan != null) {
            supportDetails = describePlan(body, plan);
            showMarkers(actor, plan.alignment);
        }
        body.append("harray{");
        if (draft.end != null) {
            button(body, "preview", "Update preview");
            if (plan != null && actor.getPower() >= 2)
                button(body, "instant", plan.alignment.curved ? "GM build curved trestle" : "GM build straight trestle");
            button(body, "clear", "Remove destination");
        } else if (!draft.waypoints.isEmpty()) button(body, "preview", "Apply elevations");
        button(body, "cancel", "Cancel survey");
        body.append("};");
        if (draft.end != null) {
            map(body, draft, horizontal, turn);
            line(body, String.format(Locale.ROOT, "Minimum curve radius %.0f tiles (%.0f m)",
                    curveRadius / TrestleAlignment.TILE, curveRadius), 190, 200, 205);
            if (turn != null) showTurnAdvice(body, draft, turn);
        }
        showElevations(body, draft, plan == null ? null : profile);
        body.append(supportDetails);
        String content = body.toString();
        ModQuestions.createQuestion(actor, "Railway trestle survey", "", tool, new ModQuestion() {
            private final AtomicBoolean answered = new AtomicBoolean();

            @Override public void sendQuestion(Question question) {
                sendBml(question, 660, 600, true, true,
                        ModQuestions.getBmlHeaderNoQuestion(question) + content + "}};null;null;}");
            }

            @Override public void answer(Question question, Properties answers) {
                if (question.getResponder() == actor && answered.compareAndSet(false, true))
                    api.submit(actor, () -> answerDraft(actor, draft, tool, answers));
            }
        }).sendQuestion();
    }

    private void answerDraft(Creature actor, Draft draft, long tool, Properties answers) throws Exception {
        if (requireDraft(actor) != draft) throw new IllegalArgumentException("This survey window is stale. Open Review Trestle Survey.");
        if (!RailwayTrestleActions.ownsTool(actor, Items.getItem(tool), ItemList.dioptra))
            throw new IllegalArgumentException("Carry the dioptra to edit this survey.");
        if ("true".equals(answers.getProperty("cancel"))) {
            clearPreview(actor.getWurmId());
            drafts.remove(actor.getWurmId());
            actor.getCommunicator().sendNormalServerMessage("Railway trestle survey cancelled.");
            return;
        }
        try {
            Draft next = draft.edit(answers);
            if ("true".equals(answers.getProperty("undoPole")) && !draft.waypoints.isEmpty()) {
                List<Waypoint> remaining = next.waypoints.subList(0, next.waypoints.size()-1);
                next = new Draft(next.start, next.end, remaining, Math.min(next.page, lastPage(remaining.size())), next.zoom, 0);
            } else if ("true".equals(answers.getProperty("instant")) && next.end != null) {
                if (actor.getPower() < 2) throw new IllegalArgumentException("Instant construction requires GM power 2.");
                if (!RailwayTrestleActions.reachable(actor,draft.start.x,draft.start.y)
                        && !RailwayTrestleActions.reachable(actor,draft.end.x,draft.end.y))
                    throw new IllegalArgumentException("Stand at either approach to build the trestle.");
                drafts.put(actor.getWurmId(), next);
                Plan plan = plan(actor, next, profile(next, alignment(next)));
                api.build(actor, plan);
                clearPreview(actor.getWurmId());
                drafts.remove(actor.getWurmId());
                actor.getCommunicator().sendNormalServerMessage((plan.alignment.curved ? "Curved" : "Straight") + " railway trestle built.");
                return;
            } else if ("true".equals(answers.getProperty("clear"))) {
                next = new Draft(next.start, null, next.waypoints, next.page, next.zoom, 0);
            }
            drafts.put(actor.getWurmId(), next);
            show(actor, next, tool, null);
        } catch (IllegalArgumentException invalid) {
            show(actor, requireDraft(actor), tool, invalid.getMessage());
        }
    }

    private String describePlan(StringBuilder body, Plan plan) throws Exception {
        TrestleAlignment alignment = plan.alignment;
        String grade = alignment.hasWaypointElevations() || alignment.hasDeckGrades() ? ""
                : String.format(Locale.ROOT," | grade %+.2f%%",alignment.gradient*100);
        String tiles = alignment.curved ? " | "+plan.curvedTiles+" rail tiles" : "";
        line(body, String.format(Locale.ROOT, "%s alignment %.1f m%s%s",
                alignment.curved ? "Curved" : "Straight", alignment.length, tiles, grade), 240, 240, 240);
        double min = Double.POSITIVE_INFINITY, max = 0;
        List<Double> bents = alignment.supportDistances(supportSpacing);
        StringBuilder details = new StringBuilder();
        line(details, "Timber supports", 235, 200, 145);
        for (int i = 0; i < bents.size(); i++) {
            double distance = bents.get(i);
            Point point = alignment.at(distance);
            double height = Math.max(0, point.z - terrain(point));
            min = Math.min(min, height);
            max = Math.max(max, height);
            if (i < 8) line(details, String.format(Locale.ROOT,
                    "Bent %d | %.1f m along route | Top %.1f m | Height %.1f m",
                    i+1,distance,point.z,height),240,240,240);
        }
        line(body, String.format(Locale.ROOT, "Survey clear | %d proposed bents | height %.1f to %.1f m",
                bents.size(), min, max),145,235,160);
        if (alignment.hasDeckGrades()) line(body, "Automatic grades between poles clear the sampled terrain.",145,235,160);
        else if (plan.autoFitted) line(body, "Automatic pole heights clear the sampled terrain.",145,235,160);
        if (bents.size() > 8) line(details, "Showing the first eight supports.",240,240,240);
        return details.toString();
    }

    void pollPreviews(long now) {
        previews.values().removeIf(preview -> {
            if (preview.actor.hasLink() && now - preview.expires < 0) return false;
            preview.clear();
            return true;
        });
    }

    private void clearPreview(long player) {
        PreviewMarkers previous = previews.remove(player);
        if (previous != null) previous.clear();
    }

    private void showMarkers(Creature actor, TrestleAlignment alignment) {
        PreviewMarkers preview = new PreviewMarkers(actor);
        previews.put(actor.getWurmId(), preview);
        preview.mark(alignment.at(0));
        markPreviewDistances(preview, alignment, alignment.waypointDistances());
        markPreviewDistances(preview, alignment, alignment.supportDistances(supportSpacing));
        preview.mark(alignment.at(alignment.length));
    }

    private static void markPreviewDistances(PreviewMarkers preview, TrestleAlignment alignment, List<Double> distances) {
        int visible = Math.min(MAX_PREVIEW_BENTS, distances.size());
        for (int i = 0; i < visible; i++) {
            int index = (int)((i+.5)*distances.size()/visible);
            preview.mark(alignment.at(distances.get(index)));
        }
    }

    private static final class PreviewMarkers {
        final Creature actor;
        final long expires = System.nanoTime() + PREVIEW_NANOS;
        final List<Long> effects = new ArrayList<>();

        PreviewMarkers(Creature actor) { this.actor = actor; }

        void mark(Point point) {
            long id = WurmId.getNextTempItemId();
            effects.add(id);
            actor.getCommunicator().sendAddEffect(id,(short)2,(float)point.x,(float)point.y,(float)point.z,(byte)0);
        }

        void clear() {
            if (actor.hasLink()) for (long id : effects) actor.getCommunicator().sendRemoveEffect(id);
        }
    }

    private Plan plan(Creature actor, Draft draft, TrestleAlignment alignment) throws Exception {
        for (Site site : new Site[] {draft.start, draft.end}) {
            Node current = api.railAt(site.x, site.y);
            checkEndpoint(actor, site.x, site.y, current);
            if (site.rail == null ? current != null : current == null || !site.rail.equals(current.name)
                    || site.marker != current.markerId || site.heading != heading(current))
                throw new IllegalArgumentException("The approach at " + site.x + ", " + site.y + " changed. Mark it again.");
            if (current != null && Math.abs(site.point.z - groundCentreHeight(site.x, site.y)) > 0.05)
                throw new IllegalArgumentException("The approach elevation changed. Mark it again.");
            if (current != null && !current.links.isEmpty()) {
                Node connected = api.rail(current.links.iterator().next());
                String issue = WurmRailWorld.validateEdge(connected.tileX,connected.tileY,site.x,site.y,0);
                if (issue != null)
                    throw new IllegalArgumentException("Ground rails leading to the approach at " + site.x + ", " + site.y + ". " + issue);
            }
        }
        Set<Long> checked = new HashSet<>();
        List<GroundCheck> ground = new ArrayList<>();
        List<TrestleAlignment.TileSection> curvedTiles = alignment.curved ? alignment.tileSections() : Collections.emptyList();
        // A curve can clip a tile corner between the fixed clearance samples.
        for (TrestleAlignment.TileSection tile : curvedTiles) {
            if (draft.start.isAt(tile.x, tile.y) || draft.end.isAt(tile.x, tile.y))
                throw new IllegalArgumentException("The curve runs back into approach tile " + tile.x + ", "
                        + tile.y + ". Move the nearby pole to give the approach more room.");
            ground.add(checkDeckPoint(actor,draft,checked,alignment.at(tile.center()),tile.center()));
        }
        double[] startBank = bankShoulders(alignment, 0), endBank = bankShoulders(alignment, alignment.length);
        for (double distance : alignment.waypointDistances())
            checkDeckSection(actor,draft,checked,alignment,distance,ground,startBank,endBank);
        int samples = (int) Math.ceil(alignment.length * 2);
        for (int i = 0; i <= samples; i++)
            checkDeckSection(actor,draft,checked,alignment,alignment.length*((double)i/samples),ground,startBank,endBank);
        TrestleAlignment fitted = alignment;
        if (alignment.curved) {
            List<TrestleHeightFit.Sample> samplesForFit = new ArrayList<>();
            for (GroundCheck check : ground) samplesForFit.add(check.sample);
            fitted = TrestleHeightFit.fit(alignment, draft.elevationOverrides(), maxGradient, samplesForFit, curvedTiles);
        }
        for (GroundCheck check : ground) check.requireClear(fitted);
        validateApproaches(draft.start,draft.end,fitted);
        Plan plan = new Plan(draft.start,draft.end,fitted,supportSpacing,curvedTiles.size(),fitted != alignment);
        api.validateCrossings(plan);
        return plan;
    }

    static void validateApproaches(Site start, Site end, TrestleAlignment alignment) {
        validateApproach(start,alignment,true);
        validateApproach(end,alignment,false);
    }

    private static void validateApproach(Site site,TrestleAlignment alignment,boolean start) {
        try {
            WurmRailWorld.validateTrestleApproach(site.point,alignment,start);
        } catch (Exception blocked) {
            throw new IllegalArgumentException((start ? "Start" : "Destination") + " approach at "
                    + site.x + ", " + site.y + ". " + blocked.getMessage(),blocked);
        }
    }

    private void checkDeckSection(Creature actor, Draft draft, Set<Long> checked, TrestleAlignment alignment,
                                  double distance, List<GroundCheck> samples, double[] startBank, double[] endBank) throws Exception {
        GroundCheck highest = checkDeckPoint(actor,draft,checked,alignment.at(distance),distance);
        if (alignment.curved) {
            double startBlend = Math.max(0, 1 - distance / BANK_JOIN_LENGTH);
            double endBlend = Math.max(0, 1 - (alignment.length - distance) / BANK_JOIN_LENGTH);
            for (int i = 0; i < CURVED_OFFSETS.length; i++) {
                Point point = alignment.offsetAt(distance,CURVED_OFFSETS[i]);
                double bankAllowance = startBank[i] * startBlend + endBank[i] * endBlend;
                GroundCheck edge = checkDeckPoint(actor,draft,checked,point,distance,bankAllowance);
                if (edge.sample.ground > highest.sample.ground) highest = edge;
            }
        }
        samples.add(highest);
    }

    private static double[] bankShoulders(TrestleAlignment alignment, double seam) throws Exception {
        double[] shoulders = new double[CURVED_OFFSETS.length];
        if (alignment.curved) {
            double centre = terrain(alignment.at(seam));
            // The deck shoulders can sit in the bank at a ground join. The
            // centreline must meet the ground and full deck clearance resumes
            // over the first half tile.
            for (int i = 0; i < shoulders.length; i++)
                shoulders[i] = Math.max(0, terrain(alignment.offsetAt(seam,CURVED_OFFSETS[i])) - centre);
        }
        return shoulders;
    }

    private TrestleAlignment alignment(Draft draft) {
        Point towardsStart = draft.waypoints.isEmpty() ? draft.end.point : draft.waypoints.get(0).point;
        Point towardsEnd = draft.waypoints.isEmpty() ? draft.start.point : draft.waypoints.get(draft.waypoints.size()-1).point;
        int dx = draft.end.x - draft.start.x, dy = draft.end.y - draft.start.y;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) throw new IllegalArgumentException("Choose different start and destination tiles.");
        if (draft.waypoints.isEmpty() && (steps < 2 || dx != 0 && dy != 0 && Math.abs(dx) != Math.abs(dy)))
            throw new IllegalArgumentException("Choose a straight compass alignment with at least one tile between approaches.");
        double stride = 4 * Math.hypot(Integer.signum(dx), Integer.signum(dy));
        if (draft.waypoints.isEmpty() && stride * steps > maxLength)
            throw new IllegalArgumentException("The surveyed crossing exceeds " + maxLength / 4 + " tiles.");
        if (draft.waypoints.isEmpty()) {
            checkHeading(draft.start, towardsStart);
            checkHeading(draft.end, towardsEnd);
            Point first = innerEdge(draft.start, towardsStart);
            Point last = innerEdge(draft.end, towardsEnd);
            return TrestleAlignment.straight(first,last,maxLength,maxGradient);
        }
        int startHeading = approachHeading(draft.start, towardsStart);
        int endHeading = approachHeading(draft.end, towardsEnd);
        List<Point> points = new ArrayList<>();
        points.add(approachSeam(draft.start, startHeading));
        for (Waypoint pole : draft.waypoints) points.add(pole.point);
        points.add(approachSeam(draft.end, endHeading));
        return TrestleAlignment.fitWaypoints(points, curveRadius, maxLength, startHeading, (endHeading + 4) % 8);
    }

    private TrestleAlignment profile(Draft draft, TrestleAlignment horizontal) {
        if (draft.waypoints.isEmpty()) return horizontal;
        return horizontal.withWaypointElevations(draft.elevationOverrides(), maxGradient);
    }

    private static int approachHeading(Site site, Point pole) {
        if (site.heading != 0) return site.heading - 1;
        double angle = Math.atan2(pole.x - site.point.x, site.point.y - pole.y);
        return Math.floorMod((int)Math.round(angle / (Math.PI / 4)), 8);
    }

    private static Point approachSeam(Site site, int heading) {
        double angle = heading * Math.PI / 4;
        return innerEdge(site, new Point(site.point.x + Math.rint(Math.sin(angle)),
                site.point.y - Math.rint(Math.cos(angle)), site.point.z));
    }

    private GroundCheck checkDeckPoint(Creature actor,Draft draft,Set<Long> checked,Point point,double distance) throws Exception {
        return checkDeckPoint(actor,draft,checked,point,distance,0);
    }

    private GroundCheck checkDeckPoint(Creature actor,Draft draft,Set<Long> checked,Point point,double distance,
                                       double bankAllowance) throws Exception {
        int x = (int)Math.floor(point.x/4), y = (int)Math.floor(point.y/4);
        if (x<1 || y<1 || x>=Zones.worldTileSizeX-1 || y>=Zones.worldTileSizeY-1)
            throw new IllegalArgumentException("The planned deck extends outside the playable map.");
        long tile = (long)x<<32 | (y&0xffffffffL);
        if (checked.add(tile) && !draft.start.isAt(x,y) && !draft.end.isAt(x,y))
            checkCorridorTile(actor,x,y);
        return new GroundCheck(x,y,new TrestleHeightFit.Sample(distance,terrain(point)-bankAllowance));
    }

    private static final class GroundCheck {
        final int x, y;
        final TrestleHeightFit.Sample sample;

        GroundCheck(int x, int y, TrestleHeightFit.Sample sample) {
            this.x = x;
            this.y = y;
            this.sample = sample;
        }

        void requireClear(TrestleAlignment alignment) {
            double rail = alignment.elevationAt(sample.distance);
            if (rail+TrestleHeightFit.TERRAIN_TOLERANCE < sample.ground-1e-9)
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Tile %d, %d at %.1f m from the start. Ground %.2f m is %.2f m above the planned rail (%.2f m).",
                        x,y,sample.distance,sample.ground,sample.ground-rail,rail));
        }
    }

    private static Point innerEdge(Site site, Point toward) {
        double dx = toward.x-site.point.x, dy = toward.y-site.point.y;
        double offsetX = Math.abs(dx) >= Math.abs(dy) ? Math.copySign(2,dx) : 2*dx/Math.abs(dy);
        double offsetY = Math.abs(dy) >= Math.abs(dx) ? Math.copySign(2,dy) : 2*dy/Math.abs(dx);
        double x = site.point.x + offsetX, y = site.point.y + offsetY;
        int tileX = (int)(x / 4), tileY = (int)(y / 4);
        int dirt = Tiles.decodeHeight(Server.surfaceMesh.getTile(tileX, tileY));
        double ground = dirt / 10.0;
        // The point is on a tile edge, where native terrain is linear.
        double east = x / 4 - tileX, south = y / 4 - tileY;
        if (east > 0) ground += east * (Tiles.decodeHeight(Server.surfaceMesh.getTile(tileX + 1,tileY))-dirt) / 10.0;
        if (south > 0) ground += south * (Tiles.decodeHeight(Server.surfaceMesh.getTile(tileX,tileY + 1))-dirt) / 10.0;
        return new Point(x, y, ground + (site.point.z - groundCentreHeight(site.x,site.y)));
    }

    private static void checkHeading(Site from, Point to) {
        if (from.heading == 0) return;
        double angle = Math.toRadians((from.heading - 1) * 45.0);
        double dx = to.x - from.point.x, dy = to.y - from.point.y;
        double along = dx * Math.sin(angle) - dy * Math.cos(angle);
        double cross = dx * Math.cos(angle) + dy * Math.sin(angle);
        if (along <= 0 || Math.abs(cross) > 1e-6)
            throw new IllegalArgumentException("The survey must follow the approach heading at " + from.x + ", " + from.y + ".");
    }

    private void checkCorridorTile(Creature actor, int x, int y) {
        VolaTile tile = Zones.getTileOrNull(x, y, true);
        byte type = Tiles.decodeType(Server.surfaceMesh.getTile(x,y));
        if (type == Tiles.Tile.TILE_HOLE.id || type == Tiles.Tile.TILE_LAVA.id || Tiles.isTree(type) || Tiles.isBush(type))
            throw new IllegalArgumentException("Clear vegetation and avoid cave mouths and lava at " + x + ", " + y + ".");
        if (RailwayBridgeDeck.partAt(x, y, 0) != null
                || tile != null && (tile.getStructure() != null || tile.getFences().length > 0))
            throw new IllegalArgumentException("Existing infrastructure conflicts with the survey at " + x + ", " + y + ".");
        if (actor.getPower() < 2 && !Methods.isActionAllowed(actor, Actions.BUILD, true, x, y, 0, 0))
            throw new IllegalArgumentException("You need construction permission at " + x + ", " + y + ".");
    }

    private static float terrain(Point point) throws Exception {
        return Zones.calculateHeight((float) point.x, (float) point.y, true);
    }

    private static double groundCentreHeight(int x, int y) {
        // At a tile centre, native terrain interpolation averages the two
        // diagonal corners. Read the integer dirt heights so an exact grade
        // limit isn't exceeded by rounding two float heights separately.
        int northwest = Tiles.decodeHeight(Server.surfaceMesh.getTile(x, y));
        int southeast = Tiles.decodeHeight(Server.surfaceMesh.getTile(x + 1, y + 1));
        return (northwest + southeast) / 20.0;
    }

    private static void line(StringBuilder body, String text, int red, int green, int blue) {
        body.append("text{color=\"").append(red).append(',').append(green).append(',').append(blue)
                .append("\";text=\"").append(RailwayBml.text(text)).append("\"};");
    }

    private static void button(StringBuilder body, String id, String title) {
        body.append("button{id=\"").append(id).append("\";text=\"").append(title).append("\"};");
    }

    private static void elevation(StringBuilder body, String name, Site site) {
        if (site.rail != null) return;
        line(body, name + " track elevation (metres)", 240, 240, 240);
        body.append("input{id=\"").append(name).append("\";maxchars=\"16\";text=\"")
                .append(site.point.z).append("\"};");
    }

    private static void map(StringBuilder body, Draft draft, TrestleAlignment alignment, RadiusFailure issue) {
        List<Point> marks = new ArrayList<>();
        marks.add(draft.start.point);
        for (Waypoint pole : draft.waypoints) marks.add(pole.point);
        marks.add(draft.end.point);
        int focus = issue == null && draft.focus > marks.size() ? 0 : draft.focus;
        body.append("label{text=\"Route map requires the WurmRail client update\";");
        TrestleSurveyMap.appendPayload(body, marks, alignment, draft.zoom, focus, issue);
        body.append("};");
        body.append("harray{dropdown{id=\"mapFocus\";default=\"").append(focus)
                .append("\";options=\"Route centre,Start");
        for (int i = 1; i <= draft.waypoints.size(); i++) body.append(",Pole ").append(i);
        body.append(",Destination");
        if (issue != null) body.append(",Tight turn");
        body.append("\"};");
        if (issue != null) button(body, "zoomIssue", "Zoom to tight turn");
        if (draft.zoom > 0) button(body, "zoomOut", "Zoom out");
        if (draft.zoom < TrestleSurveyMap.MAX_ZOOM) button(body, "zoomIn", "Zoom in");
        button(body, "zoomFit", "Fit map");
        body.append("};");
    }

    private void showTurnAdvice(StringBuilder body, Draft draft, RadiusFailure issue) {
        Point p = issue.tightest;
        String radius = issue.sampledRadius < curveRadius
                ? String.format(Locale.ROOT, " | Fitted radius about %.1f m", issue.sampledRadius) : "";
        line(body, "Tightest sampled point near tile " + (int)Math.floor(p.x / TrestleAlignment.TILE)
                + ", " + (int)Math.floor(p.y / TrestleAlignment.TILE) + radius, 255, 155, 145);
        if (issue.section == 0 || issue.section == draft.waypoints.size()) {
            line(body, "Align the nearby pole with the approach heading. If the curve still bends too soon, move the pole farther along that line.", 240, 240, 240);
        } else {
            line(body, "Move a pole toward the outside of the bend or add a pole there to guide a wider turn. Line up the last pole with the destination for a straight approach.", 240, 240, 240);
        }
        line(body, "Re-mark moved poles in route order. A 180-degree turn needs about "
                + Math.round(2 * curveRadius / TrestleAlignment.TILE)
                + " tiles between track centerlines, plus room for the deck edges.", 190, 200, 205);
    }

    private static int lastPage(int poles) {
        return Math.max(0, poles-1)/POLES_PER_PAGE;
    }

    private void showElevations(StringBuilder body, Draft draft, TrestleAlignment profile) throws Exception {
        if (draft.start.rail == null || draft.end != null && draft.end.rail == null) {
            line(body, "Approach elevations", 235, 200, 145);
            elevation(body, "start", draft.start);
            if (draft.end != null) elevation(body, "end", draft.end);
        }
        if (!draft.waypoints.isEmpty()) {
            line(body, "Intermediate pole elevations", 235, 200, 145);
            line(body, "Blank uses automatic terrain fitting. Enter metres to set a fixed height.", 240, 240, 240);
            List<Double> distances = profile == null ? Collections.emptyList() : profile.waypointDistances();
            int first = draft.page*POLES_PER_PAGE, end = Math.min(first+POLES_PER_PAGE, draft.waypoints.size());
            if (draft.waypoints.size() > POLES_PER_PAGE)
                line(body, "Poles "+(first+1)+"–"+end+" of "+draft.waypoints.size(), 190, 200, 205);
            for (int i = first; i < end; i++) {
                Waypoint pole = draft.waypoints.get(i);
                Point p = pole.point;
                String mode = pole.elevation == null ? "Auto" : "Fixed";
                line(body, String.format(Locale.ROOT, "Pole %d  |  Tile %d, %d  |  %s",
                        i+1, (int)(p.x/4), (int)(p.y/4), mode), 235, 200, 145);
                String label = String.format(Locale.ROOT, "Ground %.2f m", terrain(p));
                if (!distances.isEmpty()) {
                    label += String.format(Locale.ROOT, "  |  Planned rail %.2f m  |  Incoming grade %+.2f%%",
                            profile.elevationAt(distances.get(i)), profile.gradeInto(distances.get(i))*100);
                }
                line(body, label, 240, 240, 240);
                body.append("harray{input{id=\"pole").append(i).append("\";maxchars=\"16\";text=\"")
                        .append(pole.elevation == null ? "" : pole.elevation).append("\"};};");
            }
            body.append("harray{");
            if (draft.page > 0) button(body, "previousPoles", "Previous poles");
            if (end < draft.waypoints.size()) button(body, "nextPoles", "Next poles");
            button(body, "undoPole", "Remove last intermediate pole");
            body.append("};");
        }
        if (draft.end != null) {
            if (profile != null && (profile.hasWaypointElevations() || profile.hasDeckGrades())) line(body, String.format(Locale.ROOT,
                    "Grade into destination %+.2f%%", profile.gradeInto(profile.length)*100), 240, 240, 240);
        }
    }

    private static double parseElevation(String value) {
        try {
            double z = Double.parseDouble(value);
            if (!Double.isFinite(z) || z < -3276.8 || z > 3276.7) throw new NumberFormatException();
            return z;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Enter an elevation from -3276.8 to 3276.7 metres.");
        }
    }

    private static final class Waypoint {
        final Point point;
        final Double elevation;

        Waypoint(Point point, Double elevation) {
            this.point = point;
            this.elevation = elevation;
        }
    }

    private static final class Draft {
        final Site start, end;
        final List<Waypoint> waypoints;
        final int page, zoom, focus;

        Draft(Site start, Site end) { this(start,end,Collections.emptyList(),0,0,0); }
        Draft(Site start, Site end, List<Waypoint> waypoints, int page, int zoom, int focus) {
            this.start = start;
            this.end = end;
            this.waypoints = Collections.unmodifiableList(new ArrayList<>(waypoints));
            this.page = page;
            this.zoom = zoom;
            this.focus = focus;
        }

        List<Double> elevationOverrides() {
            List<Double> elevations = new ArrayList<>();
            for (Waypoint pole : waypoints) elevations.add(pole.elevation);
            return elevations;
        }

        Draft edit(Properties answers) {
            List<Waypoint> poles = new ArrayList<>(waypoints);
            for (int i = page*POLES_PER_PAGE; i < Math.min((page+1)*POLES_PER_PAGE, poles.size()); i++) {
                String value = answers.getProperty("pole"+i);
                if (value == null) continue;
                try {
                    poles.set(i, new Waypoint(poles.get(i).point, value.trim().isEmpty() ? null : parseElevation(value)));
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("Pole "+(i+1)+". "+invalid.getMessage());
                }
            }
            int nextPage = page, nextZoom = zoom, nextFocus = focus;
            if ("true".equals(answers.getProperty("nextPoles"))) nextPage = Math.min(page+1,lastPage(poles.size()));
            if ("true".equals(answers.getProperty("previousPoles"))) nextPage = Math.max(0,page-1);
            if ("true".equals(answers.getProperty("zoomIn"))) nextZoom = Math.min(zoom+1,TrestleSurveyMap.MAX_ZOOM);
            if ("true".equals(answers.getProperty("zoomOut"))) nextZoom = Math.max(0,zoom-1);
            if (end != null && answers.getProperty("mapFocus") != null) {
                try {
                    nextFocus = Integer.parseInt(answers.getProperty("mapFocus"));
                    if (nextFocus < 0 || nextFocus > poles.size()+3) throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("Choose an available map point.");
                }
            }
            if ("true".equals(answers.getProperty("zoomIssue"))) {
                nextZoom = Math.max(2, zoom);
                nextFocus = poles.size()+3;
            }
            if ("true".equals(answers.getProperty("zoomFit"))) {
                nextZoom = 0;
                nextFocus = 0;
            }
            return new Draft(start.edit(answers.getProperty("start")),end == null ? null : end.edit(answers.getProperty("end")),
                    poles,nextPage,nextZoom,nextFocus);
        }
    }

    static final class Plan {
        final Site start, end;
        final TrestleAlignment alignment;
        final double spacing;
        final int curvedTiles;
        final boolean autoFitted;

        Plan(Site start,Site end,TrestleAlignment alignment,double spacing,int curvedTiles,boolean autoFitted) {
            this.start = start;
            this.end = end;
            this.alignment = alignment;
            this.spacing = spacing;
            this.curvedTiles=curvedTiles;
            this.autoFitted=autoFitted;
        }
    }

    static final class Site {
        final int x, y, heading;
        final Point point;
        final String rail;
        final long marker;

        Site(int x, int y, Point point, String rail, long marker, int heading) {
            this.x = x;
            this.y = y;
            this.point = point;
            this.rail = rail;
            this.marker = marker;
            this.heading = heading;
        }

        boolean isAt(int x, int y) { return this.x == x && this.y == y; }

        String label() {
            return String.format(Locale.ROOT, "%d, %d | %.2f m | %s", x, y, point.z,
                    rail == null ? "terrain approach" : "rail " + rail);
        }

        Site edit(String value) {
            if (rail != null || value == null) return this;
            return new Site(x, y, new Point(point.x, point.y, parseElevation(value)), rail, marker, heading);
        }
    }
}
