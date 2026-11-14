package org.wurmrail;

import javassist.ClassPool;
import javassist.CtClass;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Narrow native gates; other Wurm containers keep their existing inventory rules. */
public final class LogCarHooks {
    private static boolean installed;
    private LogCarHooks() { }
    public static synchronized void init() {
        if(installed)return;
        try {
            ClassPool pool=HookManager.getInstance().getClassPool();
            CtClass item=pool.get("com.wurmonline.server.items.Item"),db=pool.get("com.wurmonline.server.items.DbItem");
            String guard="org.wurmrail.LogCarRegistry.isLogCar(this.getTemplateId())",policy="org.wurmrail.LogCarPolicy.canInsert(this,$1)";
            for(String name:new String[]{"testInsertItem","itemCanBeInserted","canHold"})
                item.getDeclaredMethod(name,new CtClass[]{item}).insertBefore("{ if("+guard+")return "+policy+"; }");
            item.getDeclaredMethod("mayCreatureInsertItem").insertBefore("{ if("+guard+")return org.wurmrail.LogCarPolicy.hasItemSlot(this); }");
            item.getDeclaredMethod("insertItem",new CtClass[]{item,CtClass.booleanType,CtClass.booleanType,CtClass.booleanType})
                    .insertBefore("{ if("+guard+"&&!"+policy+")return false; }");
            db.getDeclaredMethod("addItem",new CtClass[]{item,CtClass.booleanType}).insertBefore(
                    "{ if(!$2&&"+guard+"&&!"+policy+")throw new java.lang.IllegalArgumentException(\"Log Car rejected non-timber or over-capacity cargo.\"); }");
            db.getDeclaredMethod("setWeight",new CtClass[]{CtClass.intType,CtClass.booleanType,CtClass.booleanType}).insertBefore(
                    "{ if(!($1<=0&&$2)&&!org.wurmrail.LogCarPolicy.canChangeWeight(this,$1))return false; }");
            db.getDeclaredMethod("setTemplateId",new CtClass[]{CtClass.intType}).insertBefore(
                    "{ if(!org.wurmrail.LogCarPolicy.canChangeTemplate(this,$1))throw new java.lang.IllegalArgumentException(\"Log Cars store only felled trees and logs, within their capacity.\"); }");
            item.getDeclaredMethod("setSizes",new CtClass[]{CtClass.intType,CtClass.intType,CtClass.intType}).insertBefore(
                    "{ if(!org.wurmrail.LogCarPolicy.canResize(this,$1,$2,$3))throw new java.lang.IllegalArgumentException(\"Resizing this timber would exceed the Log Car's capacity.\"); }");
            installed=true;
        } catch(Exception failure){throw new IllegalStateException("Could not install Log Car inventory protection",failure);}
    }
}
