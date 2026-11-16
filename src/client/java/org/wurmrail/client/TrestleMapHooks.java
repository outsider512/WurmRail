package org.wurmrail.client;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;

/** Treat the survey's map label as a native component when the client supports it. */
public final class TrestleMapHooks {
    private TrestleMapHooks() { }

    public static void install(ClassPool pool) throws Exception {
        CtClass label = pool.get("com.wurmonline.client.renderer.gui.WurmLabel");
        label.addField(CtField.make("org.wurmrail.client.ClientTrestleMap wurmRailMap;", label));
        label.getDeclaredMethod("renderComponent").insertBefore("{ if(this.wurmRailMap!=null) {"
                + " this.wurmRailMap.render($1,this.text,this.x,this.y,this.width,this.height); return; } }");
        CtClass bml = pool.get("com.wurmonline.client.renderer.gui.BmlWindowComponent");
        bml.getDeclaredMethod("parse2").insertBefore("{ if(\"label\".equals($1.getTagName()) && $1.hasAttribute(\"trestlemap\")) {"
                + " org.wurmrail.client.ClientTrestleMap map=org.wurmrail.client.ClientTrestleMap.decode($1.getAttribute(\"trestlemap\"));"
                + " if(map!=null) { com.wurmonline.client.renderer.gui.WurmLabel label="
                + " new com.wurmonline.client.renderer.gui.WurmLabel(\"\"); label.wurmRailMap=map;"
                + " label.setSize(512,192); label.sizeFlags=2; return label; } } }");
    }
}
