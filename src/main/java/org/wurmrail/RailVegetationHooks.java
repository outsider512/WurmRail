package org.wurmrail;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javassist.CtClass;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Natural vegetation respects railway right of way, like native animal tracks. */
public final class RailVegetationHooks {
    private static boolean installed;
    private static volatile Set<Long> surfaceRails=Collections.emptySet();
    private RailVegetationHooks() { }

    /** Discovery-safe: install bytecode without linking any server gameplay class. */
    public static synchronized void init() {
        if(installed)return;
        try {
            CtClass poller=HookManager.getInstance().getClassPool().get("com.wurmonline.server.zones.TilePoller");
            // Sprouts select a destination up to ten tiles from the parent. Test
            // the destination through Wurm's own native-track exclusion point.
            poller.getDeclaredMethod("containsTracks").insertBefore(
                    "{ if (pollingSurface && org.wurmrail.RailVegetationHooks.hasSurfaceRail($1,$2)) return true; }");
            // Lingonberries have a separate spontaneous creation path that does
            // not consult containsTracks at all.
            poller.getDeclaredMethod("checkForLingonberryStart").insertBefore(
                    "{ if (pollingSurface && org.wurmrail.RailVegetationHooks.hasSurfaceRail($2,$3)) return; }");
            installed=true;
        } catch(Exception failure) {
            throw new IllegalStateException("Could not install railway vegetation protection",failure);
        }
    }

    public static long tileKey(int x,int y) { return ((long)x<<32)|(y&0xffffffffL); }
    public static boolean hasSurfaceRail(int x,int y) { return surfaceRails.contains(tileKey(x,y)); }
    static void replaceSurfaceRails(Set<Long> tiles) {
        surfaceRails=Collections.unmodifiableSet(new HashSet<Long>(tiles));
    }
}
