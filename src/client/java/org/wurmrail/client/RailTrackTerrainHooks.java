package org.wurmrail.client;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewMethod;

/** Terrain-conforming railway scenery with unchanged horizontal tile boundaries. */
public final class RailTrackTerrainHooks {
    private RailTrackTerrainHooks() { }
    public static void install(ClassPool pool) throws Exception {
        CtClass loader=pool.get("com.wurmonline.client.renderer.model.ModelResourceLoader");
        loader.addField(CtField.make("private static final java.util.Map wurmRailTerrainModels=new java.util.HashMap();",loader));
        loader.addField(CtField.make("private static final java.lang.ref.ReferenceQueue wurmRailTerrainQueue=new java.lang.ref.ReferenceQueue();",loader));
        loader.addMethod(CtNewMethod.make("public static synchronized void wurmRailStoreTerrainCopy("
                +" com.wurmonline.client.renderer.model.AbstractModelData data,com.wurmonline.client.renderer.model.ModelResourceWrapper wrapper) {"
                +" wurmRailTerrainModels.put(new java.lang.ref.WeakReference(wrapper,wurmRailTerrainQueue),data); }",loader));
        // Native clone() also replaces the URL cache. Instance-specific geometry
        // must have its own weak cleanup queue without entering that lookup.
        loader.getDeclaredMethod("unload").insertBefore("{ java.lang.ref.Reference railReference;"
                +" while((railReference=wurmRailTerrainQueue.poll())!=null){"
                +" com.wurmonline.client.renderer.model.AbstractModelData railData="
                +" (com.wurmonline.client.renderer.model.AbstractModelData)wurmRailTerrainModels.remove(railReference);"
                +" if(railData!=null)railData.unload();} }");
        CtClass wrapper=pool.get("com.wurmonline.client.renderer.model.ModelResourceWrapper");
        wrapper.addField(CtField.make("private com.wurmonline.client.renderer.model.ModelResourceWrapper wurmRailTerrainSource;",wrapper));
        wrapper.addMethod(CtNewMethod.make("public boolean wurmRailIsPrivateTerrainCopy() {return this.wurmRailTerrainSource!=null;}",wrapper));
        wrapper.addMethod(CtNewMethod.make("public com.wurmonline.client.renderer.model.ModelResourceWrapper wurmRailPrivateTerrainCopy() {"
                +" com.wurmonline.client.renderer.model.AbstractModelData data=this.modelData.getCopy();"
                +" com.wurmonline.client.renderer.model.ModelResourceWrapper copy=new com.wurmonline.client.renderer.model.ModelResourceWrapper(this.resourceUrl,data);"
                +" copy.wurmRailTerrainSource=this;"
                +" com.wurmonline.client.renderer.model.ModelResourceLoader.wurmRailStoreTerrainCopy(data,copy);return copy; }",wrapper));
        CtClass mesh=pool.get("com.wurmonline.client.renderer.model.collada.importer.TriangleMesh");
        mesh.addField(CtField.make("private boolean wurmRailOwnsTerrainBuffers;",mesh));
        mesh.addMethod(CtNewMethod.make("public void wurmRailOwnTerrainBuffers() {"
                +" if(this.wurmRailOwnsTerrainBuffers)return;"
                +" java.nio.FloatBuffer positions=com.wurmonline.client.util.BufferUtil.clone(this.vertBuffer.buffer);"
                +" java.nio.FloatBuffer normals=this.normalBuffer==null?null:com.wurmonline.client.util.BufferUtil.clone(this.normalBuffer.buffer);"
                +" this.vertBuffer.release();this.vertBuffer=new com.wurmonline.client.util.SharedFloatBuffer(positions);"
                +" if(this.normalBuffer!=null){this.normalBuffer.release();this.normalBuffer=new com.wurmonline.client.util.SharedFloatBuffer(normals);}"
                +" this.allowSlim=false;this.freeVBO();this.wurmRailOwnsTerrainBuffers=true; }",mesh));
        CtClass data=pool.get("com.wurmonline.client.renderer.model.collada.AbstractColladaModelData");
        data.addMethod(CtNewMethod.make("public void wurmRailRefreshTerrainBounds() {"
                +" this.bounds=this.model.calculateBoundingBox();this.pickingBound=this.model.calculatePickingBox();"
                +" if(this.pickingBound==null)this.pickingBound=new com.wurmonline.client.renderer.model.ModelDataBounds(this.bounds); }",data));
        CtClass scenery=pool.get("com.wurmonline.client.renderer.cell.StaticModelRenderable");
        scenery.addMethod(CtNewMethod.make("protected boolean wurmRailUsesDeckMatrix() { return false; }",scenery));
        scenery.addMethod(CtNewMethod.make("protected org.wurmrail.shared.TrestlePanel wurmRailCurvedPanel() { return null; }",scenery));
        scenery.addMethod(CtNewMethod.make("public boolean wurmRailIsTrackModel() {"
                +" return !this.wurmRailUsesDeckMatrix() && org.wurmrail.client.RailTrackTerrain.trackMask(this.getModelName()==null?null:this.getModelName().toString())>=0; }",scenery));
        // Railway scenery takes its private copy inside the callback, where we
        // can avoid native clone() replacing the pristine URL-cache entry.
        scenery.getDeclaredMethod("wantNewModelCopy").insertBefore(
                "{ if(this.wurmRailIsTrackModel()||this.wurmRailUsesDeckMatrix()||this.wurmRailCurvedPanel()!=null)return false; }");
        // The first ModelResourceWrapper listener receives the pristine cached
        // wrapper itself. Clone that callback too: deforming it would contaminate
        // every later instance of the same track shape on a different slope.
        scenery.getDeclaredMethod("modelLoaded").insertBefore("{ org.wurmrail.shared.TrestlePanel panel=this.wurmRailCurvedPanel();"
                +" if(panel!=null) { $1=$1.wurmRailPrivateTerrainCopy(); org.wurmrail.client.RailTrackTerrain.deformCurved($1,panel); }"
                +" else if(this.wurmRailIsTrackModel()) {"
                +" $1=$1.wurmRailPrivateTerrainCopy();"
                +" if($1.getModelData() instanceof com.wurmonline.client.renderer.model.collada.AbstractColladaModelData) {"
                +" com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry[] parts="
                +" ((com.wurmonline.client.renderer.model.collada.AbstractColladaModelData)$1.getModelData()).getModel().getGeometryArray();"
                +" for(int i=0;i<parts.length;i++){com.wurmonline.client.renderer.model.collada.importer.TriangleMesh[] meshes=parts[i].getTriangleMeshArray();"
                +" for(int j=0;j<meshes.length;j++)meshes[j].setAllowSlim(false);} } } }");
        CtClass ground=pool.get("com.wurmonline.client.renderer.cell.GroundItemCellRenderable");
        ground.addField(CtField.make("private org.wurmrail.client.RailTrackTerrain wurmRailTrackTerrain;",ground));
        ground.addField(CtField.make("private boolean wurmRailTrackReady;",ground));
        ground.addMethod(CtNewMethod.make("public boolean wurmRailConformTrack(long now) {"
                +" long bridge=this.item==null?-10L:this.item.getBridgeId();"
                +" boolean trestle=this.item!=null&&this.item.getModelName().toString().startsWith(\"model.wurmrail.trestledeck.\");"
                +" if(!this.wurmRailIsTrackModel()||this.getParent()!=null||(!this.isOnGround()&&bridge<=0L&&!trestle)||Math.abs(this.getScaleWithoutOffset()-1f)>.0001f||(this.getLayer()!=0&&this.getLayer()!=-1)){"
                +" if(this.wurmRailTrackTerrain!=null)this.wurmRailTrackTerrain.restore();this.wurmRailTrackReady=false;this.wurmRailTrackTerrain=null;return false;}"
                +" if(world==null||this.model==null)return false;"
                // GroundItemCellRenderable builds its first matrix in its
                // constructor, before initialize() delivers modelLoaded(). A
                // warm model at that point is still the shared cache source.
                +" if(!this.model.wurmRailIsPrivateTerrainCopy()){this.wurmRailTrackReady=false;this.wurmRailTrackTerrain=null;return false;}"
                +" if(this.wurmRailTrackTerrain==null)this.wurmRailTrackTerrain=new org.wurmrail.client.RailTrackTerrain();"
                +" boolean changed=this.wurmRailTrackTerrain.update(world,this.model,this.item==null?null:this.item.getModelName().toString(),"
                +" this.x,this.y,this.yaw,this.getScaleWithoutOffset(),this.getLayer(),bridge,now,this.item.getH());"
                +" if(changed){this.h=this.wurmRailTrackTerrain.getBase();this.wurmRailTrackReady=true;this.pitch=0f;this.roll=0f;this.setVolumeFromModel();}"
                +" return changed; }",ground));
        ground.getDeclaredMethod("prepareRender").insertBefore(
                "{ if(this.wurmRailConformTrack(System.nanoTime()))this.updateModelMatrix(); }");
        ground.getDeclaredMethod("updateModelMatrix").insertBefore(
                "{ this.wurmRailConformTrack(System.nanoTime()); if(this.wurmRailTrackReady){this.pitch=0f;this.roll=0f;this.h=this.wurmRailTrackTerrain.getBase();} }");
    }
}
