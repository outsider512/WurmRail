package org.wurmrail.client;

import javassist.ClassPool;
import javassist.CtClass;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;

/** Adds WurmRail's local art pack before any client resource manager is built. */
public final class WurmRailModelsMod implements WurmClientMod, PreInitable {
    public void preInit() {
        try {
            ClassPool pool = HookManager.getInstance().getClassPool();
            org.wurmrail.shared.CoalVeinTiles.install(pool);
            CtClass resources = pool.get("com.wurmonline.client.resources.Resources");
            // Only string-based game references are safe during mod discovery.
            // Copy the caller's list: some native callers use immutable lists.
            resources.getDeclaredConstructor(new CtClass[] {
                    pool.get("java.io.File"), pool.get("java.util.List") })
                    .insertBeforeBody("{ if (!$2.contains(\"wurmrail-models.jar\")) {"
                            + " java.util.List railPacks = new java.util.ArrayList($2);"
                            + " railPacks.add(\"wurmrail-models.jar\"); $2 = railPacks; } }");
            RailVehicleTerrainHooks.install(pool);
            RailVehicleMotionHooks.install(pool);
            RailTrackTerrainHooks.install(pool);
            TrestleWalkingClientHooks.install(pool);
            TrestleMapHooks.install(pool);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot install WurmRail resources and vehicle presentation hooks", failure);
        }
    }
}
