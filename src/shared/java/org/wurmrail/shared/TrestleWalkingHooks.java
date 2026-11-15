package org.wurmrail.shared;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/** Add the deck to Wurm's on-foot height sampling on both sides of the movement protocol. */
public final class TrestleWalkingHooks {
    private TrestleWalkingHooks() { }

    public static void install(ClassPool pool, String subclass, String sampler) throws Exception {
        CtClass movement = pool.get("com.wurmonline.shared.util.MovementChecker");
        movement.addMethod(CtNewMethod.make("protected float wurmRailWalkingDeck(float x,float y,float feet)"
                + " { return java.lang.Float.NaN; }", movement));
        CtClass walker = pool.get(subclass);
        walker.addMethod(CtNewMethod.make("protected float wurmRailWalkingDeck(float x,float y,float feet)"
                + " { " + sampler + " }", walker));

        CtClass[] heightArgs = {CtClass.floatType, CtClass.floatType, CtClass.floatType};
        movement.getDeclaredMethod("getHeight", heightArgs).insertAfter(
                "{ if(this.layer==0 && this.bridgeId<=0L && !this.isOnFloor() && !this.flying) {"
                + " float deck=this.wurmRailWalkingDeck($1,$2,this.z);"
                + " if(deck>$_)$_=deck; } }");

        // A supported downhill step follows the planks continuously. Otherwise
        // the native gravity step repeatedly leaves the walker above the deck.
        movement.addMethod(CtNewMethod.make("private void wurmRailFollowDeck() {"
                + " if(this.layer!=0 || this.bridgeId>0L || this.isOnFloor() || this.flying || this.offZ!=0f"
                + " || this.za < -0.08f) return;"
                + " float deck=this.wurmRailWalkingDeck(this.x,this.y,this.zOld);"
                + " if(!java.lang.Float.isFinite(deck) || deck < -1.45f"
                + " || deck < this.getHeight(this.x,this.y,-3000f,0)) return;"
                + " float oldDeck=this.wurmRailWalkingDeck(this.xOld,this.yOld,this.zOld);"
                + " float oldGround=this.getHeight(this.xOld,this.yOld,-3000f,0);"
                + " float support=oldDeck>oldGround ? oldDeck : oldGround;"
                + " if(java.lang.Math.abs(this.zOld-support)<=.075f) { this.z=deck; this.za=0f; }"
                + " }", movement));

        // The terrain below can be a steep cliff. Check its slope only where
        // the predicted footstep lacks a reachable deck.
        movement.getDeclaredMethod("movestep").instrument(new ExprEditor() {
            @Override public void edit(MethodCall call) throws javassist.CannotCompileException {
                if ("getTileSteepness".equals(call.getMethodName()))
                    call.replace("{ if($3==0 && this.bridgeId<=0L && !this.isOnFloor() && !this.flying"
                            + " && java.lang.Float.isFinite(this.wurmRailWalkingDeck(this.x+this.xa,this.y+this.ya,this.z)))"
                            + " $_=0f; else $_=$proceed($$); }");
                else if ("updateGroundOffset".equals(call.getMethodName()))
                    call.replace("{ $proceed($$); this.wurmRailFollowDeck(); }");
            }
        });
    }
}
