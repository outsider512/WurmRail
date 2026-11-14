package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Real water production into industrial storage only, independent of railway services. */
public final class NaturalWaterProduction {
    private static final Logger LOG=Logger.getLogger(NaturalWaterProduction.class.getName());
    private NaturalWaterProduction(){ }
    public static String compatibilityProblem(Item tank,boolean salted) {
        if(!LiquidTankPolicy.isIndustrialTank(tank))return "Choose an Industrial Water Tank.";
        String problem=LiquidTankPolicy.contentsProblem(tank);if(problem!=null)return problem;
        Item water=LiquidTankPolicy.contents(tank);
        return water==null||water.getTemplateId()==ItemList.water&&water.isSalted()==salted&&water.getRarity()==0
                &&water.getLeftAuxData()==0&&(water.getRealTemplateId()==-10L||water.getRealTemplateId()==ItemList.water)
                ?null:"The Industrial Water Tank contains incompatible, rare or different-salinity water. Empty it before pumping this source.";
    }
    /** Caller revalidates source and durable storage ownership on the server thread. */
    public static LiquidTankTransfers.Result add(Item tank,long owner,long maxMillilitres,boolean salted) {
        return add(tank,owner,maxMillilitres,salted,SteamInputs.UNMETERED);
    }
    public static LiquidTankTransfers.Result add(Item tank,long owner,long maxMillilitres,boolean salted,SteamInputs.Work work) {
        if(maxMillilitres<0)throw new IllegalArgumentException("A non-negative pump allowance is required.");
        String problem=compatibilityProblem(tank,salted);if(problem!=null)return new LiquidTankTransfers.Result(0,false,problem);
        int amount=(int)Math.min(Integer.MAX_VALUE,Math.min(maxMillilitres,LiquidTankPolicy.remainingMillilitres(tank)));
        if(amount<=0)return new LiquidTankTransfers.Result(0,false,"The Industrial Water Tank is full; pumping resumes when capacity becomes available.");
        amount=(int)Math.min(amount,Math.floor(work.limitUnits(amount/1000.0)*1000+0.0000001));
        if(amount<=0)return new LiquidTankTransfers.Result(0,false,"SUPPLY CONFIG: pumping allowance is below the configured paid buffer limit.");
        SteamInputs.Payment payment=work.reserve(amount/1000.0);
        if(!payment.ready())return new LiquidTankTransfers.Result(0,payment.faulted(),payment.problem());
        Item water=LiquidTankPolicy.contents(tank);boolean created=false;int before=water==null?0:water.getWeightGrams();float qualityBefore=water==null?100:water.getCurrentQualityLevel();
        try {
            if(water==null) {
                water=ItemFactory.createItem(ItemList.water,100f,(byte)26,(byte)0,"WurmRail Steam Water Pump");created=true;
                water.setSizes(1,1,1);water.setLastOwnerId(owner);water.setWeight(amount,false);water.setIsSalted(salted);
                if(water.getWeightGrams()!=amount)throw new IllegalStateException("Native water weight was not accepted.");
                try{tank.insertItem(water,false,true);}catch(RuntimeException notification){if(!contained(tank,water))throw notification;}
                if(!contained(tank,water))throw new IllegalStateException("The Industrial Water Tank refused the new water.");
            } else {
                water.setWeight(before+amount,true);if(!contained(tank,water)||water.getWeightGrams()!=before+amount)throw new IllegalStateException("Native water growth was not accepted.");
                mixQuality(water,qualityBefore,before,amount);
            }
            if(LiquidTankPolicy.contentsProblem(tank)!=null)throw new IllegalStateException("The Industrial Water Tank's liquid inventory requires inspection.");
            payment.commit();
            return new LiquidTankTransfers.Result(amount,false,"Pumping water into the linked Industrial Water Tank.");
        } catch(Exception failure) {
            long actual=water!=null&&contained(tank,water)?Math.max(0,(long)water.getWeightGrams()-before):0;
            if(!created&&actual>0&&actual<=amount)try{mixQuality(water,qualityBefore,before,(int)actual);}catch(Exception qualityFailure){failure.addSuppressed(qualityFailure);}
            if(created&&actual==0&&water!=null)try {
                if(tank.getItems().contains(water))tank.dropItem(water.getWurmId(),false);Items.destroyItem(water.getWurmId());
                if(Items.getItemOptional(water.getWurmId()).isPresent()||tank.getItems().contains(water))throw new IllegalStateException("Uncommitted water cleanup could not be confirmed.");
                payment.refund();
                return new LiquidTankTransfers.Result(0,false,"Native storage refused water; nothing was added. Pumping retries when storage is available.");
            }catch(Exception cleanup){failure.addSuppressed(cleanup);}
            try {
                if(actual>=0&&actual<=amount&&(!created||actual>0))payment.complete(actual/(double)amount);
                else payment.uncertain();
            }catch(RuntimeException accounting){failure.addSuppressed(accounting);}
            LiquidTankTransfers.stopForRecovery(tank);LOG.log(Level.SEVERE,"Steam Water Pump stopped for tank "+tank.getWurmId(),failure);
            return new LiquidTankTransfers.Result(actual,true,"Inspect Industrial Water Tank "+tank.getWurmId()+" and clear its recovery stop before restarting the pump.");
        }
    }
    private static void mixQuality(Item water,float previousCurrentQuality,int previousWeight,int added){float mixed=(previousCurrentQuality*previousWeight+100f*added)/(previousWeight+added);water.setQualityLevel(mixed);if(Math.abs(water.getQualityLevel()-mixed)>.001f)throw new IllegalStateException("Native mixed-water quality could not be confirmed.");water.setDamage(0f);}
    private static boolean contained(Item parent,Item child){return child.getParentId()==parent.getWurmId()&&parent.getItems().contains(child);}
}
