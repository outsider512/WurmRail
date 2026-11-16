package org.wurmrail.client;

import com.wurmonline.client.game.World;
import com.wurmonline.client.game.CaveDataBuffer;
import com.wurmonline.client.game.NearTerrainDataBuffer;
import com.wurmonline.client.renderer.cell.Cell;
import com.wurmonline.client.renderer.model.ModelResourceWrapper;
import com.wurmonline.client.renderer.structures.BridgePartData;
import com.wurmonline.shared.constants.BridgeConstants.BridgeState;
import com.wurmonline.client.renderer.model.collada.AbstractColladaModelData;
import com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry;
import com.wurmonline.client.renderer.model.collada.importer.TriangleMesh;
import org.wurmrail.shared.TrestlePanel;
import java.nio.FloatBuffer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** An instance's rail bed, raised along its centreline without shortening its tile footprint. */
public final class RailTrackTerrain {
    private static final int[] DX={0,1,1,1},DY={-1,-1,0,1};
    private ModelResourceWrapper wrapper;
    private final List<TriangleMesh> meshes=new ArrayList<TriangleMesh>();
    private final List<float[]> vertices=new ArrayList<float[]>(),normals=new ArrayList<float[]>();
    private float[] previous;
    private float lastX,lastY,lastYaw,lastScale;
    private int lastLayer;
    private long lastBridge;
    private long nextCheck;
    private float base;

    public float getBase() {return base;}

    public static void deformCurved(ModelResourceWrapper wrapper, TrestlePanel panel) {
        if (!(wrapper.getModelData() instanceof AbstractColladaModelData)) return;
        AbstractColladaModelData data = (AbstractColladaModelData)wrapper.getModelData();
        for (ColladaGeometry geometry : data.getModel().getGeometryArray()) {
            for (TriangleMesh mesh : geometry.getTriangleMeshArray()) {
                own(mesh);
                FloatBuffer positions = mesh.getVertexBuffer(), normals = mesh.getNormalBuffer();
                for (int index = 0; index < positions.limit(); index += 3) {
                    float fraction = (positions.get(index) + 2) / 4;
                    float lateral = positions.get(index + 2);
                    float[] point = panel.vertex(fraction, lateral);
                    positions.put(index, point[0]);
                    positions.put(index + 1, positions.get(index + 1) + point[2]);
                    positions.put(index + 2, point[1]);
                    if (normals != null) conformNormal(normals, index, panel, fraction);
                }
                mesh.updateVBO();
            }
        }
        refreshBounds(data);
    }

    private static void conformNormal(FloatBuffer normals, int index, TrestlePanel panel, float fraction) {
        float[] a = panel.vertex(fraction - 1f / TrestlePanel.INTERVALS, 0);
        float[] b = panel.vertex(fraction + 1f / TrestlePanel.INTERVALS, 0);
        float dx = b[0] - a[0], dz = b[1] - a[1];
        float length = (float)Math.hypot(dx, dz);
        if (length == 0) return;
        float tangent = normals.get(index) - normals.get(index + 1) * (b[2] - a[2]) / length;
        float up = normals.get(index + 1), across = normals.get(index + 2);
        float x = (dx * tangent - dz * across) / length, z = (dz * tangent + dx * across) / length;
        float magnitude = (float)Math.sqrt(x * x + up * up + z * z);
        if (magnitude == 0) return;
        normals.put(index, x / magnitude);
        normals.put(index + 1, up / magnitude);
        normals.put(index + 2, z / magnitude);
    }

    public boolean update(World world,ModelResourceWrapper model,String name,
            float x,float y,float yaw,float scale,int layer,long now) {
        return update(world,model,name,x,y,yaw,scale,layer,-10L,now);
    }

    public boolean update(World world,ModelResourceWrapper model,String name,
            float x,float y,float yaw,float scale,int layer,long bridgeId,long now) {
        return update(world,model,name,x,y,yaw,scale,layer,bridgeId,now,Float.NaN);
    }

