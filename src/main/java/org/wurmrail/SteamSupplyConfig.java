package org.wurmrail;

import java.util.Properties;

/** Shared balancing values; energy is an abstract work value, never a fuel item identity. */
public final class SteamSupplyConfig {
    public static final long MICRO_PER_ENERGY=1000000L;
    public final double woodEnergyPerKg,charcoalEnergyPerKg,coalEnergyPerKg;
    public final double drillEnergy,yarderEnergy,pumpEnergyPerLitre,linkMetres;
    public final long drillWaterMillilitres,yarderWaterMillilitres,fuelBufferMicro,waterBufferMillilitres;
    private SteamSupplyConfig(Properties p) {
        woodEnergyPerKg=number(p,"steamWoodEnergyPerKg",1,.001,100000);
        charcoalEnergyPerKg=number(p,"steamCharcoalEnergyPerKg",3,.001,100000);
        coalEnergyPerKg=number(p,"steamCoalEnergyPerKg",6,.001,100000);
        drillEnergy=number(p,"steamDrillEnergyPerAction",10,0,1000000);
        yarderEnergy=number(p,"steamYarderEnergyPerAction",10,0,1000000);
        pumpEnergyPerLitre=number(p,"steamPumpEnergyPerLitre",.02,0,100000);
        drillWaterMillilitres=millilitres(number(p,"steamDrillWaterLitresPerAction",10,0,100000));
        yarderWaterMillilitres=millilitres(number(p,"steamYarderWaterLitresPerAction",5,0,100000));
        fuelBufferMicro=micro(number(p,"steamFuelBufferEnergy",60,.001,1000000));
        waterBufferMillilitres=millilitres(number(p,"steamWaterBufferLitres",100,.001,100000));
        linkMetres=number(p,"steamSupplyLinkMetres",16,4,64);
    }
    public static SteamSupplyConfig defaults(){return new SteamSupplyConfig(new Properties());}
    public static SteamSupplyConfig fromProperties(Properties p){if(p==null)throw new IllegalArgumentException("Steam configuration is required.");return new SteamSupplyConfig(p);}
    public static SteamSupplyConfig from(Properties p){return fromProperties(p);}
    public static long micro(double energy){return quantity(energy*MICRO_PER_ENERGY);}
    public static long millilitres(double litres){return quantity(litres*1000);}
    private static long quantity(double n){if(!Double.isFinite(n)||n<0||n>Long.MAX_VALUE)throw new IllegalArgumentException("Invalid steam quantity.");return (long)Math.ceil(n-0.0000001);}
    private static double number(Properties p,String name,double fallback,double min,double max){double v;try{v=Double.parseDouble(p.getProperty(name,Double.toString(fallback)).trim());}catch(NumberFormatException invalid){throw new IllegalArgumentException("Invalid "+name+".");}if(!Double.isFinite(v)||v<min||v>max)throw new IllegalArgumentException(name+" must be between "+min+" and "+max+".");return v;}
}
