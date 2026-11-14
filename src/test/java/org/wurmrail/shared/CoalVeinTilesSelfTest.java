package org.wurmrail.shared;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.Loader;

/** Isolated native registry copies prove that a map identity is never stolen. */
public final class CoalVeinTilesSelfTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        ClassPool clean=new ClassPool(true);CoalVeinTiles.install(clean);CoalVeinTiles.install(clean);
        Class<?> tiles=Class.forName("com.wurmonline.mesh.Tiles",true,new Loader(clean));
        Object coal=tiles.getMethod("getTile",int.class).invoke(null,208);
        check(coal!=null&&"Coal Vein".equals(coal.getClass().getMethod("getName").invoke(coal)),"idempotent registration initializes one actual native Coal Vein");
        Object[] values=(Object[])coal.getClass().getMethod("values").invoke(null);
        int matches=0;for(Object value:values)if(value==coal)matches++;
        check(matches==1&&values[values.length-1]==coal,"repeated installation cannot append duplicate enum values");
        check(tiles.getMethod("getTile",int.class).invoke(null,222)!=coal,"vanilla iron remains a separate registry entry");

        ClassPool occupied=new ClassPool(true);CtClass enumType=occupied.get("com.wurmonline.mesh.Tiles$Tile");
        enumType.makeClassInitializer().insertAfter("{ getTiles()[208]=TILE_CAVE_WALL_ORE_IRON; }");
        CoalVeinTiles.install(occupied);Throwable conflict=null;
        try{Class<?> nativeTiles=Class.forName("com.wurmonline.mesh.Tiles",true,new Loader(occupied));nativeTiles.getMethod("getTile",int.class).invoke(null,208);}catch(Throwable failure){conflict=failure;}
        check(conflict!=null&&messages(conflict).contains("already occupies"),"an occupied tile ID fails explicitly instead of replacing another resource");

        ClassPool late=new ClassPool(true);late.get("com.wurmonline.mesh.Tiles").freeze();boolean rejected=false;
        try{CoalVeinTiles.install(late);}catch(IllegalStateException failure){rejected=messages(failure).contains("before Wurm's Tiles classes load");}
        check(rejected,"late installation fails clearly while game classes still retain their vanilla bytes");
        check(CoalVeinTiles.isCoal(208)&&CoalVeinTiles.isCoal((byte)208)&&!CoalVeinTiles.isCoal(222),"map integer and signed wire-byte predicates agree");
        System.out.println("CoalVeinTilesSelfTest: "+checks+" assertions passed; real enum append, fixed-ID conflict rejection, idempotence and load-order guards.");
    }
    private static String messages(Throwable failure){StringBuilder out=new StringBuilder();for(Throwable current=failure;current!=null;current=current.getCause())out.append(current.getMessage()).append('\n');return out.toString();}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