    public boolean update(World world,ModelResourceWrapper model,String name,
            float x,float y,float yaw,float scale,int layer,long bridgeId,long now,float packetHeight) {
        int mask=trackMask(name);
        boolean trestle=name!=null&&name.startsWith("model.wurmrail.trestledeck.");
        if(world==null||mask<0||model==null||!model.isLoaded()
                ||!(model.getModelData() instanceof AbstractColladaModelData)
                ||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(yaw)
                ||Math.abs(scale-1f)>.0001f||(layer!=0&&layer!=-1))return false;
        boolean same=wrapper==model&&lastX==x&&lastY==y&&lastYaw==yaw&&lastScale==scale&&lastLayer==layer&&lastBridge==bridgeId;
        if(same&&now<nextCheck)return false;
        nextCheck=now+250000000L;
        BridgePartData deck=bridgeId>0?bridgeAt(world,x,y,layer,bridgeId):null;
        if(bridgeId>0&&deck==null)return false;
        if(deck==null&&layer<0&&(world.getCaveBuffer()==null||!world.getCaveBuffer().isFloor(x,y)))return false;
        int axis=axis(mask);
        float ax=axis<0?0:DX[axis],ay=axis<0?0:DY[axis];
        // Track art is baked in compass axes. Nonzero rotation is retained for
        // dropped portable sections, while placed railway pieces use yaw zero.
        double angle=Math.toRadians(-yaw),co=Math.cos(angle),si=Math.sin(angle);
        float wx=(float)(ax*co-ay*si),wy=(float)(ax*si+ay*co);
        boolean plane=deck!=null||trestle;
        float[] profile=new float[plane?3:33];
        float gx=0,gz=0;
        if(deck!=null) {
            boolean northSouth=deck.getDir()==0||deck.getDir()==4;
            gx=(float)(deck.getSlope()*(northSouth?si:co)/4);
            gz=(float)(deck.getSlope()*(northSouth?co:-si)/4);
            profile[0]=deck.getBridgeInterpolatedHeight(x,y);
            profile[1]=gx;
            profile[2]=gz;
        } else if(trestle) {
            if(!Float.isFinite(packetHeight))return false;
            float grade=trestleGrade(name);
            float length=(float)Math.hypot(ax,ay);
            gx=grade*ax/length;gz=grade*ay/length;
            profile[0]=packetHeight;profile[1]=gx;profile[2]=gz;
        } else {
            for(int i=0;i<profile.length;i++) {
                if(axis<0&&i>0){profile[i]=profile[0];continue;}
                float t=axis<0?0:(i-16)*.125f;
                profile[i]=floor(world,x+t*wx,y+t*wy,layer);
                if(!Float.isFinite(profile[i]))return false;
            }
        }
        float center=plane?profile[0]:profile[16];
        if(same&&Arrays.equals(previous,profile))return false;
        AbstractColladaModelData data=(AbstractColladaModelData)model.getModelData();
        if(wrapper!=model) {meshes.clear();vertices.clear();normals.clear();previous=null;}
        boolean uneven=plane&&(gx!=0||gz!=0);
        if(!plane)for(float height:profile)if(Math.abs(height-center)>.00001f)uneven=true;
        // A flat tile needs no private vertex allocation. Once a tile has been
        // shaped, retain its originals so later terraforming cannot accumulate.
        if(uneven||!meshes.isEmpty()) {
            if(meshes.isEmpty()) {
                for(ColladaGeometry geometry:data.getModel().getGeometryArray())
                    for(TriangleMesh mesh:geometry.getTriangleMeshArray()) {
                        own(mesh);
                        meshes.add(mesh);vertices.add(copy(mesh.getVertexBuffer()));
                        normals.add(copy(mesh.getNormalBuffer()));
                    }
            }
            float divisor=ax*ax+ay*ay;
            for(int m=0;m<meshes.size();m++) {
                TriangleMesh mesh=meshes.get(m);float[] source=vertices.get(m),normal=normals.get(m);
                FloatBuffer output=mesh.getVertexBuffer(),outNormal=mesh.getNormalBuffer();
                for(int at=0;at<source.length;at+=3) {
                    float t=divisor==0?0:(source[at]*ax+source[at+2]*ay)/divisor;
                    float f=Math.max(0,Math.min(32,(t+2)*8));int index=Math.min(31,(int)f);float blend=f-index;
                    float elevation=!plane?profile[index]*(1-blend)+profile[index+1]*blend-center
                            :gx*source[at]+gz*source[at+2];
                    output.put(at,source[at]);output.put(at+1,source[at+1]+elevation);output.put(at+2,source[at+2]);
                    if(normal!=null&&outNormal!=null) {
                        float slope=!plane?(profile[index+1]-profile[index])*8:0;
                        float slopeX=plane?gx:divisor==0?0:slope*ax/divisor;
                        float slopeZ=plane?gz:divisor==0?0:slope*ay/divisor;
                        float nx=normal[at]-slopeX*normal[at+1],ny=normal[at+1],nz=normal[at+2]-slopeZ*normal[at+1];
                        float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                        outNormal.put(at,nx/length);outNormal.put(at+1,ny/length);outNormal.put(at+2,nz/length);
                    }
                }
                mesh.updateVBO();
            }
            refreshBounds(data);
        }
        wrapper=model;previous=profile;base=center;lastX=x;lastY=y;lastYaw=yaw;lastScale=scale;lastLayer=layer;lastBridge=bridgeId;
        return true;
    }

