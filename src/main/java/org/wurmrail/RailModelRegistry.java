package org.wurmrail;

/** Primitive-only model lookup: safe for the loader to verify before game classes are patched. */
public final class RailModelRegistry {
    private static volatile int locomotiveId, cartId, trackId, trackSectionId, pickupId, dropoffId, alignmentId, signalId;
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

    public static boolean isRailVehicleModel(String model) {
        return "model.wurmrail.locomotive.".equals(model) || "model.wurmrail.cart.".equals(model)
                || "model.wurmrail.logcar.".equals(model) || "model.wurmrail.tankcar.".equals(model);
    }

    public static boolean isCustomTemplate(int templateId) {
        return templateId != 0 && (SteamWaterPumpRegistry.isPump(templateId) || LogCarRegistry.isLogCar(templateId) || SteamYarderRegistry.isYarder(templateId) || LogBunkRegistry.isBunk(templateId) || IndustrialWaterTankRegistry.isIndustrialTank(templateId) || TankCarRegistry.isTankCar(templateId) || SteamRockDrillRegistry.isDrill(templateId) || BulkHopperRegistry.isHopper(templateId) || templateId == locomotiveId || templateId == cartId
                || templateId == trackId || templateId == trackSectionId || templateId == pickupId || templateId == dropoffId || templateId == alignmentId || templateId == signalId);
    }

    public static String modelName(int templateId, byte connections, int kind) {
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
