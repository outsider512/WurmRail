package org.wurmrail;

import javassist.ClassPool;
import javassist.CtClass;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Tank-only native insertion and liquid-growth guards; discovery remains primitive-only. */
public final class LiquidTankHooks {
    private static boolean installed;
    private LiquidTankHooks() { }
    public static synchronized void init() {
        if(installed)return;
        try {
            ClassPool pool=HookManager.getInstance().getClassPool();
            CtClass item=pool.get("com.wurmonline.server.items.Item"),db=pool.get("com.wurmonline.server.items.DbItem");
            String guard="org.wurmrail.IndustrialWaterTankRegistry.isLiquidTank(this.getTemplateId())";
            String policy="org.wurmrail.LiquidTankPolicy.canInsert(this,$1)";
            for(String name:new String[]{"testInsertItem","itemCanBeInserted"})
                item.getDeclaredMethod(name,new CtClass[]{item}).insertBefore("{ if("+guard+")return "+policy+"; }");
            item.getDeclaredMethod("canHold",new CtClass[]{item}).insertBefore(
                    "{ if("+guard+")return org.wurmrail.LiquidTankPolicy.canPour(this,$1); }");
            item.getDeclaredMethod("mayCreatureInsertItem").insertBefore(
                    "{ if("+guard+")return org.wurmrail.LiquidTankPolicy.hasItemSlot(this); }");
            item.getDeclaredMethod("insertItem",new CtClass[]{item,CtClass.booleanType,CtClass.booleanType,CtClass.booleanType})
                    .insertBefore("{ if("+guard+"&&!"+policy+")return false; }");
            db.getDeclaredMethod("addItem",new CtClass[]{item,CtClass.booleanType}).insertBefore(
                    "{ if(!$2&&"+guard+"&&!"+policy+")throw new java.lang.IllegalArgumentException(\"Tank Cars accept one supported liquid only, within their capacity.\"); }");
            db.getDeclaredMethod("setWeight",new CtClass[]{CtClass.intType,CtClass.booleanType,CtClass.booleanType})
                    .insertBefore("{ if(!($1<=0&&$2)&&!org.wurmrail.LiquidTankPolicy.canChangeWeight(this,$1))return false; }");
            db.getDeclaredMethod("setTemplateId",new CtClass[]{CtClass.intType}).insertBefore(
                    "{ if(!org.wurmrail.LiquidTankPolicy.canChangeTemplate(this,$1))throw new java.lang.IllegalArgumentException(\"Empty the Tank Car before changing its liquid type.\"); }");
            // This native action splits/subtracts before insertItem and ignores
            // insertion refusal. Reject incompatible pours before any source changes.
            pool.get("com.wurmonline.server.behaviours.MethodsItems").getDeclaredMethod("fillContainer",
                    new CtClass[]{pool.get("com.wurmonline.server.behaviours.Action"),item,item,
                            pool.get("com.wurmonline.server.creatures.Creature"),CtClass.booleanType})
                    .insertBefore("{ if($2!=null&&org.wurmrail.IndustrialWaterTankRegistry.isLiquidTank($2.getTemplateId())"
                    +"&&!org.wurmrail.LiquidTankPolicy.canPour($2,$3)) {"
                    +" if($4!=null&&!$5)$4.getCommunicator().sendNormalServerMessage(\"The Tank Car cannot accept this liquid or has no compatible space.\");return; } }");
            installed=true;
        } catch(Exception failure) { throw new IllegalStateException("Could not install Tank Car liquid protection",failure); }
    }
}
