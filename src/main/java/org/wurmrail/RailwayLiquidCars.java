package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.items.Item;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.wurmrail.core.RailNetwork.Train;

/** Shared rolling-stock access and diagnostics, without a natural-water or station dependency. */
public final class RailwayLiquidCars {
    private RailwayLiquidCars(){ }
    public static boolean acceptsCar(Item car,long owner) {
        return LiquidTankPolicy.isTankCar(car)&&car.isOwner(owner)&&MachineStorageLinks.placed(car)
                &&!car.isLocked()&&!car.isSealedByPlayer();
    }
    public static String capacitySummary(Train train) {
        if(train==null)return "";long stored=0,capacity=0;int count=0;boolean found=false;
        Set<String> types=new LinkedHashSet<String>();
        for(long id:train.carIds) {
            Item car=Items.getItemOptional(id).orElse(null);if(!LiquidTankPolicy.isTankCar(car))continue;found=true;
            if(!acceptsCar(car,train.ownerId))continue;
            stored+=LiquidTankPolicy.storedMillilitres(car);capacity+=LiquidTankPolicy.capacityMillilitres(car);count++;
            Item liquid=LiquidTankPolicy.contents(car);if(liquid!=null)types.add(liquid.getName());
        }
        return !found?"":count==0?"Tank liquid: no eligible unlocked Tank Cars.":String.format(Locale.ROOT,
                "Tank liquid: %,.1f / %,.1f litres across %d Tank Cars (%s).",stored/1000.0,capacity/1000.0,count,
                types.isEmpty()?"empty":String.join(", ",types));
    }
}
