package org.wurmrail.client;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.wurmrail.shared.TrestleWalkingHooks;

public final class TrestleWalkingClientHooks {
    private TrestleWalkingClientHooks() { }

    public static void install(ClassPool pool) throws Exception {
        String surface = "org.wurmrail.shared.TrestleDeckSurface";
        String panel = "org.wurmrail.shared.TrestlePanel";
        CtClass packet = pool.get("com.wurmonline.client.renderer.GroundItemData");
        packet.addField(CtField.make("private int wurmRailDeckAxis=-1;",packet));
        packet.addField(CtField.make("private int wurmRailDeckGrade;",packet));
        packet.addField(CtField.make("private " + panel + " wurmRailPanel;",packet));
        packet.getDeclaredConstructors()[0].insertAfter(
                "{ int axis=" + surface + ".modelAxis($2); if(axis>=0) {"
                + " try { this.wurmRailDeckGrade=" + surface + ".modelGrade($2); this.wurmRailDeckAxis=axis;"
                + " } catch(java.lang.RuntimeException malformedDeck) {} }"
                + " this.wurmRailPanel=" + panel + ".fromModel($2);"
                + " if((this.wurmRailDeckAxis>=0 || this.wurmRailPanel!=null) && $14==0 && $19<=0L && $21==-10L) this.scale=1f; }");
        packet.addMethod(CtNewMethod.make("public int wurmRailDeckAxis() { return this.wurmRailDeckAxis; }",packet));
        packet.addMethod(CtNewMethod.make("public int wurmRailDeckGrade() { return this.wurmRailDeckGrade; }",packet));
        packet.addMethod(CtNewMethod.make("public " + panel + " wurmRailCurvedPanel() { return this.wurmRailPanel; }",packet));

        CtClass matrix = pool.get("com.wurmonline.client.renderer.Matrix");
        matrix.addMethod(CtNewMethod.make("public void wurmRailSlope(float x,float y) {"
                + " this.m01+=x*this.m00+y*this.m02; this.m11+=x*this.m10+y*this.m12;"
                + " this.m21+=x*this.m20+y*this.m22; }",matrix));
        CtClass scenery = pool.get("com.wurmonline.client.renderer.cell.StaticModelRenderable");
        scenery.addMethod(CtNewMethod.make("protected void wurmRailDeckLoaded() {}",scenery));
        scenery.addMethod(CtNewMethod.make("protected void wurmRailDeckBounds() {}",scenery));
        scenery.getDeclaredMethod("modelLoaded").insertAfter("{ this.wurmRailDeckLoaded(); }");
        scenery.getDeclaredMethod("setVolumeFromModel").insertAfter("{ this.wurmRailDeckBounds(); }");
        CtClass ground = pool.get("com.wurmonline.client.renderer.cell.GroundItemCellRenderable");
        ground.addMethod(CtNewMethod.make("public com.wurmonline.client.renderer.GroundItemData"
                + " wurmRailGroundItem() { return this.item; }", ground));
        ground.addMethod(CtNewMethod.make("protected boolean wurmRailUsesDeckMatrix() {"
                + " return this.item!=null && this.item.wurmRailDeckAxis()>=0 && this.item.getLayer()==0"
                + " && this.item.getParent()==-10L && this.item.getBridgeId()<=0L; }",ground));
        ground.addMethod(CtNewMethod.make("protected " + panel + " wurmRailCurvedPanel() {"
                + " return this.item!=null && this.item.getLayer()==0 && this.item.getParent()==-10L"
                + " && this.item.getBridgeId()<=0L ? this.item.wurmRailCurvedPanel() : null; }",ground));
        ground.addMethod(CtNewMethod.make("protected void wurmRailDeckLoaded() {"
                + " if(this.wurmRailUsesDeckMatrix() || this.wurmRailCurvedPanel()!=null) this.updateModelMatrix(); }",ground));
        String slopes = "float sx=" + surface + ".slopeX(this.item.wurmRailDeckAxis(),this.item.wurmRailDeckGrade());"
                + " float sy=" + surface + ".slopeY(this.item.wurmRailDeckAxis(),this.item.wurmRailDeckGrade());";
        ground.getDeclaredMethod("updateModelMatrix").insertBefore("{ if(this.wurmRailUsesDeckMatrix() || this.wurmRailCurvedPanel()!=null) {"
                + " this.setOffsetScale(0f); this.h=this.item.getH(); this.pitch=0f; this.roll=0f; } }");
        ground.getDeclaredMethod("updateModelMatrix").insertAfter("{ if(this.wurmRailUsesDeckMatrix()) {"
                + slopes + " this.worldModel.wurmRailSlope(sx,sy); this.renderModel.wurmRailSlope(sx,sy); } }");
        ground.addMethod(CtNewMethod.make("protected void wurmRailDeckBounds() {"
                + " if(!this.wurmRailUsesDeckMatrix() || !this.isLoaded()) return;"
                + slopes
                + " com.wurmonline.client.renderer.cell.Volume v=this.getVolume();"
                + " float rise=(java.lang.Math.abs(sx)*v.getSizeX()+java.lang.Math.abs(sy)*v.getSizeY())*.5f;"
                + " v.set(v.getX0(),v.getH0()-rise,v.getY0(),v.getX1(),v.getH1()+rise,v.getY1()); }",ground));

        CtClass renderer = pool.get("com.wurmonline.client.renderer.cell.CellRenderer");
        renderer.addField(CtField.make("private final org.wurmrail.client.TrestleWalkingDecks wurmRailDecks"
                + " = new org.wurmrail.client.TrestleWalkingDecks();", renderer));
        String item = "((com.wurmonline.client.renderer.cell.GroundItemCellRenderable)$1).wurmRailGroundItem()";
        String groundItem = "$1 instanceof com.wurmonline.client.renderer.cell.GroundItemCellRenderable";
        renderer.getDeclaredMethod("addRenderable").insertAfter("{ if(" + groundItem
                + ") { com.wurmonline.client.renderer.GroundItemData item=" + item + ";"
                + " this.wurmRailDecks.add(item,item.wurmRailDeckAxis(),item.wurmRailDeckGrade(),item.wurmRailCurvedPanel()); } }");
        renderer.getDeclaredMethod("removeRenderable").insertBefore("{ if(" + groundItem
                + ") { com.wurmonline.client.renderer.GroundItemData item=" + item + ";"
                + " if(item.wurmRailDeckAxis()>=0 || item.wurmRailCurvedPanel()!=null) this.wurmRailDecks.remove(item); } }");
        renderer.getDeclaredMethod("clear").insertAfter("{ this.wurmRailDecks.clear(); }");
        renderer.addInterface(pool.get("org.wurmrail.shared.TrestleDeckSurface$TileHeight"));
        renderer.addMethod(CtNewMethod.make("public float sample(int tx,int ty,float x,float y,float feet) {"
                + " return this.wurmRailDecks.height(tx,ty,x,y,feet); }", renderer));

        TrestleWalkingHooks.install(pool, "com.wurmonline.client.game.PlayerMovementCheck",
                "if(this.world.getPlayer()==null || this.world.getPlayer().getCarrierCreature()!=null"
                + " || this.world.getCellRenderer()==null) return java.lang.Float.NaN;"
                + " return org.wurmrail.shared.TrestleDeckSurface.find(x,y,feet,this.world.getCellRenderer());");

        // The graphics client's collision solver can adjust XY after the
        // shared movement check. Use the deck height at the final footstep.
        CtClass movement = pool.get("com.wurmonline.client.game.PlayerMovementCheck");
        movement.addMethod(CtNewMethod.make("public float wurmRailCollisionHeight(float x,float y,float height) {"
                + " if(this.isFlying() || this.getBridgeId()>0L || this.isOnFloor()) return height;"
                + " float feet=this.getZ();"
                + " float predicted=this.wurmRailWalkingDeck(this.getX(),this.getY(),feet);"
                + " if(!(java.lang.Math.abs(predicted-feet)<=.075f)) return height;"
                + " float actual=this.wurmRailWalkingDeck(x,y,feet);"
                + " return java.lang.Math.abs(actual-feet)<=.75f ? java.lang.Math.max(height,actual) : height; }",movement));
        CtClass player = pool.get("com.wurmonline.client.game.PlayerObj");
        player.getDeclaredMethod("gametick").instrument(new ExprEditor() {
            @Override public void edit(MethodCall call) throws javassist.CannotCompileException {
                if ("getObjectPosition".equals(call.getMethodName())
                        && "com.wurmonline.client.collision.CollisionManager".equals(call.getClassName()))
                    call.replace("{ $_=$proceed($$); if($_!=null && $1==-1L && this.getLayer()==0) {"
                            + " float height=this.movementChecker.wurmRailCollisionHeight($_.x,$_.y,$_.z);"
                            + " if(height!=$_.z) $_=new javax.vecmath.Vector3f($_.x,$_.y,height); } }");
            }
        });
    }
}
