package org.wurmrail.client;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/** Replaces the native one-second target filter only for WurmRail vehicles. */
public final class RailVehicleMotionHooks {
    private RailVehicleMotionHooks() { }

    public static void install(ClassPool pool) throws Exception {
        // The native listener already turns compass headings into 180-heading.
        // The pure curve buffer uses compass headings; convert at both boundaries.
        CtClass vehicle=pool.get("com.wurmonline.client.renderer.cell.CreatureCellRenderable");
        vehicle.addField(CtField.make("private org.wurmrail.client.RailVehicleMotion wurmRailMotion;",vehicle));
        vehicle.addField(CtField.make("private boolean wurmRailHasExplicitHeight;",vehicle));
        vehicle.addField(CtField.make("private boolean wurmRailHasTimedMotion;",vehicle));
        vehicle.addField(CtField.make("private long wurmRailLastNativeMotion;",vehicle));
        vehicle.addField(CtField.make("private boolean wurmRailRefreshing;",vehicle));
        vehicle.addMethod(CtNewMethod.make(
                "public boolean wurmRailIsLocalController() {"
                + " return world!=null&&world.getPlayer()!=null&&world.getPlayer().isCarrierController()"
                + " &&world.getPlayer().getCarrierCreature()==this; }",vehicle));
        vehicle.addMethod(CtNewMethod.make(
                "public void wurmRailAcceptMotion(float x,float y,float h,float yaw,long now) {"
                + " if (!wurmRailIsRailVehicle() || this.carrierCreature != null) return;"
                + " this.wurmRailLastNativeMotion=now;"
                + " if (h != -3000f && java.lang.Float.isFinite(h)) this.wurmRailHasExplicitHeight=true;"
                + " if (this.wurmRailHasExplicitHeight) this.setOnGround(false);"
                + " if (this.wurmRailMotion == null) this.wurmRailMotion = new org.wurmrail.client.RailVehicleMotion("
                + " this.interTickPos[0].getValue(),this.interTickPos[1].getValue(),this.interTickPos[2].getValue(),"
                + " 180f-this.interTickPos[3].getValue(),now-50000000L);"
                + " this.wurmRailMotion.accept(x,y,h,180f-yaw,now); this.targetChanged=true; this.positionSet=true; }",vehicle));
        vehicle.addMethod(CtNewMethod.make(
                "public void wurmRailApplyMotion(long now) {"
                + " if (!wurmRailIsRailVehicle() || this.carrierCreature != null) return;"
                + " float[] p=org.wurmrail.client.RailConsistPlayback.sample(this.getId(),this.getLayer(),now,this.wurmRailLastNativeMotion);"
                + " this.wurmRailHasTimedMotion=p!=null;"
                + " if(p!=null)this.wurmRailHasExplicitHeight=true;"
                + " else { if(this.wurmRailMotion==null)return; p=this.wurmRailMotion.sample(now); }"
                + " for (int i=0;i<2;i++) this.interTickPos[i].reallySetValue(p[i]);"
                + " if (this.wurmRailHasExplicitHeight) {"
                + "  float h=org.wurmrail.client.RailDeckTilt.alignHeight(world,p[0],p[1],p[2],this.getLayer());"
                + "  this.interTickPos[2].reallySetValue(h); this.setOnGround(false); }"
                + " this.interTickPos[3].reallySetValue((180f-p[3]+360f)%360f);"
                + " this.targetMovementDiffX=0f; this.targetMovementDiffY=0f;"
                + " this.hasHPosBeenUpdated=true; this.positionUpdated=true; this.positionSet=true; }",vehicle));
        vehicle.addMethod(CtNewMethod.make(
                "public void wurmRailBeginRefresh(float x,float y,float h,float yaw,int layer,long now) {"
                + " if(!wurmRailIsRailVehicle()||this.wurmRailMotion==null||this.getLayer()!=layer)return;"
                + " if(h!=-3000f&&java.lang.Float.isFinite(h))this.wurmRailHasExplicitHeight=true;"
                + " this.wurmRailMotion.refresh(x,y,h,180f-yaw,now);this.wurmRailRefreshing=true; }",vehicle));
        vehicle.addMethod(CtNewMethod.make(
                "public void wurmRailEndRefresh() { this.wurmRailRefreshing=false; }",vehicle));
        installRiderPose(pool,vehicle);
        RailVehicleTerrainHooks.bindMotionClock(vehicle);
        pool.get("org.gotti.wurmunlimited.modcomm.ModCommHandler").getDeclaredMethod("startHandshake").insertBefore(
                "{ org.wurmrail.client.RailConsistPlayback.startHandshake(); }");
        // Native moveCreature replaces a local controller's received XY with
        // (0,0) before calling move. Capture the actual packet for presentation
        // before that substitution; native control/movement still runs normally.
        CtClass listener=pool.get("com.wurmonline.client.comm.ServerConnectionListenerClass");
        listener.getDeclaredMethod("moveCreature").insertBefore(
                "{ com.wurmonline.client.renderer.cell.CreatureCellRenderable rail="
                + " (com.wurmonline.client.renderer.cell.CreatureCellRenderable)this.creatures.get(new Long($1));"
                + " if(rail!=null&&rail.wurmRailIsRailVehicle()&&rail.wurmRailIsLocalController())"
                + " rail.wurmRailAcceptMotion($2,$3,$4,180f-$5*(360f/256f),System.nanoTime()); }");
        // Existing moving items are also reannounced for visibility/metadata.
        // Their delayed render position differs from the packet by design, so
        // native addTheCreature calls setPosImmediately even during normal travel.
        // Keep the packet's history while allowing all native metadata/targets
        // to update. True teleports and layer changes still reset playback.
        listener.getDeclaredMethod("addTheCreature").insertBefore(
                "{ com.wurmonline.client.renderer.cell.CreatureCellRenderable rail="
                + " (com.wurmonline.client.renderer.cell.CreatureCellRenderable)this.creatures.get(new Long($1));"
                + " if(rail!=null)rail.wurmRailBeginRefresh($6,$7,$8,180f-$9,$10,System.nanoTime()); }");
        listener.getDeclaredMethod("addTheCreature").insertAfter(
                "{ com.wurmonline.client.renderer.cell.CreatureCellRenderable rail="
                + " (com.wurmonline.client.renderer.cell.CreatureCellRenderable)this.creatures.get(new Long($1));"
                + " if(rail!=null)rail.wurmRailEndRefresh(); }",true);
        listener.getDeclaredMethod("deleteCreature").insertAfter(
                "{ org.wurmrail.client.RailConsistPlayback.forget($1); }");
        vehicle.getDeclaredMethod("move").insertAfter(
                "{ if(!wurmRailIsLocalController()) wurmRailAcceptMotion($1,$2,$3,$4,System.nanoTime()); }");
        vehicle.getDeclaredMethod("setPosImmediately").insertAfter(
                "{ if (wurmRailIsRailVehicle() && this.wurmRailMotion != null && !this.wurmRailRefreshing"
                + " && (!$4 || !wurmRailIsLocalController())) this.wurmRailMotion.reset("
                + " this.interTickPos[0].getValue(),this.interTickPos[1].getValue(),this.interTickPos[2].getValue(),"
                + " 180f-this.interTickPos[3].getValue(),System.nanoTime()); }");
        vehicle.getDeclaredMethod("moveDOFtoTarget").insertBefore(
                "{ if (($1<2 || $1==3 || ($1==2 && this.wurmRailHasExplicitHeight))"
                + " && (this.wurmRailHasTimedMotion || this.wurmRailMotion != null)"
                + " && wurmRailIsRailVehicle() && this.carrierCreature==null) return true; }");
        // The native bridge branch writes Z directly, bypassing moveDOFtoTarget.
        // Keep packet playback authoritative even when the bridge model is unloaded.
        vehicle.getDeclaredMethod("gameTick").instrument(new ExprEditor() {
            @Override public void edit(MethodCall call) throws javassist.CannotCompileException {
                if(call.getMethodName().equals("getBridgeHeight"))
                    call.replace("{ $_=this.wurmRailHasExplicitHeight&&this.wurmRailIsRailVehicle()?0f:$proceed($$); }");
            }
        });
        vehicle.getDeclaredMethod("gameTick").insertBefore(
                // Sampling advances and prunes the playback buffer. A fresh
                // per-car clock here cannot be rewound to the earlier shared
                // render time after a slow tick or a passenger-first update.
                "{ long now=wurmRailRenderTime(); wurmRailApplyMotion(now);"
                + " if (wurmRailIsRailVehicle()) wurmRailUpdateTerrainTilt(now);"
                + " if (this.carrierCreature != null && this.carrierCreature.wurmRailIsRailVehicle()) {"
                + " this.carrierCreature.wurmRailApplyMotion(now); this.carrierCreature.wurmRailUpdateTerrainTilt(now);"
                + " this.carrierCreature.setRenderPos(0f); } }");
        String frame="{ long now=wurmRailRenderTime(); wurmRailApplyMotion(now);"
                + " if (wurmRailIsRailVehicle()) wurmRailUpdateTerrainTilt(now);"
                + " if (this.carrierCreature != null) { this.carrierCreature.wurmRailApplyMotion(now);"
                + " if (this.carrierCreature.wurmRailIsRailVehicle()) {"
                + " this.carrierCreature.wurmRailUpdateTerrainTilt(now); wurmRailUpdateRiderSeat(now); } } }";
        vehicle.getDeclaredMethod("setRenderPos").insertBefore(frame);
        for(String getter:new String[]{"getXPosValue","getYPosValue","getHPosValue","getYawValue"})
            vehicle.getDeclaredMethod(getter).insertBefore(frame);
    }

