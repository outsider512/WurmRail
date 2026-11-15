package org.wurmrail;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.wurmrail.shared.TrestleWalkingHooks;

final class TrestleWalkingServerHooks {
    private TrestleWalkingServerHooks() { }

    static void install() {
        try {
            ClassPool pool = HookManager.getInstance().getClassPool();
            TrestleWalkingHooks.install(pool, "com.wurmonline.server.creatures.MovementScheme",
                    "if(!this.creature.isPlayer() || this.creature.getVehicle()!=-10L) return java.lang.Float.NaN;"
                    + " return org.wurmrail.TrestleWalkingWorld.height(x,y,feet);");
            CtClass zone = pool.get("com.wurmonline.server.zones.VirtualZone");
            zone.getDeclaredMethod("isMovingSameLevel").insertAfter(
                    "{ if($_ && org.wurmrail.TrestleWalkingWorld.needsExplicitHeight($1)) $_=false; }");
            for (CtMethod method : zone.getDeclaredMethods("addCreature")) {
                if (method.getParameterTypes().length != 6) continue;
                method.instrument(new ExprEditor() {
                    @Override public void edit(MethodCall call) throws javassist.CannotCompileException {
                        if ("sendNewCreature".equals(call.getMethodName()))
                            call.replace("{ if($11 && org.wurmrail.TrestleWalkingWorld.needsExplicitHeight(creature))"
                                    + " $11=false; $proceed($$); }");
                    }
                });
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot install WurmRail trestle walking", failure);
        }
    }
}
