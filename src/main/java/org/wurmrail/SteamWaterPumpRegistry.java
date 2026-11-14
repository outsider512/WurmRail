package org.wurmrail;

/** Primitive-only identity lookup, safe during modloader bytecode preparation. */
public final class SteamWaterPumpRegistry {
    private static volatile int templateId;
    private SteamWaterPumpRegistry(){ }
    public static void register(int id){if(id<=0)throw new IllegalArgumentException("A pump template ID is required.");templateId=id;}
    public static boolean isPump(int id){return templateId>0&&id==templateId;}
}
