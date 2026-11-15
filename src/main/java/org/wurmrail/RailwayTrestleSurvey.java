package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
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

/** Session-local survey drafts. All changes and terrain reads run on the server poll. */
final class RailwayTrestleSurvey {
    interface Api {
        Node railAt(int x, int y);
        Node rail(String name);
        boolean trackPresent(Node rail);
        void build(Creature actor, Plan plan) throws Exception;
        void submit(Creature actor, Response response);
    }

    interface Response { void apply() throws Exception; }

    private final ConcurrentHashMap<Long, Draft> drafts = new ConcurrentHashMap<>();
    private final Api api;
    private final double maxLength, maxGradient, supportSpacing;

    RailwayTrestleSurvey(Api api, int maxTiles, double maxGradient, int spacingTiles) {
        this.api = api;
        this.maxLength = maxTiles * TrestleAlignment.TILE;
        this.maxGradient = maxGradient;
        this.supportSpacing = spacingTiles * TrestleAlignment.TILE;
    }

    boolean hasSurvey(Creature actor) { return drafts.containsKey(actor.getWurmId()); }

    void handle(Creature actor, RailwayTrestleActions.Operation operation, long tool, int x, int y,
                long target) throws Exception {
        if (!RailwayTrestleActions.ownsTool(actor, Items.getItem(tool), ItemList.dioptra)
                || !RailwayTrestleActions.reachable(actor, x, y))
            throw new IllegalArgumentException("Carry the dioptra and stand near the survey target.");
        if (operation == RailwayTrestleActions.Operation.OPEN) {
            show(actor, requireDraft(actor), tool, null);
            return;
        }
        Node rail = api.railAt(x, y);
        if (target != -10L) {
            Item item = Items.getItem(target);
            if (item.getTileX() != x || item.getTileY() != y || !item.isOnSurface()
                    || item.getFloorLevel() != 0 || item.getBridgeId() != -10L
                    || item.getParentId() != -10L || item.getOwnerId() != -10L
                    || item.isBanked() || item.isTraded() || item.isMailed())
                throw new IllegalArgumentException("The survey target moved. Select it again.");
            if (operation == RailwayTrestleActions.Operation.MARK && item.getTemplateId() != ItemList.rangePole)
                throw new IllegalArgumentException("Place a range pole on the ground for the destination.");
            if (operation == RailwayTrestleActions.Operation.START
                    && (rail == null || rail.markerId != target || !api.trackPresent(rail)))
                throw new IllegalArgumentException("Choose a current railway track marker.");
        } else if (operation == RailwayTrestleActions.Operation.MARK) {
            throw new IllegalArgumentException("Choose the placed range pole.");
        }
        checkEndpoint(actor, x, y, rail);
        Point point = new Point(x * 4.0 + 2, y * 4.0 + 2, groundCentreHeight(x, y));
        Site site = new Site(x, y, point, rail == null ? null : rail.name, rail == null ? 0 : rail.markerId,
                rail == null ? 0 : heading(rail));
        Draft next;
        if (operation == RailwayTrestleActions.Operation.START) next = new Draft(site, null);
        else next = new Draft(requireDraft(actor).start, site);
        drafts.put(actor.getWurmId(), next);
        if (operation == RailwayTrestleActions.Operation.START)
            actor.getCommunicator().sendNormalServerMessage("Trestle survey started. Place a range pole at the destination and mark it with the dioptra.");
        show(actor, next, tool, null);
    }

    private int heading(Node rail) {
        if (rail.links.isEmpty()) return 0;
        Node linked = api.rail(rail.links.iterator().next());
        if (linked == null || linked.layer != 0)
            throw new IllegalArgumentException("Choose an approach that leads away from the cave entrance.");
        return 1 + (RailNetwork.direction(linked.tileX - rail.tileX, linked.tileY - rail.tileY) + 4) % 8;
    }

