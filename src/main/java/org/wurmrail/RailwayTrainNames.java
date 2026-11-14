package org.wurmrail;

import com.wurmonline.server.behaviours.Vehicle;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import org.wurmrail.core.RailNetwork.Train;

/** One visible train name for controls, the native item and its passenger vehicle. */
public final class RailwayTrainNames {
    private RailwayTrainNames() { }

    public static String label(Train train, Item engine) {
        if (train == null) throw new IllegalArgumentException("A train is required.");
        if (engine == null || usesTemplateName(engine))
            return train.name.startsWith("Train_") ? train.name.replace('_', ' ') : train.name;
        return engine.getName();
    }

    /** Native item creation prefixes its template name with a size such as 'large'. */
    private static boolean usesTemplateName(Item engine) {
        String actual=engine.getActualName();
        return actual == null || actual.isEmpty()
                || actual.equalsIgnoreCase(engine.getTemplate().getName())
                || actual.equalsIgnoreCase(ItemFactory.generateName(engine.getTemplate(),engine.getMaterial()));
    }

    /** Persist an old generic item name without replacing a player's custom name. */
    public static boolean synchronize(Train train, Item engine) {
        if (train == null || !WurmRailWorld.isLocomotive(engine)
                || train.carIds.isEmpty() || train.carIds.get(0).longValue()!=engine.getWurmId()) return false;
        boolean changed=false;
        if (usesTemplateName(engine)) {
            engine.setName(label(train,engine));
            changed=true;
        }
        Vehicle vehicle=Vehicles.getVehicle(engine);
        if (vehicle!=null && !engine.getName().equals(vehicle.name)) {
            vehicle.name=engine.getName();
            changed=true;
        }
        return changed;
    }
}
