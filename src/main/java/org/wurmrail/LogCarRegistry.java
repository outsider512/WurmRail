package org.wurmrail;

/** Primitive-only lookup remains safe during modloader class transformation. */
public final class LogCarRegistry {
    private static volatile int templateId;
    private LogCarRegistry() { }
    public static void register(int id) {
        if(id<=0)throw new IllegalArgumentException("A valid Log Car template is required.");
        templateId=id;
    }
    public static boolean isLogCar(int id){return templateId>0&&templateId==id;}
    public static boolean isCar(int id){return isLogCar(id);}
}
