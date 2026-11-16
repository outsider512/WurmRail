package org.wurmrail;

import javassist.CtClass;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Install model selection without loading Item, Player, or any gameplay helper. */
public final class RailModelHooks {
    private static boolean installed;
    private RailModelHooks() { }

    public static synchronized void init() {
        if (installed) return;
        try {
            CtClass item = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item");
            item.getDeclaredMethod("getModelName").insertBefore(
                    "{ if (org.wurmrail.RailModelRegistry.isCustomTemplate(this.getTemplateId())) {"
                    + " return org.wurmrail.RailModelRegistry.modelName(this.getWurmId(), this.getTemplateId(),"
                    + " this.getAuxData(), this.getData1()); } }");
            // VolaTile marks deck vehicles as floor zero. The moving-item
            // spawn packet must still carry Z for clients joining mid-crossing.
            CtClass communicator = HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Communicator");
            CtClass zone = HookManager.getInstance().getClassPool().get("com.wurmonline.server.zones.VirtualZone");
            for (javassist.CtMethod method : zone.getDeclaredMethods("addItem")) {
                if (method.getParameterTypes().length != 4) continue;
                method.insertBefore("{ if (org.wurmrail.RailModelRegistry.isTrestle($1.getTemplateId())) $4=false; }");
            }
            for (javassist.CtMethod method : communicator.getDeclaredMethods("sendNewMovingItem")) {
                if (method.getParameterTypes().length != 15) continue;
                method.insertBefore("{ if (org.wurmrail.RailModelRegistry.isRailVehicleModel($4)"
                        + " && ($8 > 0L || org.wurmrail.RailModelRegistry.isElevatedCar($1))) $11 = false; }");
            }
            CtClass queued = HookManager.getInstance().getClassPool().get("com.wurmonline.server.players.PlayerCommunicatorQueued");
            for (javassist.CtMethod method : queued.getDeclaredMethods("sendNewMovingItem")) {
                if (method.getParameterTypes().length != 14) continue;
                method.insertBefore("{ if (org.wurmrail.RailModelRegistry.isElevatedCar($1)) $10 = false; }");
            }
            installed = true;
        } catch (Exception e) {
            throw new IllegalStateException("Could not install custom rail model selection", e);
        }
    }
}
