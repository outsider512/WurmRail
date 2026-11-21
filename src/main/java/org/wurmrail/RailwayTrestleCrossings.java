package org.wurmrail;

import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.TrestleAlignment;
import org.wurmrail.shared.TrestleDeckSurface;
import org.wurmrail.shared.TrestlePanel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Clearance of independently supported decks. Bounds are intentionally conservative at corners. */
final class RailwayTrestleCrossings {
    private static final float CLEARANCE = 4f;
    private static final float VEHICLE_SEPARATION = 3f;
    private static final float BENT_RADIUS = 1.32f;
    private final RailNetwork network;
    private final Map<Long,List<Obstacle>> occupied = new HashMap<>();

    private RailwayTrestleCrossings(RailNetwork network) {
        this.network = network;
    }

    static void validate(RailNetwork network, RailwayTrestleSurvey.Plan plan) throws Exception {
        RailwayTrestleCrossings crossing = new RailwayTrestleCrossings(network);
        TrestleAlignment alignment = plan.alignment;
        if (alignment.curved) {
            for (TrestleAlignment.TileSection section : alignment.tileSections()) {
                TrestleAlignment.Point point = alignment.at(section.center());
                crossing.checkDeck(panel(alignment.panel(section),section.x*4+2,section.y*4+2,(float)point.z),plan);
            }
        } else {
            int dx = Integer.signum(plan.end.x-plan.start.x), dy = Integer.signum(plan.end.y-plan.start.y);
            int axis = RailNetwork.direction(dx,dy) & 3;
            int steps = Math.max(Math.abs(plan.end.x-plan.start.x),Math.abs(plan.end.y-plan.start.y));
            float grade = (float)(alignment.gradient * (RailNetwork.direction(dx,dy)<4 ? 1 : -1));
            for (int i=1;i<steps;i++) {
                float x = (plan.start.x+i*dx)*4+2, y = (plan.start.y+i*dy)*4+2;
                float z = (float)alignment.elevationAt(alignment.length*(i-.5)/(steps-1));
                crossing.checkDeck(straight(x,y,z,axis,grade),plan);
            }
        }
        for (double distance : alignment.supportDistances(plan.spacing)) {
            TrestleAlignment.Point point = alignment.at(distance);
            float x = (float)point.x, y = (float)point.y;
            float ground = Zones.calculateHeight(x,y,true);
            float top = (float)point.z-.11f;
            if (top <= ground) continue;
            Bounds bent = box(x,y,BENT_RADIUS,BENT_RADIUS,ground-1,top);
            for (Obstacle obstacle : crossing.near(bent)) {
                float occupiedTop = obstacle.bounds.high + (obstacle.rail == null ? 0 : VEHICLE_SEPARATION);
                if (bent.intersects(obstacle.bounds) && bent.low <= occupiedTop && bent.high >= obstacle.bounds.low
                        && (obstacle.rail == null || !approach(plan,obstacle.rail)))
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "A timber bent conflicts with %s near tile %d, %d. Move the survey so the supports clear the lower line.",
                            obstacle.label,(int)(x/4),(int)(y/4)));
            }
        }
    }

    private void checkDeck(Bounds deck,RailwayTrestleSurvey.Plan plan) {
        for (Obstacle obstacle : near(deck)) {
            if (!deck.intersects(obstacle.bounds) || obstacle.rail != null && approach(plan,obstacle.rail)) continue;
            if (deck.low < obstacle.bounds.high + CLEARANCE)
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "The upper deck needs %.1f m clearance above %s near tile %d, %d.",
                        CLEARANCE,obstacle.label,obstacle.x,obstacle.y));
        }
    }

    private static boolean approach(RailwayTrestleSurvey.Plan plan,Node rail) {
        return rail.name.equals(plan.start.rail) || rail.name.equals(plan.end.rail);
    }

    private List<Obstacle> near(Bounds bounds) {
        List<Obstacle> result = new ArrayList<>();
        // Curved shoulders, diagonal decks and bent caps can overhang their owning tile.
        for (int x=Math.max(1,tile(bounds.minX)-1);x<=Math.min(Zones.worldTileSizeX-2,tile(bounds.maxX)+1);x++)
            for (int y=Math.max(1,tile(bounds.minY)-1);y<=Math.min(Zones.worldTileSizeY-2,tile(bounds.maxY)+1);y++) {
                long key = (long)x<<32 | (y&0xffffffffL);
                List<Obstacle> obstacles = occupied.get(key);
                if (obstacles == null) {
                    obstacles = obstaclesAt(x,y);
                    occupied.put(key,obstacles);
                }
                result.addAll(obstacles);
            }
        return result;
    }

    private List<Obstacle> obstaclesAt(int x,int y) {
        List<Obstacle> result = new ArrayList<>();
        for (Node rail : network.nodesAt(x,y,0)) {
            Bounds bounds;
            if (network.isTrestleDeck(rail.name)) {
                Item item = Items.getItemOptional(rail.markerId).orElse(null);
                if (item == null || item.getTemplateId() != WurmRailWorld.trestleDeckTemplateId
                        || item.getTileX()!=x || item.getTileY()!=y || item.getParentId()!=-10L || !item.isOnSurface()) {
                    bounds = box(rail.worldX(),rail.worldY(),2.96f,2.96f,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY);
                } else {
                    TrestlePanel panel = RailModelRegistry.curvedDeck(item.getWurmId());
                    bounds = panel == null
                            ? straight(item.getPosX(),item.getPosY(),item.getPosZ(),item.getAuxData()&3,item.getData1()/1000000f)
                            : panel(panel,item.getPosX(),item.getPosY(),item.getPosZ());
                }
            } else bounds = ground(x,y);
            result.add(new Obstacle(bounds,rail,rail.name,x,y));
        }
        VolaTile tile = Zones.getTileOrNull(x,y,true);
        if (tile != null) for (Item item : tile.getItems()) {
            if (item.getTemplateId() == WurmRailWorld.trestleBentTemplateId) {
                float z = item.getPosZ();
                result.add(new Obstacle(box(item.getPosX(),item.getPosY(),BENT_RADIUS,BENT_RADIUS,
                        z-(item.getAuxData()&255),z),null,"existing trestle timber",x,y));
            }
        }
        return result;
    }

    private static Bounds ground(int x,int y) {
        float high = Float.NEGATIVE_INFINITY;
        for (int dx=0;dx<=1;dx++) for (int dy=0;dy<=1;dy++)
            high = Math.max(high,Tiles.decodeHeight(Server.surfaceMesh.getTile(x+dx,y+dy))/10f);
        return box(x*4+2,y*4+2,2,2,Float.NEGATIVE_INFINITY,high);
    }

    private static Bounds straight(float x,float y,float z,int axis,float grade) {
        boolean diagonal = (axis&1)!=0;
        float shoulder = TrestleDeckSurface.HALF_WIDTH*(diagonal ? .708f : 1f);
        float rise = Math.abs(grade)*(diagonal ? 2.829f : 2f);
        return box(x,y,axis==0 ? shoulder : 2+(diagonal ? shoulder : 0),
                axis==2 ? shoulder : 2+(diagonal ? shoulder : 0),z-rise,z+rise);
    }

    private static Bounds panel(TrestlePanel panel,float x,float y,float z) {
        Bounds result = new Bounds();
        for (int i=0;i<=TrestlePanel.INTERVALS;i++) for (float side : new float[]{-TrestleDeckSurface.HALF_WIDTH,TrestleDeckSurface.HALF_WIDTH}) {
            float[] vertex = panel.vertex((float)i/TrestlePanel.INTERVALS,side);
            result.include(x+vertex[0],y+vertex[1],z+vertex[2]);
        }
        return result;
    }

    private static Bounds box(float x,float y,float halfX,float halfY,float low,float high) {
        Bounds result = new Bounds();
        result.include(x-halfX,y-halfY,low);
        result.include(x+halfX,y+halfY,high);
        return result;
    }

    private static int tile(float point) { return (int)Math.floor(point/4); }

    static boolean vehicleLevelsOverlap(float first,float second) {
        return Math.abs(first-second) < VEHICLE_SEPARATION;
    }

    private static final class Bounds {
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,low=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,high=Float.NEGATIVE_INFINITY;

        void include(float x,float y,float z) {
            minX=Math.min(minX,x); maxX=Math.max(maxX,x);
            minY=Math.min(minY,y); maxY=Math.max(maxY,y);
            low=Math.min(low,z); high=Math.max(high,z);
        }

        boolean intersects(Bounds other) {
            return Math.min(maxX,other.maxX)-Math.max(minX,other.minX)>.001f
                    && Math.min(maxY,other.maxY)-Math.max(minY,other.minY)>.001f;
        }
    }

    private static final class Obstacle {
        final Bounds bounds;
        final Node rail;
        final String label;
        final int x,y;

        Obstacle(Bounds bounds,Node rail,String label,int x,int y) {
            this.bounds=bounds; this.rail=rail; this.label=label; this.x=x; this.y=y;
        }
    }
}
