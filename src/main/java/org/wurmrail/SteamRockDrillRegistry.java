package org.wurmrail;

/** Primitive-only lookup while the modloader is still modifying game classes. */
public final class SteamRockDrillRegistry {
    private static volatile int templateId;
    private SteamRockDrillRegistry() { }
    public static void register(int id) {
        if(id<=0)throw new IllegalArgumentException("A Steam Rock Drill template is required.");
        templateId=id;
    }
    public static boolean isDrill(int id) { return templateId>0&&id==templateId; }
}
