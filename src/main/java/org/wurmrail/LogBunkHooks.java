package org.wurmrail;

import javassist.ClassPool;
import javassist.CtClass;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Narrow native gates; other Wurm containers keep their existing inventory rules. */
public final class LogBunkHooks {
    private static boolean installed;
    private LogBunkHooks() { }
    public static synchronized void init() {
        if(installed)return;
        try {
            ClassPool pool=HookManager.getInstance().getClassPool();
            CtClass item=pool.get("com.wurmonline.server.items.Item"),db=pool.get("com.wurmonline.server.items.DbItem");
            String guard="org.wurmrail.LogBunkRegistry.isBunk(this.getTemplateId())",policy="org.wurmrail.LogBunkPolicy.canInsert(this,$1)";
            for(String name:new String[]{"testInsertItem","itemCanBeInserted","canHold"})
                item.getDeclaredMethod(name,new CtClass[]{item}).insertBefore("{ if("+guard+")return "+policy+"; }");
            item.getDeclaredMethod("mayCreatureInsertItem").insertBefore("{ if("+guard+")return org.wurmrail.LogBunkPolicy.hasItemSlot(this); }");
            item.getDeclaredMethod("insertItem",new CtClass[]{item,CtClass.booleanType,CtClass.booleanType,CtClass.booleanType})
                    .insertBefore("{ if("+guard+"&&!"+policy+")return false; }");
            db.getDeclaredMethod("addItem",new CtClass[]{item,CtClass.booleanType}).insertBefore(
                    "{ if(!$2&&"+guard+"&&!"+policy+")throw new java.lang.IllegalArgumentException(\"Log Bunk rejected non-timber or over-capacity cargo.\"); }");
            db.getDeclaredMethod("setWeight",new CtClass[]{CtClass.intType,CtClass.booleanType,CtClass.booleanType}).insertBefore(
                    "{ if(!($1<=0&&$2)&&!org.wurmrail.LogBunkPolicy.canChangeWeight(this,$1))return false; }");
            db.getDeclaredMethod("setTemplateId",new CtClass[]{CtClass.intType}).insertBefore(
                    "{ if(!org.wurmrail.LogBunkPolicy.canChangeTemplate(this,$1))throw new java.lang.IllegalArgumentException(\"Log Bunks store only felled trees and logs, within their capacity.\"); }");
            item.getDeclaredMethod("setSizes",new CtClass[]{CtClass.intType,CtClass.intType,CtClass.intType}).insertBefore(
                    "{ if(!org.wurmrail.LogBunkPolicy.canResize(this,$1,$2,$3))throw new java.lang.IllegalArgumentException(\"Resizing this timber would exceed the Log Bunk's capacity.\"); }");
            installed=true;
        } catch(Exception failure){throw new IllegalStateException("Could not install Log Bunk inventory protection",failure);}
    }
}