    private void checkEndpoint(Creature actor, int x, int y, Node rail) {
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

    private void show(Creature actor, Draft draft, long tool, String error) throws Exception {
        StringBuilder body = new StringBuilder();
        line(body, "Start " + draft.start.label(), 240, 240, 240);
        if (draft.end == null) {
            line(body, "Place a range pole at the destination. Activate the dioptra and choose Mark Trestle Destination on the pole.", 240, 240, 240);
        } else {
            line(body, "Destination " + draft.end.label(), 240, 240, 240);
            boolean valid = preview(actor, draft, body);
            elevation(body, "start", draft.start);
            elevation(body, "end", draft.end);
            button(body, "preview", "Update preview");
            button(body, "clear", "Remove destination");
            if (valid && actor.getPower() >= 2) button(body, "instant", "GM build straight trestle");
        }
        if (error != null) line(body, error, 255, 155, 145);
        button(body, "cancel", "Cancel survey");
        String content = body.toString();
        ModQuestions.createQuestion(actor, "Railway trestle survey", "", tool, new ModQuestion() {
            private final AtomicBoolean answered = new AtomicBoolean();

            @Override public void sendQuestion(Question question) {
                sendBml(question, 660, 540, true, true,
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
            drafts.remove(actor.getWurmId());
            actor.getCommunicator().sendNormalServerMessage("Railway trestle survey cancelled.");
            return;
        }
        if (draft.end == null) return;
        if ("true".equals(answers.getProperty("instant"))) {
            try {
                if (actor.getPower() < 2) throw new IllegalArgumentException("Instant construction requires GM power 2.");
                if (!RailwayTrestleActions.reachable(actor,draft.start.x,draft.start.y)
                        && !RailwayTrestleActions.reachable(actor,draft.end.x,draft.end.y))
                    throw new IllegalArgumentException("Stand at either approach to build the trestle.");
                api.build(actor, plan(actor, draft));
                drafts.remove(actor.getWurmId());
                actor.getCommunicator().sendNormalServerMessage("Straight railway trestle built.");
            } catch (IllegalArgumentException invalid) {
                show(actor, draft, tool, invalid.getMessage());
            }
            return;
        }
        Draft next;
        try {
            if ("true".equals(answers.getProperty("clear"))) next = new Draft(draft.start, null);
            else if ("true".equals(answers.getProperty("preview")))
                next = new Draft(draft.start.edit(answers.getProperty("start")), draft.end.edit(answers.getProperty("end")));
            else return;
        } catch (IllegalArgumentException invalid) {
            show(actor, draft, tool, invalid.getMessage());
            return;
        }
        drafts.put(actor.getWurmId(), next);
        show(actor, next, tool, null);
    }

    private boolean preview(Creature actor, Draft draft, StringBuilder body) throws Exception {
        try {
            Plan plan = plan(actor, draft);
            TrestleAlignment alignment = plan.alignment;
            line(body, String.format(Locale.ROOT, "Straight alignment %.1f m | gradient %+.2f%%",
                    alignment.length, alignment.gradient * 100), 240, 240, 240);
            double min = Double.POSITIVE_INFINITY, max = 0;
            int count = 0;
            StringBuilder bents = new StringBuilder();
            for (double distance : alignment.supportDistances(supportSpacing)) {
                Point point = alignment.at(distance);
                double height = Math.max(0, point.z - terrain(point));
                min = Math.min(min, height);
                max = Math.max(max, height);
                count++;
                if (count <= 8) line(bents, String.format(Locale.ROOT,
                        "Bent %d | %.1f m along route | top %.1f m | height %.1f m",
                        count, distance, point.z, height), 240, 240, 240);
            }
            line(body, String.format(Locale.ROOT, "Survey clear | %d proposed bents | height %.1f to %.1f m", count, min, max),
                    145, 235, 160);
            body.append(bents);
            if (count > 8) line(body, "Showing the first eight bents.", 240, 240, 240);
            return true;
        } catch (IllegalArgumentException invalid) {
            line(body, invalid.getMessage(), 255, 155, 145);
            return false;
        }
    }

    private Plan plan(Creature actor, Draft draft) throws Exception {
        for (Site site : new Site[] {draft.start, draft.end}) {
            Node current = api.railAt(site.x, site.y);
            checkEndpoint(actor, site.x, site.y, current);
            if (site.rail == null ? current != null : current == null || !site.rail.equals(current.name)
                    || site.marker != current.markerId || site.heading != heading(current))
                throw new IllegalArgumentException("The approach at " + site.x + ", " + site.y + " changed. Mark it again.");
            if (current != null && Math.abs(site.point.z - groundCentreHeight(site.x, site.y)) > 0.05)
                throw new IllegalArgumentException("The approach elevation changed. Mark it again.");
        }
        checkHeading(draft.start, draft.end);
        checkHeading(draft.end, draft.start);
        int dx = draft.end.x - draft.start.x, dy = draft.end.y - draft.start.y;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps < 2 || dx != 0 && dy != 0 && Math.abs(dx) != Math.abs(dy))
            throw new IllegalArgumentException("Choose a straight compass alignment with at least one tile between approaches.");
        double stride = 4 * Math.hypot(Integer.signum(dx), Integer.signum(dy));
        if (stride * steps > maxLength)
            throw new IllegalArgumentException("The surveyed crossing exceeds " + maxLength / 4 + " tiles.");
        double halfX = 2 * Integer.signum(dx), halfY = 2 * Integer.signum(dy);
        Point first = innerEdge(draft.start, halfX, halfY);
        Point last = innerEdge(draft.end, -halfX, -halfY);
        TrestleAlignment alignment = TrestleAlignment.straight(first, last, maxLength, maxGradient);
        Set<Long> checked = new HashSet<>();
        int samples = (int) Math.ceil(alignment.length * 2);
        for (int i = 0; i <= samples; i++) {
            Point point = alignment.at(alignment.length * ((double) i / samples));
            int x = (int) (point.x / 4), y = (int) (point.y / 4);
            long tile = (long) x << 32 | (y & 0xffffffffL);
            if (checked.add(tile) && !draft.start.isAt(x, y) && !draft.end.isAt(x, y))
                checkCorridorTile(actor, x, y);
            double ground = terrain(point);
            if (point.z + 0.05 < ground)
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Tile %d, %d at %.1f m from the deck start: ground %.2f m is %.2f m above the planned rail (%.2f m).",
                        x, y, alignment.length * ((double) i / samples), ground, ground - point.z, point.z));
        }
        return new Plan(draft.start, draft.end, alignment, supportSpacing);
    }

    private static Point innerEdge(Site site, double offsetX, double offsetY) {
        double x = site.point.x + offsetX, y = site.point.y + offsetY;
        int tileX = (int)(x / 4), tileY = (int)(y / 4);
        int dirt = Tiles.decodeHeight(Server.surfaceMesh.getTile(tileX, tileY));
        double ground = dirt / 10.0;
        // Cardinal approaches meet at an edge midpoint; diagonals meet at a corner.
        if (offsetX == 0) ground = (dirt + Tiles.decodeHeight(Server.surfaceMesh.getTile(tileX + 1,tileY))) / 20.0;
        if (offsetY == 0) ground = (dirt + Tiles.decodeHeight(Server.surfaceMesh.getTile(tileX,tileY + 1))) / 20.0;
        return new Point(x, y, ground + (site.point.z - groundCentreHeight(site.x,site.y)));
    }

    private static void checkHeading(Site from, Site to) {
        if (from.heading == 0) return;
        double angle = Math.toRadians((from.heading - 1) * 45.0);
        double dx = to.point.x - from.point.x, dy = to.point.y - from.point.y;
        double along = dx * Math.sin(angle) - dy * Math.cos(angle);
        double cross = dx * Math.cos(angle) + dy * Math.sin(angle);
        if (along <= 0 || Math.abs(cross) > 1e-6)
            throw new IllegalArgumentException("The straight survey must follow the approach heading at " + from.x + ", " + from.y + ".");
    }

    private void checkCorridorTile(Creature actor, int x, int y) {
        VolaTile tile = Zones.getTileOrNull(x, y, true);
        byte type = Tiles.decodeType(Server.surfaceMesh.getTile(x,y));
        if (type == Tiles.Tile.TILE_HOLE.id || type == Tiles.Tile.TILE_LAVA.id || Tiles.isTree(type) || Tiles.isBush(type))
            throw new IllegalArgumentException("Clear vegetation and avoid cave mouths and lava at " + x + ", " + y + ".");
        if (RailwayBridgeDeck.partAt(x, y, 0) != null || api.railAt(x, y) != null
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

    private static final class Draft {
        final Site start, end;
        Draft(Site start, Site end) { this.start = start; this.end = end; }
    }

    static final class Plan {
        final Site start, end;
        final TrestleAlignment alignment;
        final double spacing;

        Plan(Site start, Site end, TrestleAlignment alignment, double spacing) {
            this.start = start;
            this.end = end;
            this.alignment = alignment;
            this.spacing = spacing;
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
            try {
                double z = Double.parseDouble(value);
                if (!Double.isFinite(z) || z < -3276.8 || z > 3276.7) throw new NumberFormatException();
                return new Site(x, y, new Point(point.x, point.y, z), rail, marker, heading);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Enter an elevation from -3276.8 to 3276.7 metres.");
            }
        }
    }
}
