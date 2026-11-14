package org.wurmrail;

/** Primitive-only identity used while native game classes are still being patched. */
public final class LogBunkRegistry {
    private static volatile int templateId;
    private LogBunkRegistry() { }
    public static void register(int id) {
        if(id<=0)throw new IllegalArgumentException("A valid Log Bunk template is required.");
        templateId=id;
    }
    public static boolean isBunk(int id){return templateId>0&&id==templateId;}
}
