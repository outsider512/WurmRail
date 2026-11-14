package org.wurmrail;

/** Primitive-only identity/capacity, safe during modloader class transformation. */
public final class IndustrialWaterTankRegistry {
    public static final int MAX_VOLUME_CC=100000000,MAX_WEIGHT_GRAMS=100000000,MAX_ITEMS=1;
    private static volatile int templateId;
    private IndustrialWaterTankRegistry() { }
    public static void register(int id) {
        if(id<=0)throw new IllegalArgumentException("An Industrial Water Tank template is required.");
        templateId=id;
    }
    public static boolean isIndustrialTank(int id){return templateId>0&&id==templateId;}
    public static boolean isLiquidTank(int id){return isIndustrialTank(id)||TankCarRegistry.isTankCar(id);}
}
