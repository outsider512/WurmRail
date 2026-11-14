package org.wurmrail;

import javassist.ClassPool;
import javassist.CtClass;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Discovery-safe, hopper-only native inventory policy. Ordinary Wurm containers retain their rules. */
public final class BulkHopperHooks {
    private static boolean installed;
    private BulkHopperHooks() { }
    public static synchronized void init() {
        if(installed)return;
        try {
            ClassPool pool=HookManager.getInstance().getClassPool();
            CtClass item=pool.get("com.wurmonline.server.items.Item");
            String guard="org.wurmrail.BulkHopperRegistry.isHopper(this.getTemplateId())";
            String policy="org.wurmrail.BulkHopperPolicy.canInsert(this,$1)";
            CtClass nativeItem=pool.get("com.wurmonline.server.items.Item");
            for(String name:new String[]{"testInsertItem","itemCanBeInserted","canHold"})
                item.getDeclaredMethod(name,new CtClass[]{nativeItem}).insertBefore("{ if("+guard+")return "+policy+"; }");
            item.getDeclaredMethod("mayCreatureInsertItem").insertBefore(
                    "{ if("+guard+")return org.wurmrail.BulkHopperPolicy.hasItemSlot(this); }");
            item.getDeclaredMethod("insertItem",new CtClass[]{nativeItem,CtClass.booleanType,CtClass.booleanType,CtClass.booleanType})
                    .insertBefore("{ if("+guard+"&&!"+policy+")return false; }");
            // Loading saved inventory links must preserve existing items. Every
            // new low-level insertion, including unconditional calls, is checked.
            CtClass dbItem=pool.get("com.wurmonline.server.items.DbItem");
            dbItem.getDeclaredMethod("addItem",new CtClass[]{nativeItem,CtClass.booleanType}).insertBefore(
                    "{ if(!$2&&"+guard+"&&!"+policy+")throw new java.lang.IllegalArgumentException(\"Bulk Hopper rejected an invalid or over-capacity resource.\"); }");
            installed=true;
        } catch(Exception failure) {
            throw new IllegalStateException("Could not install Bulk Hopper inventory protection",failure);
        }
    }
}
