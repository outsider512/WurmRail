package org.wurmrail;

/** Primitive-only lookup safe while the modloader is still patching game classes. */
public final class TankCarRegistry {
    private static volatile int templateId;
    private TankCarRegistry() { }
    public static void register(int id) {
        if(id<=0)throw new IllegalArgumentException("A valid Tank Car template is required.");
        templateId=id;
    }
    public static boolean isTankCar(int id) { return templateId>0&&id==templateId; }
}