    private static BridgePartData bridgeAt(World world,float x,float y,int layer,long bridgeId) {
        if(world.getCellRenderer()==null)return null;
        int tileX=(int)(x/4),tileY=(int)(y/4);
        Cell cell=world.getCellRenderer().findCellFromTile(tileX,tileY,layer);
        BridgePartData deck=cell==null?null:cell.getBridgeAtSurfaceAt(tileX,tileY);
        return deck!=null&&deck.getBridgeId()==bridgeId&&deck.getState()==BridgeState.COMPLETED?deck:null;
    }

    /** Return a carried/replaced rail instance to its native undeformed art. */
    public void restore() {
        for(int i=0;i<meshes.size();i++) {
            TriangleMesh mesh=meshes.get(i);FloatBuffer p=mesh.getVertexBuffer(),n=mesh.getNormalBuffer();
            float[] source=vertices.get(i),normal=normals.get(i);
            for(int at=0;at<source.length;at++)p.put(at,source[at]);
            if(n!=null&&normal!=null)for(int at=0;at<normal.length;at++)n.put(at,normal[at]);
            mesh.updateVBO();
        }
        if(wrapper!=null&&!meshes.isEmpty())refreshBounds((AbstractColladaModelData)wrapper.getModelData());
        meshes.clear();vertices.clear();normals.clear();wrapper=null;previous=null;
    }

    private static float floor(World world,float x,float y,int layer) {
        try {
            if(layer<0) {
                CaveDataBuffer cave=world.getCaveBuffer();
                // At the shared cave mouth edge one adjoining tile is surface.
                // The known cave buffer still supplies the continuous floor;
                // requiring isFloor at that exact edge would flatten this tile.
                if(cave==null||!cave.isValid(x,y))return Float.NaN;
                return cave.getInterpolatedFloor(x,y);
            }
            NearTerrainDataBuffer terrain=world.getNearTerrainBuffer();
            return terrain!=null&&terrain.isValid(x,y)?terrain.getInterpolatedHeight(x,y):Float.NaN;
        } catch(RuntimeException changingTerrain) {return Float.NaN;}
    }

    public static int trackMask(String name) {
        if(name==null)return -1;
        String trestle="model.wurmrail.trestledeck.";
        if(name.startsWith(trestle)) {
            int index=trestle.length();
            if(name.length()<=index || name.charAt(index)<'0' || name.charAt(index)>'3')return -1;
            int axis=name.charAt(index)-'0';
            return (1<<axis)|(1<<(axis+4));
        }
        int start=name.startsWith("model.wurmrail.track.")?"model.wurmrail.track.".length():
                name.startsWith("model.wurmrail.junction.")?"model.wurmrail.junction.".length():
                name.startsWith("model.wurmrail.station.")?"model.wurmrail.station.".length():-1;
        if(start<0)return -1;int end=name.length();if(end>start&&name.charAt(end-1)=='.')end--;
        if(start==end)return -1;int mask=0;
        for(int at=start;at<end;at++){char digit=name.charAt(at);if(digit<'0'||digit>'9')return -1;mask=mask*10+digit-'0';if(mask>255)return -1;}
        return mask;
    }

    private static float trestleGrade(String name) {
        int start="model.wurmrail.trestledeck.".length()+2;
        int end=name.indexOf('.',start);
        if(end<=start)return 0;
        try { return Math.max(-1,Math.min(1,Integer.parseInt(name.substring(start,end))/1000000f)); }
        catch(NumberFormatException invalid) { return 0; }
    }

    private static int axis(int mask) {
        if(mask==0)return 0;
        int count=Integer.bitCount(mask),first=Integer.numberOfTrailingZeros(mask);
        if(count==1)return first&3;
        return count==2&&mask==((1<<first)|(1<<((first+4)&7)))?first&3:-1;
    }
    private static float[] copy(FloatBuffer buffer) {
        if(buffer==null)return null;float[] result=new float[buffer.limit()];
        for(int i=0;i<result.length;i++)result[i]=buffer.get(i);return result;
    }
    // These two native operations must access private copy-on-write buffers and
    // bounds. Resolve injected methods after the launcher has transformed them;
    // reflection keeps javac independent of patched game-class signatures.
    private static Method ownMethod,boundsMethod;
    private static void own(TriangleMesh mesh) {
        try {if(ownMethod==null)ownMethod=TriangleMesh.class.getMethod("wurmRailOwnTerrainBuffers");ownMethod.invoke(mesh);}
        catch(Exception failure) {throw new IllegalStateException("Cannot isolate rail terrain mesh",failure);}
    }
    private static void refreshBounds(AbstractColladaModelData data) {
        try {if(boundsMethod==null)boundsMethod=AbstractColladaModelData.class.getMethod("wurmRailRefreshTerrainBounds");boundsMethod.invoke(data);}
        catch(Exception failure) {throw new IllegalStateException("Cannot refresh rail terrain bounds",failure);}
    }
}
