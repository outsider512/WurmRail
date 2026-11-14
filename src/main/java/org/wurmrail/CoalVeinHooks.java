package org.wurmrail;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.wurmrail.shared.CoalVeinTiles;

/** Native mining adapters, installed without loading gameplay classes during discovery. */
public final class CoalVeinHooks {
    private static boolean installed;
    private CoalVeinHooks(){ }
    public static synchronized void init() {
        if(installed)return;
        try {
            ClassPool pool=HookManager.getInstance().getClassPool();
            CtClass rock=pool.get("com.wurmonline.server.behaviours.TileRockBehaviour");
            rock.getDeclaredMethod("getItemTemplateForTile",new CtClass[]{CtClass.byteType}).insertBefore(
                    "{ if(($1&255)=="+CoalVeinTiles.TILE_ID+") { int coal=org.wurmrail.CoalItemRegistry.templateId(); if(coal<=0)throw new java.lang.IllegalStateException(\"Coal item templates are not initialized.\"); return coal; } }");
            rock.getDeclaredMethod("getDifficultyForTile",new CtClass[]{CtClass.byteType}).insertBefore(
                    "{ if(($1&255)=="+CoalVeinTiles.TILE_ID+")return 10; }");
            CtClass wall=pool.get("com.wurmonline.server.behaviours.CaveWallBehaviour");
            CtMethod examine=wall.getDeclaredMethod("action",new CtClass[]{pool.get("com.wurmonline.server.behaviours.Action"),
                    pool.get("com.wurmonline.server.creatures.Creature"),CtClass.intType,CtClass.intType,CtClass.booleanType,
                    CtClass.intType,CtClass.intType,CtClass.shortType,CtClass.floatType});
            examine.insertBefore("{ if($8==1&&((com.wurmonline.mesh.Tiles.decodeType($6))&255)=="+CoalVeinTiles.TILE_ID+") { $2.getCommunicator().sendNormalServerMessage(\"A Coal Vein emerges here. This finite deposit yields coal rather than charcoal.\");return true; } }");
            // The native material27 label is 'charcoal'. Keep that vanilla material
            // unchanged while making coal prospecting describe the distinct item.
            for(CtMethod method:wall.getDeclaredMethods("action"))method.instrument(new ExprEditor(){
                @Override public void edit(MethodCall call)throws javassist.CannotCompileException {
                    if(call.getClassName().equals("com.wurmonline.server.creatures.Communicator")
                            &&call.getMethodName().equals("sendNormalServerMessage")
                            &&call.getSignature().startsWith("(Ljava/lang/String;"))
                        call.replace("{ $1=org.wurmrail.CoalVeinHooks.prospectMessage($1);$proceed($$); }");
                }
            });
            installed=true;
        }catch(Exception failure){throw new IllegalStateException("Could not install native Coal Vein mining support",failure);}
    }
    public static String prospectMessage(String message) {
        return "You would mine charcoal coal here.".equals(message)?"You would mine coal here.":message;
    }
}
