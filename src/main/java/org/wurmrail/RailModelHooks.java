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
                    + " return org.wurmrail.RailModelRegistry.modelName(this.getTemplateId(),"
                    + " this.getAuxData(), this.getData1()); } }");
            // VolaTile marks deck vehicles as floor zero. The moving-item
            // spawn packet must still carry Z for clients joining mid-crossing.
            CtClass communicator = HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Communicator");
            for (javassist.CtMethod method : communicator.getDeclaredMethods("sendNewMovingItem")) {
                if (method.getParameterTypes().length != 15) continue;
                method.insertBefore("{ if ($8 > 0L && org.wurmrail.RailModelRegistry.isRailVehicleModel($4)) $11 = false; }");
            }
            installed = true;
        } catch (Exception e) {
            throw new IllegalStateException("Could not install custom rail model selection", e);
        }
    }
}
