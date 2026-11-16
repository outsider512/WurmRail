package org.wurmrail;

import java.util.concurrent.ConcurrentHashMap;
import org.wurmrail.shared.TrestlePanel;

/** Primitive-only model lookup: safe for the loader to verify before game classes are patched. */
public final class RailModelRegistry {
    private static volatile int locomotiveId, cartId, trackId, trackSectionId, pickupId, dropoffId, alignmentId, signalId;
    private static volatile int trestleDeckId, trestleBentId;
    private static final java.util.Set<Long> elevatedCars = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Long, TrestlePanel> curvedDecks = new ConcurrentHashMap<>();
    private RailModelRegistry() { }

    public static void register(int locomotive, int cart, int track) {
        locomotiveId = locomotive; cartId = cart; trackId = track;
    }

    public static void registerTrackSection(int trackSection) {
        trackSectionId = trackSection;
    }
    public static void registerPassengerPlatforms(int pickup,int dropoff) { pickupId=pickup; dropoffId=dropoff; }
    public static void registerJunctionAlignment(int alignment) { alignmentId=alignment; }
    public static void registerSignal(int signal) { signalId=signal; }
    public static void registerTrestle(int deck, int bent) { trestleDeckId = deck; trestleBentId = bent; }
    public static boolean isTrestle(int id) { return id != 0 && (id == trestleDeckId || id == trestleBentId); }
    public static boolean isElevatedCar(long id) { return elevatedCars.contains(id); }
    static void setElevatedCar(long id, boolean elevated) {
        if (elevated) elevatedCars.add(id);
        else elevatedCars.remove(id);
    }

    static TrestlePanel curvedDeck(long id) { return curvedDecks.get(id); }
    static boolean registerCurvedDeck(long id, TrestlePanel panel) { return curvedDecks.putIfAbsent(id, panel) == null; }
    static void forgetCurvedDeck(long id) { curvedDecks.remove(id); }

    public static String modelName(long item, int templateId, byte connections, int kind) {
        TrestlePanel panel = templateId == trestleDeckId ? curvedDeck(item) : null;
        return panel == null ? modelName(templateId, connections, kind) : panel.modelName();
    }

    public static boolean isRailVehicleModel(String model) {
        return "model.wurmrail.locomotive.".equals(model) || "model.wurmrail.cart.".equals(model)
                || "model.wurmrail.logcar.".equals(model) || "model.wurmrail.tankcar.".equals(model);
    }

    public static boolean isCustomTemplate(int templateId) {
        return templateId != 0 && (SteamWaterPumpRegistry.isPump(templateId) || LogCarRegistry.isLogCar(templateId) || SteamYarderRegistry.isYarder(templateId) || LogBunkRegistry.isBunk(templateId) || IndustrialWaterTankRegistry.isIndustrialTank(templateId) || TankCarRegistry.isTankCar(templateId) || SteamRockDrillRegistry.isDrill(templateId) || BulkHopperRegistry.isHopper(templateId) || templateId == locomotiveId || templateId == cartId
                || templateId == trackId || templateId == trackSectionId || templateId == pickupId || templateId == dropoffId || templateId == alignmentId || templateId == signalId || isTrestle(templateId));
    }

    public static String modelName(int templateId, byte connections, int kind) {
        if (templateId == trestleDeckId && trestleDeckId != 0)
            return "model.wurmrail.trestledeck."+(connections&3)+"."+kind+".";
        if (templateId == trestleBentId && trestleBentId != 0)
            return "model.wurmrail.trestlebent."+(connections&255)+".";
        if(SteamWaterPumpRegistry.isPump(templateId))return "model.wurmrail.steamwaterpump.";
        if(LogCarRegistry.isLogCar(templateId))return "model.wurmrail.logcar.";
        if(SteamYarderRegistry.isYarder(templateId))return "model.wurmrail.steamyarder.";
        if(LogBunkRegistry.isBunk(templateId))return "model.wurmrail.logbunk.";
        if(IndustrialWaterTankRegistry.isIndustrialTank(templateId))return "model.wurmrail.industrialwatertank.";
        if(TankCarRegistry.isTankCar(templateId))return "model.wurmrail.tankcar.";
        if(SteamRockDrillRegistry.isDrill(templateId))return "model.wurmrail.steamrockdrill.";
        if(BulkHopperRegistry.isHopper(templateId))return "model.wurmrail.bulkhopper.";
        if (templateId == locomotiveId && locomotiveId != 0) return "model.wurmrail.locomotive.";
        if (templateId == cartId && cartId != 0) return "model.wurmrail.cart.";
        if (templateId == trackSectionId && trackSectionId != 0) return "model.wurmrail.track.17.";
        if (templateId == pickupId && pickupId != 0) return "model.wurmrail.platform.pickup.";
        if (templateId == dropoffId && dropoffId != 0) return "model.wurmrail.platform.pickup.";
        if (templateId == signalId && signalId != 0) {
            int state=connections&255;
            if(state>15) state=0;
            return "model.wurmrail.signal."+(state&7)+"."+((state&8)!=0?"green":"red")+".";
        }
        if (templateId == alignmentId && alignmentId != 0) {
            int mask=connections&255;
            return "model.wurmrail.alignment."+(Integer.bitCount(mask)==2?mask:0)+".";
        }
        if (templateId == trackId && trackId != 0) {
            String group = kind == 1 ? "station" : kind == 2 ? "junction" : "track";
            return "model.wurmrail." + group + "." + (connections & 255) + ".";
        }
        return null;
    }
}
