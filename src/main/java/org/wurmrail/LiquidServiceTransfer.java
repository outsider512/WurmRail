package org.wurmrail;

/** Result of one bounded native-liquid operation, independent of any resource producer. */
public class LiquidServiceTransfer {
    public final long transferredMillilitres;
    public final boolean finished,requiresOperatorIntervention;
    public final String status;
    public LiquidServiceTransfer(long amount,boolean finished,boolean fault,String status) {
        if(amount<0)throw new IllegalArgumentException("Transferred liquid volume cannot be negative.");
        transferredMillilitres=amount;this.finished=finished;requiresOperatorIntervention=fault;
        StringBuilder text=new StringBuilder();if(status!=null)for(int i=0;i<status.length()&&text.length()<600;i++) {
            char c=status.charAt(i);text.append(Character.isISOControl(c)?' ':c);
        }
        this.status=text.toString().trim();
    }
    public static LiquidServiceTransfer ongoing(long amount,String status){return new LiquidServiceTransfer(amount,false,false,status);}
    public static LiquidServiceTransfer completed(long amount,String status){return new LiquidServiceTransfer(amount,true,false,status);}
    public static LiquidServiceTransfer fault(long amount,String status){return new LiquidServiceTransfer(amount,false,true,status);}
}
