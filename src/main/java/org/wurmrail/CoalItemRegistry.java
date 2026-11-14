package org.wurmrail;

/** Primitive-only lookup, safe before native gameplay classes have been transformed. */
public final class CoalItemRegistry {
    private static volatile int templateId;
    private CoalItemRegistry(){ }
    public static void register(int id){if(id<=0||id==204)throw new IllegalArgumentException("Coal requires its own item template, separate from charcoal.");templateId=id;}
    public static int templateId(){return templateId;}
    public static boolean isCoal(int id){return templateId>0&&id==templateId;}
}
