package org.wurmrail;

/** Primitive-only custom model identity, safe during mod discovery. */
public final class SteamYarderRegistry {
    private static volatile int templateId;
    private SteamYarderRegistry() { }
    public static void register(int id){if(id<=0)throw new IllegalArgumentException("A Steam Yarder template is required.");templateId=id;}
    public static boolean isYarder(int id){return templateId>0&&id==templateId;}
}
