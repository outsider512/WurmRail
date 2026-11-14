package org.wurmrail;

/** Primitive-only lookup used by hooks while game classes are still being patched. */
public final class BulkHopperRegistry {
    private static volatile int templateId;
    private BulkHopperRegistry() { }
    public static void register(int id) {
        if(id<=0)throw new IllegalArgumentException("A valid Bulk Hopper template is required.");
        templateId=id;
    }
    public static boolean isHopper(int id) { return templateId>0&&id==templateId; }
}
