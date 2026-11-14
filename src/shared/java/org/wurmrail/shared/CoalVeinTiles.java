package org.wurmrail.shared;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewMethod;
import javassist.Modifier;
import javassist.NotFoundException;

/** Fixed map identity shared by the client and server; safe before game classes load. */
public final class CoalVeinTiles {
    public static final int TILE_ID=208;
    public static final byte TILE_TYPE=(byte)TILE_ID;
    public static final String ENUM_NAME="TILE_WURMRAIL_COAL_VEIN";
    public static final String DISPLAY_NAME="Coal Vein";
    /** Reuse Wurm's dark rock material while keeping a distinct, named resource tile. */
    public static final String TEXTURE="img.texture.cave.slate";
    private static final String TILE_CLASS="com.wurmonline.mesh.Tiles$Tile";
    private static final String FACTORY="wurmrailCreateCoalVein";
    private CoalVeinTiles(){ }

    public static boolean isCoal(byte type){return (type&255)==TILE_ID;}
    public static boolean isCoal(int type){return type==TILE_ID;}

    /** Append a real enum value; never rename, replace or renumber a vanilla tile. */
    public static synchronized void install(ClassPool pool) {
        try {
            CtClass tile=pool.get(TILE_CLASS),tiles=pool.get("com.wurmonline.mesh.Tiles");
            try {
                tile.getDeclaredField(ENUM_NAME);
                tile.getDeclaredMethod(FACTORY);
                return;
            }catch(NotFoundException missing){ }
            if(tile.isFrozen()||tiles.isFrozen())throw new IllegalStateException(
                    "Coal Vein tile registration must run before Wurm's Tiles classes load. Check mod load order.");
            CtField field=new CtField(tile,ENUM_NAME,tile);
            field.setModifiers(Modifier.PUBLIC|Modifier.STATIC|Modifier.FINAL);
            // Enum metadata lets reflection and native enum-based controls see the new value.
            field.getFieldInfo().setAccessFlags(field.getFieldInfo().getAccessFlags()|0x4000);
            tile.addField(field);
            tile.addMethod(CtNewMethod.make("private static "+TILE_CLASS+" "+FACTORY+"() {"
                    +" if ("+TILE_CLASS+".getTiles()["+TILE_ID+"] != null) throw new java.lang.IllegalStateException("
                    +"\"WurmRail Coal Vein requires unused tile ID 208; another tile/mod already occupies that ID.\");"
                    +" return new "+TILE_CLASS+"(\""+ENUM_NAME+"\", $VALUES.length, "+TILE_ID
                    +", \""+DISPLAY_NAME+"\", \"#242424\", 0.001f, \""+TEXTURE+"\","
                    +" new com.wurmonline.mesh.Tiles$Flag[]{com.wurmonline.mesh.Tiles$Flag.CAVE, com.wurmonline.mesh.Tiles$Flag.ORECAVE},"
                    +" 60, (byte)0, (byte)1, (byte)1); }",tile));
            // Assign final fields only inside their declaring class initializer.
            // The native constructor also registers the new object in Tiles.tiles[208].
            tile.makeClassInitializer().insertAfter("{ "+ENUM_NAME+"="+FACTORY+"(); "
                    +TILE_CLASS+"[] coalValues=new "+TILE_CLASS+"[$VALUES.length+1];"
                    +" java.lang.System.arraycopy($VALUES,0,coalValues,0,$VALUES.length);"
                    +" coalValues[$VALUES.length]="+ENUM_NAME+"; $VALUES=coalValues; }");
            for(String method:new String[]{"isSolidCave","isOreCave","isReinforcedCave"})
                tiles.getDeclaredMethod(method,new CtClass[]{CtClass.byteType}).insertBefore(
                        "{ if (($1 & 255)=="+TILE_ID+") return true; }");
        }catch(Exception failure){throw new IllegalStateException("Could not install WurmRail Coal Vein tile 208",failure);}
    }
}