    private static void installRiderPose(ClassPool pool,CtClass vehicle) throws Exception {
        // Native tickFrame sets the World interpolation fraction once after its
        // game ticks and before rendering. Reuse that timestamp throughout the
        // camera, seats and vehicle draw, even when preparing a frame is slow.
        CtClass world=pool.get("com.wurmonline.client.game.World");
        world.addField(CtField.make("private long wurmRailFrameNanos;",world));
        world.addMethod(CtNewMethod.make(
                "public long wurmRailRenderTime() {"
                + " return this.wurmRailFrameNanos==0L?System.nanoTime():this.wurmRailFrameNanos; }",world));
        world.getDeclaredMethod("setSecondsPlayed").insertBefore(
                "{ this.wurmRailFrameNanos=System.nanoTime(); }");
        world.getDeclaredMethod("clear").insertBefore(
                "{ this.wurmRailFrameNanos=0L; org.wurmrail.client.RailConsistPlayback.clear(); }");
        vehicle.addMethod(CtNewMethod.make(
                "public long wurmRailRenderTime() {"
                + " return world==null?System.nanoTime():world.wurmRailRenderTime(); }",vehicle));
        // Sample one carrier pose for both native seat representations: yaw-only
        // translation plus a separate tilt correction. WorldRender adds the two
        // for the local camera; remote avatars use the complete rotated offset.
        // Keep native roll/pitch/yaw rotation order and the supplied seat origin.
        vehicle.addMethod(CtNewMethod.make(
                "public float[] wurmRailSampleSeat(float x,float y,float h,long now) {"
                + " wurmRailApplyMotion(now); wurmRailUpdateTerrainTilt(now);"
                + " float phi=(float)Math.toRadians((double)this.interTickPos[3].getValue());"
                + " com.wurmonline.client.renderer.RenderVector flat=new com.wurmonline.client.renderer.RenderVector(x,y,h);"
                + " flat.rotateZ((double)(phi+1.5707964f));"
                + " com.wurmonline.client.renderer.RenderVector tilted=new com.wurmonline.client.renderer.RenderVector(x,y,h);"
                + " tilted.rotateXsmallangle(Math.toRadians((double)this.interTickPos[5].getValue()));"
                + " tilted.rotateYsmallangle(Math.toRadians((double)this.interTickPos[4].getValue()));"
                + " tilted.rotateZ((double)(phi+1.5707964f));"
                + " return new float[]{flat.getX(),flat.getY(),flat.getZ(),tilted.getX(),tilted.getY(),tilted.getZ(),"
                + " this.interTickPos[0].getValue(),this.interTickPos[1].getValue(),this.interTickPos[2].getValue()}; }",vehicle));
        vehicle.addMethod(CtNewMethod.make(
                "public void wurmRailUpdateRiderSeat(long now) {"
                + " if(this.carrierCreature==null||this.hitched||this.offsets==null"
                + " ||!this.carrierCreature.wurmRailIsRailVehicle())return;"
                + " float[] seat=this.carrierCreature.wurmRailSampleSeat(this.offsets[0],this.offsets[1],this.offsets[2],now);"
                // Do not interpolate a previous tick's seat against a carrier
                // already sampled at render time. Both must share this pose.
                + " for(int axis=0;axis<3;axis++)this.interTickOffset[axis].reallySetValue(seat[axis+3]); }",vehicle));

        // A loaded NullMount replaces PlayerObj's numeric seat offsets with
        // zeros. WorldRender applies that model-space mount later, before it
        // prepares vehicle models. Its native path uses the previous cached yaw
        // and omits pitch/roll; sample the same complete pose as the car draw.
        vehicle.addMethod(CtNewMethod.make(
                "public boolean wurmRailIsLocalMount(com.wurmonline.client.renderer.cell.MountOffsets mount) {"
                + " return wurmRailIsRailVehicle()&&world!=null&&world.getPlayer()!=null"
                + " &&world.getPlayer().getCarrierCreature()==this&&world.getPlayer().getPlayerBody()!=null"
                + " &&world.getPlayer().getPlayerBody().getMountOffset()==mount; }",vehicle));
        vehicle.addMethod(CtNewMethod.make(
                "public float[] wurmRailSampleMount(float x,float h,float y) {"
                + " long now=wurmRailRenderTime();wurmRailApplyMotion(now);wurmRailUpdateTerrainTilt(now);"
                + " setRenderPos(world==null?0f:world.getTickFraction());"
                + " com.wurmonline.math.Quaternion rotation=new com.wurmonline.math.Quaternion();"
                + " rotation.fromAngles(0f,(float)Math.toRadians((double)this.interTickPos[3].getValue()),0f);"
                + " com.wurmonline.math.Quaternion axis=new com.wurmonline.math.Quaternion();"
                + " axis.fromAngles((float)Math.toRadians((double)this.interTickPos[4].getValue()),0f,0f);rotation.mult(axis);"
                + " axis.fromAngles(0f,0f,(float)Math.toRadians((double)this.interTickPos[5].getValue()));rotation.mult(axis);"
                + " com.wurmonline.math.Vector3f offset=new com.wurmonline.math.Vector3f(x,h,y);"
                + " rotation.rotate(offset,offset);return new float[]{offset.x,offset.y,offset.z}; }",vehicle));
        CtClass mount=pool.get("com.wurmonline.client.renderer.cell.MountOffsets");
        mount.getDeclaredMethod("addRotatedMountOffset").insertBefore(
                "{ if(this.hasCarrierCreatureMountJoint()&&$2!=null&&$2.wurmRailIsLocalMount(this)) {"
                + " float[] offset=$2.wurmRailSampleMount(getMountOffsetX(),getMountOffsetH(),getMountOffsetY());"
                + " float sign=$3?-1f:1f;"
                // Reflection flips world height after the full seat rotation.
                + " $1.translation.addLocal(sign*offset[0],($4?-sign:sign)*offset[1],sign*offset[2]);return; } }");

        CtClass player=pool.get("com.wurmonline.client.game.PlayerObj");
        // Wurm repositions a carrier when setting up the local mount and when
        // applying a player correction. Only a vehicle packet can reset the
        // authoritative rail path; the native player targets still update.
        vehicle.addMethod(CtNewMethod.make(
                "public void wurmRailSetMountPosition(float x,float y,float h,boolean smooth,boolean ground) {"
                + " boolean previous=this.wurmRailRefreshing;this.wurmRailRefreshing=true;"
                + " try { setPosImmediately(x,y,h,smooth,ground); }"
                + " finally { this.wurmRailRefreshing=previous; } }",vehicle));
        for (String method : new String[]{"setController","teleportTo"})
            player.getDeclaredMethod(method).instrument(new ExprEditor() {
                @Override public void edit(MethodCall call) throws javassist.CannotCompileException {
                    if (call.getMethodName().equals("setPosImmediately"))
                        call.replace("{ if($0.wurmRailIsRailVehicle())$0.wurmRailSetMountPosition($$); else $proceed($$); }");
                }
            });
        player.addMethod(CtNewMethod.make(
                "public boolean wurmRailIsRailRider() {"
                + " return this.carrierCreature!=null&&this.carrierCreature.wurmRailIsRailVehicle(); }",player));
        player.addMethod(CtNewMethod.make(
                "public float wurmRailRiderCoordinate(int axis,boolean correction) {"
                + " long now=this.worldused==null?System.nanoTime():this.worldused.wurmRailRenderTime();"
                + " float[] seat=this.carrierCreature.wurmRailSampleSeat(this.xOffset,this.yOffset,this.hOffset,now);"
                + " if(correction)return seat[axis+3]-seat[axis];"
                + " return seat[axis+6]+seat[axis]; }",player));
        String[] axes={"X","Y","H"};
        for(int axis=0;axis<axes.length;axis++) {
            player.getDeclaredMethod("get"+axes[axis]).insertBefore(
                    "{ if(wurmRailIsRailRider())return wurmRailRiderCoordinate("+axis+",false)"
                    +(axis==2?"+this.cameraHeightOffSet":"")+"; }");
            player.getDeclaredMethod("getCarrier"+axes[axis]+"PosOffset").insertBefore(
                    "{ if(wurmRailIsRailRider())return wurmRailRiderCoordinate("+axis+",true); }");
        }
        // Render the seat from rail playback. World position still drives
        // native tile queries, streaming, collision and player movement.
        world.addMethod(CtNewMethod.make(
                "public float wurmRailCameraCoordinate(int axis,float nativePosition) {"
                + " com.wurmonline.client.game.PlayerObj rider=this.getPlayer();"
                + " return rider!=null&&rider.wurmRailIsRailRider()"
                + " ?rider.wurmRailRiderCoordinate(axis,false):nativePosition; }",world));
        ExprEditor railCamera = new ExprEditor() {
            @Override public void edit(MethodCall call) throws javassist.CannotCompileException {
                if (!call.getClassName().equals("com.wurmonline.client.game.World")) return;
                for (int axis=0;axis<axes.length;axis++)
                    if (call.getMethodName().equals("getPlayerPos"+axes[axis])) {
                        call.replace("{ $_=$0.wurmRailCameraCoordinate("+axis+",$proceed($$)); }");
                        return;
                    }
            }
        };
        pool.get("com.wurmonline.client.renderer.WorldRender").getDeclaredMethod("prepareRender").instrument(railCamera);
        CtClass body = pool.get("com.wurmonline.client.renderer.PlayerBodyRenderable");
        for (String axis : axes)
            body.getDeclaredMethod("get"+axis+"PosValue").instrument(railCamera);
    }
}
