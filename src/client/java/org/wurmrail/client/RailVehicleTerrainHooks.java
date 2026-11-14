package org.wurmrail.client;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewMethod;

/** Keep authoritative rail height while recovering terrain tilt for railway vehicles only. */
public final class RailVehicleTerrainHooks {
    private RailVehicleTerrainHooks() { }

    public static void install(ClassPool pool) throws Exception {
        CtClass creature=pool.get("com.wurmonline.client.renderer.cell.CreatureCellRenderable");
        // Store signed angles independently of IRotFloat's 0..360 degree values.
        // No runtime helper or additional class-loader dependency is required.
        creature.addField(CtField.make("private boolean wurmRailTiltReady;",creature));
        creature.addField(CtField.make("private long wurmRailTiltNanos;",creature));
        creature.addField(CtField.make("private double wurmRailTiltPitch;",creature));
        creature.addField(CtField.make("private double wurmRailTiltRoll;",creature));
        creature.addMethod(CtNewMethod.make(
                "public boolean wurmRailIsRailVehicle() {"
                +" if(this.getModelName()==null)return false;"
                +" String name=this.getModelName().toString();"
                +" return name.equals(\"model.wurmrail.locomotive\")||name.startsWith(\"model.wurmrail.locomotive.\")"
                +" ||name.equals(\"model.wurmrail.cart\")||name.startsWith(\"model.wurmrail.cart.\")"
                +" ||name.equals(\"model.wurmrail.tankcar\")||name.startsWith(\"model.wurmrail.tankcar.\")"
                +" ||name.equals(\"model.wurmrail.logcar\")||name.startsWith(\"model.wurmrail.logcar.\"); }",creature));
        creature.addMethod(CtNewMethod.make(
                "public void wurmRailEaseTerrainTilt(long now) {"
                +" if(!this.wurmRailTiltReady)return;"
                +" long elapsed=now-this.wurmRailTiltNanos;"
                +" if(elapsed>0L){"
                // Many render/camera/seat getters can sample the same vehicle.
                // Elapsed time, rather than call count, controls the transition.
                // Bound catch-up after a render pause, while still progressing
                // if the client remains slow enough to exceed the pause limit.
                +"  this.wurmRailTiltNanos=now;"
                +"  if(elapsed>250000000L)elapsed=50000000L;"
                +"   double blend=-Math.expm1(-(double)elapsed/150000000.0);"
                +"   this.wurmRailTiltPitch+=(this.posTargets[4]-this.wurmRailTiltPitch)*blend;"
                +"   this.wurmRailTiltRoll+=(this.posTargets[5]-this.wurmRailTiltRoll)*blend;"
                +" }"
                +" if(!java.lang.Double.isFinite(this.wurmRailTiltPitch))this.wurmRailTiltPitch=this.posTargets[4];"
                +" if(!java.lang.Double.isFinite(this.wurmRailTiltRoll))this.wurmRailTiltRoll=this.posTargets[5];"
                +" this.interTickPos[4].reallySetValue((float)this.wurmRailTiltPitch);"
                +" this.interTickPos[5].reallySetValue((float)this.wurmRailTiltRoll);"
                +" }",creature));
        creature.addMethod(CtNewMethod.make(
                "public void wurmRailUpdateTerrainTilt(long now) {"
                +" if(!this.wurmRailIsRailVehicle()||this.posTargets==null||this.interTickPos==null)return;"
                +" this.pitchModel=true;this.rollModel=true;"
                +" if(!java.lang.Float.isFinite(this.posTargets[4]))this.posTargets[4]=0f;"
                +" if(!java.lang.Float.isFinite(this.posTargets[5]))this.posTargets[5]=0f;"
                +" if(!java.lang.Float.isFinite(this.interTickPos[4].getValue(0f)))this.interTickPos[4].reallySetValue(this.posTargets[4]);"
                +" if(!java.lang.Float.isFinite(this.interTickPos[5].getValue(0f)))this.interTickPos[5].reallySetValue(this.posTargets[5]);"
                +" try {"
                +" if(world==null)return;"
                +" float x=this.interTickPos[0].getValue(0f),y=this.interTickPos[1].getValue(0f);"
                +" float h=this.interTickPos[2].getValue(0f),yaw=this.interTickPos[3].getValue(0f);"
                +" if(!java.lang.Float.isFinite(x)||!java.lang.Float.isFinite(y)||!java.lang.Float.isFinite(h)||!java.lang.Float.isFinite(yaw))return;"
                +" float[] normal=this.terrainNormal;float floor;"
                +" if(normal==null)normal=new float[3];"
                +" com.wurmonline.client.renderer.structures.BridgePartData deck=null;"
                +" long bridge=this.getBridgeId();"
                +" if(bridge>0L){"
                +"  if(world.getCellRenderer()==null)return;"
                +"  int tx=(int)(x/4f),ty=(int)(y/4f);"
                +"  com.wurmonline.client.renderer.cell.Cell cell=world.getCellRenderer().findCellFromTile(tx,ty,this.getLayer());"
                +"  if(cell!=null)deck=cell.getBridgeAtSurfaceAt(tx,ty);"
                +"  if(deck==null||deck.getBridgeId()!=bridge)return;"
                +" }"
                +" if(deck!=null){floor=deck.getBridgeInterpolatedHeight(x,y);deck.getNormal(x,y,normal);}"
                +" else if(this.getLayer()<0) {"
                +"  com.wurmonline.client.game.CaveDataBuffer cave=world.getCaveBuffer();"
                +"  if(cave==null||!cave.isValid(x,y)||!cave.isFloor(x,y))return;"
                +"  floor=cave.getInterpolatedFloor(x,y);cave.getNormal(x,y,normal);"
                +" } else {"
                +"  com.wurmonline.client.game.NearTerrainDataBuffer surface=world.getNearTerrainBuffer();"
                +"  if(surface==null||!surface.isValid(x,y))return;"
                +"  floor=surface.getInterpolatedHeight(x,y);surface.getRealNormal(x,y,normal);"
                +" }"
                // The layer packet can arrive while XY still trails outside the mouth.
                // Keep the previous tilt rather than sampling a roof or an unloaded rock floor.
                +" if(!java.lang.Float.isFinite(floor)||Math.abs(floor-h)>1.0f)return;"
                +" if(!java.lang.Float.isFinite(normal[0])||!java.lang.Float.isFinite(normal[1])||!java.lang.Float.isFinite(normal[2])||normal[2]<=0.05f)return;"
                +" double ax=Math.toDegrees(Math.atan2((double)normal[0],(double)normal[2]));"
                +" double ay=Math.toDegrees(Math.atan2((double)normal[1],(double)normal[2]));"
                +" double alongX=Math.cos(Math.toRadians((double)(yaw-270f))),alongY=Math.cos(Math.toRadians((double)(yaw-180f)));"
                +" float pitch=(float)(-ax*alongX-ay*alongY),roll=(float)(ax*alongY-ay*alongX);"
                +" if(!java.lang.Float.isFinite(pitch)||!java.lang.Float.isFinite(roll))return;"
                +" this.posTargets[4]=Math.max(-45f,Math.min(45f,pitch));"
                +" this.posTargets[5]=Math.max(-45f,Math.min(45f,roll));"
                +" this.pitchOriginal=this.posTargets[4];this.rollOriginal=this.posTargets[5];"
                // Spawning or teleporting begins on the correct grade. Ordinary
                // terrain and layer transitions retain their existing smooth pose.
                +" if(!this.wurmRailTiltReady){"
                +"  this.wurmRailTiltPitch=this.posTargets[4];this.wurmRailTiltRoll=this.posTargets[5];"
                +"  this.wurmRailTiltNanos=now;this.wurmRailTiltReady=true;"
                +" }"
                +" } catch(java.lang.RuntimeException terrainChanging) { }"
                +" finally { this.wurmRailEaseTerrainTilt(now); }"
                +" }",creature));
        creature.addMethod(CtNewMethod.make(
                "public void wurmRailUpdateTerrainTilt() {"
                +" this.wurmRailUpdateTerrainTilt(System.nanoTime()); }",creature));
        creature.getDeclaredMethod("determineCreatureRotType").insertAfter(
                "{ if(this.wurmRailIsRailVehicle()){this.pitchModel=true;this.rollModel=true;} }");
        // Explicit-Z moves intentionally set onGround=false. Native code then supplies
        // an up normal and mounted models can reject tilt altogether; scope the override
        // here so avatars, carts from other mods, and native terrain behaviour stay intact.
        creature.getDeclaredMethod("setCreaturePitchRollTarget").insertBefore(
                "{ if(this.wurmRailIsRailVehicle()){this.wurmRailUpdateTerrainTilt();return;} }");
        // This smoother owns both tilt axes, including on a parked vehicle that
        // has never received a movement packet. Native tick filtering would add
        // a second, frame-dependent interpolation on top of the shared pose.
        creature.getDeclaredMethod("moveDOFtoTarget").insertBefore(
                "{ if(($1==4||$1==5)&&this.wurmRailTiltReady&&this.wurmRailIsRailVehicle())return true; }");
        creature.getDeclaredMethod("setPosImmediately").insertBefore(
                "{ if(this.wurmRailIsRailVehicle()){this.wurmRailTiltReady=false;this.wurmRailTiltNanos=0L;} }");
    }
}
