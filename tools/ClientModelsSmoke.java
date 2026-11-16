import java.io.File;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javassist.Loader;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Actual client mod discovery and native resource/Collada parsing without a display. */
public final class ClientModelsSmoke {
    public static void main(String[] args) {
        try {
            Loader loader = HookManager.getInstance().getLoader();
            for (String prefix : Arrays.asList("javafx.", "com.sun.", "org.controlsfx.",
                    "impl.org.controlsfx.", "org.gotti.wurmunlimited.modloader.classhooks.", "javassist."))
                loader.delegateLoadingOf(prefix);
            Thread.currentThread().setContextClassLoader(loader);
            loader.run("ClientModelsSmoke$Startup", args);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    public static final class Startup {
        public static void main(String[] args) throws Throwable {
            // Disable the display capability probe before bootstrap can verify
            // any game classes that reference WorldRender.
            HookManager.getInstance().getClassPool().get("com.wurmonline.client.renderer.WorldRender")
                    .getClassInitializer().setBody("{}");
            HookManager.getInstance().getClassPool().get("com.wurmonline.client.renderer.gui.text.TextQuad")
                    .getDeclaredMethod("isSupported").setBody("{return false;}");
            System.out.println("Client runtime Java " + System.getProperty("java.version"));
            org.gotti.wurmunlimited.modloader.ModLoader loader =
                    new org.gotti.wurmunlimited.modloader.ModLoader();
            List<? extends org.gotti.wurmunlimited.modloader.interfaces.ModEntry<
                    org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod>> mods =
                    loader.loadModsFromModDir(Paths.get("mods"));
            require(mods.size() == 1 && "wurmrailmodels".equals(mods.get(0).getName()),
                    "exactly WurmRailModels must be discovered");
            System.out.println("PASS genuine client mod discovery, ModComm and resource hook lifecycle");
            // Preserve the real model-load callbacks, equipment updates, and
            // normal/selection mesh filters. Record draws at the GPU submission
            // boundary so this regression needs neither OpenGL nor a game session.
            javassist.CtClass renderer = HookManager.getInstance().getClassPool()
                    .get("com.wurmonline.client.renderer.model.collada.ColladaModelRender");
            renderer.getClassInitializer().setBody("{}");
            renderer.getDeclaredMethod("renderTriangleList").setBody(
                    "{ ClientModelsSmoke$Checks.recordNativeDraw($9, $11); }");
            // Rail scenery exercises native world matrices. Its renderer-origin
            // getters are CPU fields; only the GPU capability initializer needs
            // exclusion in this display-free harness.
            HookManager.getInstance().getLoader().run("ClientModelsSmoke$Checks", args);
        }
    }

    public static final class Checks {
        private static int normalDraws, selectionDraws;

        public static void recordNativeDraw(
                com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh,
                com.wurmonline.client.renderer.ModelRenderMode mode) {
            require(mesh.enableRender && mesh.shouldRender && mesh.getTriangleCount() > 0,
                    "native draw submission must contain enabled indexed geometry");
            if (mode == com.wurmonline.client.renderer.ModelRenderMode.RENDER_NORMAL) normalDraws++;
            if (mode == com.wurmonline.client.renderer.ModelRenderMode.RENDER_SOLID) selectionDraws++;
        }

        public static void main(String[] args) throws Exception {
            File packDirectory = Paths.get(args[0], "packs").toFile();
            com.wurmonline.client.resources.Resources resources =
                    new com.wurmonline.client.resources.Resources(packDirectory,
                            java.util.Collections.<String>emptyList());
            List<String> keys = new ArrayList<String>();
            keys.add("model.wurmrail.locomotive");
            keys.add("model.wurmrail.cart");
            keys.add("model.wurmrail.tankcar");
            keys.add("model.wurmrail.logcar");
            keys.add("model.wurmrail.bulkhopper");
            keys.add("model.wurmrail.steamrockdrill");
            keys.add("model.wurmrail.industrialwatertank");
            keys.add("model.wurmrail.logbunk");
            keys.add("model.wurmrail.steamyarder");
            keys.add("model.wurmrail.steamwaterpump");
            keys.add("model.wurmrail.platform.pickup");
            keys.add("model.wurmrail.platform.dropoff");
            for(int axis=0;axis<4;axis++)keys.add("model.wurmrail.trestledeck."+axis);
            keys.add("model.wurmrail.trestlecurve");
            for(int height:new int[]{1,2,4,8,16,32})keys.add("model.wurmrail.trestlebent."+height);
            keys.add("model.wurmrail.alignment.0");
            for(int a=0;a<8;a++)for(int b=a+1;b<8;b++)keys.add("model.wurmrail.alignment."+((1<<a)|(1<<b)));
            for(int direction=0;direction<8;direction++)for(String aspect:Arrays.asList("red","green"))keys.add("model.wurmrail.signal."+direction+"."+aspect);
            for (int mask = 0; mask < 256; mask++)
                for (String kind : Arrays.asList("track", "junction", "station"))
                    keys.add("model.wurmrail." + kind + "." + mask);
            for (String key : keys) {
                require(resources.resourceExists(key, false), "native mapping missing: " + key);
                com.wurmonline.client.resources.ResourceUrl url = resources.getResource(key);
                require(url != null && url.toString().contains("wurmrail-models.jar"),
                        "custom resource must resolve from installed pack: " + key);
                require(resources.resourceExists(key + ".", true),
                        "actual server model name with trailing dot must resolve: " + key);
                require(url.equals(resources.getResource(key + ".")),
                        "server model name must use native suffix fallback to the custom mesh: " + key);
            }
            System.out.println("PASS native Resources constructor automatically selects all custom model mappings");
            TrestleMapClientSmoke.run();
            RailVehicleTerrainClientSmoke.run();
            RailTrackTerrainClientSmoke.run();
            RailVehicleMotionClientSmoke.run();
            RailVehicleClockClientSmoke.run();
            RailVehicleRefreshClientSmoke.run();
            RailVehiclePlayerClientSmoke.run();
            RailVehicleRiderClientSmoke.run();
            CoalVeinClientSmoke.run();
            int models = 0;
            int optimizedModels = 0;
            long vertices = 0;
            java.util.Set<String> checkedTextures = new java.util.HashSet<String>();
            for (String key : keys) {
                com.wurmonline.client.resources.ResourceUrl url = resources.getResource(key);
                com.wurmonline.client.renderer.model.collada.importer.ColladaModel model;
                try (InputStream source = url.openStream()) {
                    model = com.wurmonline.client.renderer.model.collada.importer.ColladaImporter
                            .loadModel(url.toString(), source);
                }
                require(model != null, "native Collada importer rejected " + key);
                require(model.getGeometryArray() != null && model.getGeometryArray().length > 0,
                        "native model has no geometry: " + key);
                require(model.getTotalVertices() > 0, "native model has no vertices: " + key);
                for (com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry
                        : model.getGeometryArray()) {
                    require(geometry.getTriangleMeshArray() != null, "native model has no draw meshes: " + key);
                    for (com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh
                            : geometry.getTriangleMeshArray()) {
                        require(mesh.getVertexCount() > 0 && mesh.getVertexCount() <= 32767,
                                "draw mesh exceeds native signed short vertex indices: " + key);
                        // Native COLLADA import produces deindexed triangle soup.
                        // getTriangleCount() reads indexCount, which is still zero
                        // until the game's next-stage model.optimize() call.
                        if (mesh.getIndexBuffer() == null) {
                            require(mesh.getVertexCount() % 3 == 0,
                                    "deindexed native draw mesh must contain complete triangles: " + key);
                        } else {
                            require(mesh.getIndexCount() > 0 && mesh.getIndexCount() % 3 == 0
                                    && mesh.getTriangleCount() > 0, "indexed draw mesh has no complete triangles: " + key);
                        }
                        require(mesh.getNormalBuffer() != null, "draw mesh has no native normals: " + key);
                        require(mesh.getColladaMaterial() != null, "draw mesh has no native material: " + key);
                        String textureName = mesh.getColladaMaterial().getTextureName();
                        require(textureName != null && textureName.endsWith(".png"),
                                "native renderer needs an original diffuse texture for material colors: " + key);
                        com.wurmonline.client.resources.ResourceUrl texture = url.derive(textureName);
                        require(texture != null && texture.exists(), "native material texture cannot resolve: " + textureName);
                        if (checkedTextures.add(texture.toString())) {
                            try (InputStream textureBytes = texture.openStream()) {
                                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(textureBytes);
                                require(image != null && image.getWidth() > 0 && image.getHeight() > 0,
                                        "native diffuse texture is not a valid image: " + textureName);
                            }
                        }
                        java.nio.FloatBuffer uv = mesh.getTextureCoordBuffer(0);
                        require(uv != null && uv.limit() == mesh.getVertexCount() * 2,
                                "native diffuse texture needs matching UV coordinates: " + key);
                        for (int coordinate = 0; coordinate < uv.limit(); coordinate++)
                            require(!Float.isNaN(uv.get(coordinate)) && !Float.isInfinite(uv.get(coordinate)),
                                    "native texture coordinates must be finite: " + key);
                        require(uv.limit() >= 6 && Math.abs((uv.get(2) - uv.get(0)) * (uv.get(5) - uv.get(1))
                                - (uv.get(3) - uv.get(1)) * (uv.get(4) - uv.get(0))) > .000001f,
                                "native UV triangle needs nonzero area for finite tangents: " + key);
                        java.nio.FloatBuffer normals = mesh.getNormalBuffer();
                        for (int normal = 0; normal < normals.limit(); normal += 3) {
                            float nx = normals.get(normal), ny = normals.get(normal + 1), nz = normals.get(normal + 2);
                            float length = nx * nx + ny * ny + nz * nz;
                            require(!Float.isNaN(length) && length > .98f && length < 1.02f,
                                    "native independent normal indices must produce unit normals: " + key);
                        }
                        java.nio.FloatBuffer points = mesh.getVertexBuffer();
                        for (int point = 0; point < points.limit(); point += 3) {
                            float y = points.get(point + 1), z = points.get(point + 2);
                            require(!Float.isNaN(y) && !Float.isInfinite(y)
                                    && !Float.isNaN(z) && !Float.isInfinite(z), "invalid native coordinates: " + key);
                        }
                    }
                }
                com.wurmonline.client.renderer.model.collada.ColladaPropertiesXml.load(model, url, false);
                checkNativeItemOrientation(key, model);
                vertices += model.getTotalVertices();
                // Native optimization deduplicates vertices with a quadratic
                // search. Exercise every two-exit shape plus vehicles, accessory
                // meshes and the largest eight-way layout; parse all models above.
                boolean optimize = key.startsWith("model.wurmrail.signal.") || key.startsWith("model.wurmrail.alignment.") || key.startsWith("model.wurmrail.platform.") || key.equals("model.wurmrail.locomotive") || key.equals("model.wurmrail.cart") || key.equals("model.wurmrail.tankcar") || key.equals("model.wurmrail.logcar") || key.equals("model.wurmrail.bulkhopper") || key.equals("model.wurmrail.steamrockdrill")
                        || key.equals("model.wurmrail.logbunk") || key.equals("model.wurmrail.steamyarder") || key.equals("model.wurmrail.steamwaterpump")
                        || key.equals("model.wurmrail.track.255") || key.equals("model.wurmrail.station.0")
                        || key.equals("model.wurmrail.junction.0");
                if (key.startsWith("model.wurmrail.track.")) {
                    int mask = Integer.parseInt(key.substring("model.wurmrail.track.".length()));
                    optimize |= Integer.bitCount(mask) == 2;
                }
                if (optimize) {
                    model.optimize();
                    for (com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry
                            : model.getGeometryArray())
                        for (com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh
                                : geometry.getTriangleMeshArray()) {
                            require(mesh.isOptimized() && mesh.getIndexBuffer() != null
                                    && mesh.getTriangleCount() > 0 && mesh.getIndexCount() % 3 == 0,
                                    "native optimizer must produce indexed triangles for rendering: " + key);
                            java.nio.IntBuffer indices = mesh.getIndexBuffer();
                            for (int index = 0; index < indices.limit(); index++)
                                require(indices.get(index) >= 0 && indices.get(index) < mesh.getVertexCount(),
                                        "native optimized draw index is out of range: " + key);
                        }
                    optimizedModels++;
                }
                if (key.equals("model.wurmrail.locomotive") || key.equals("model.wurmrail.cart") || key.equals("model.wurmrail.tankcar") || key.equals("model.wurmrail.logcar"))
                    checkNativeVehicleVisibility(key, url, model);
                models++;
            }
            require(models == 836, "pack must contain all existing models and eleven modular trestle meshes");
            System.out.println("PASS native Collada importer parsed " + models + " custom meshes (" + vertices
                    + " vertices) and " + checkedTextures.size() + " original diffuse textures");
            System.out.println("PASS native renderer optimization and triangle indices for " + optimizedModels
                    + " representative models including all 28 two-exit shapes and the eight-way junction");
            System.out.println("PASS native item world matrices: all 768 rail layouts are flat with matching compass exits;"
                    + " all four vehicles and sixteen rider mounts agree with server positions at all eight headings");
            System.out.println("PASS locomotive roof clearance above all four rider roots at all eight headings"
                    + " for a representative 1.80m root-to-head extent (geometry check; no live avatar)");
            System.out.println("PASS native vehicle-load callbacks, equipment ticks and normal/selection draw filters"
                    + " preserve all four vehicles and their four passenger sockets");
            System.out.println("CLIENT MODEL SMOKE PASSED (headless native CPU paths; GPU submissions recorded; no player session started)");
        }

        private static void checkNativeVehicleVisibility(String key,
                com.wurmonline.client.resources.ResourceUrl url,
                com.wurmonline.client.renderer.model.collada.importer.ColladaModel source) throws Exception {
            // Dynamic vehicle instances receive independent copies. Use the real
            // native copy and render data, including native bounds calculations.
            com.wurmonline.client.renderer.model.collada.importer.ColladaModel model =
                    new com.wurmonline.client.renderer.model.collada.importer.ColladaModel(source);
            com.wurmonline.client.renderer.model.collada.ColladaModelData data =
                    new com.wurmonline.client.renderer.model.collada.ColladaModelData(url, model);
            com.wurmonline.client.renderer.model.ModelDataBounds bounds = model.calculateBoundingBox();
            com.wurmonline.client.renderer.model.ModelDataBounds pick = model.calculatePickingBox();
            if (pick == null) pick = new com.wurmonline.client.renderer.model.ModelDataBounds(bounds);
            // init() also loads GPU textures/materials. Those are covered by the
            // pack's independent texture tests; supply its native CPU bounds here.
            setFixtureField(data, "bounds", bounds);
            setFixtureField(data, "pickingBound", pick);
            setFixtureField(data, "originalPickingBound",
                    new com.wurmonline.client.renderer.model.ModelDataBounds(pick));
            require(bounds.getSizeX() > 1f && bounds.getSizeH() > 1.7f && bounds.getSizeY() > 3.8f,
                    "native vehicle must have usable visible bounds: " + key);
            require(data.getPickingBounds().getSizeX() > 1f && data.getPickingBounds().getSizeY() > 3.8f,
                    "native vehicle must have usable selection bounds: " + key);
            com.wurmonline.client.renderer.model.ModelResourceWrapper wrapper =
                    new com.wurmonline.client.renderer.model.ModelResourceWrapper(url);
            wrapper.setModel(data);

            // Allocate only the native listener's CPU state; a normal constructor
            // registers terrain/world rendering, which needs a running client.
            java.lang.reflect.Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object allocator = unsafeField.get(null);
            com.wurmonline.client.renderer.cell.CreatureCellRenderable vehicle =
                    (com.wurmonline.client.renderer.cell.CreatureCellRenderable)allocator.getClass()
                    .getMethod("allocateInstance", Class.class).invoke(allocator,
                            com.wurmonline.client.renderer.cell.CreatureCellRenderable.class);
            setFixtureField(vehicle, "modelName", new com.wurmonline.client.util.SecureStrings(key + "."));
            setFixtureField(vehicle, "movementData", new com.wurmonline.client.renderer.cell.MovementData(
                    null, 0f, com.wurmonline.client.sound.MovementSound.MovementType.WALK));
            setFixtureField(vehicle, "currentLod",
                    com.wurmonline.client.renderer.model.collada.importer.TriangleMesh.LODLevel.NONE);
            vehicle.modelLoaded(wrapper);
            int expectedDraws = 0;
            for (com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry : model.getGeometryArray())
                for (com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh : geometry.getTriangleMeshArray())
                    if (mesh.enableRender && mesh.shouldRender) expectedDraws++;
            require(expectedDraws > 0, "native loaded vehicle must begin with visible geometry: " + key);
            // CreatureCellRenderable.gameTick performs this exact equipment step.
            // In 2.0.1 it disables every railway mesh, despite successful imports.
            for (int tick = 0; tick < 3; tick++)
                if (vehicle.getMountItems() != null) vehicle.getMountItems().updateItems();
            normalDraws = selectionDraws = 0;
            data.render(null, nativeItemMatrix(0f, true), null, null, null, null, null,
                    com.wurmonline.client.renderer.ModelRenderMode.RENDER_NORMAL, 0, 0f,
                    com.wurmonline.client.renderer.model.collada.importer.TriangleMesh.LODLevel.NONE);
            data.render(null, nativeItemMatrix(0f, true), null, null, null, null, null,
                    com.wurmonline.client.renderer.ModelRenderMode.RENDER_SOLID, 0, 0f,
                    com.wurmonline.client.renderer.model.collada.importer.TriangleMesh.LODLevel.NONE);
            require(normalDraws == expectedDraws && selectionDraws == expectedDraws,
                    "native vehicle-load/equipment update hides visible or selectable geometry: " + key
                            + " normal=" + normalDraws + "/" + expectedDraws
                            + " selection=" + selectionDraws + "/" + expectedDraws);
            require(!model.getCanHaveMountItems() && vehicle.getMountItems() == null,
                    "rail vehicles must not enable horse/boat equipment mesh filtering: " + key);
            require(data.getShouldBeRendered() && vehicle.getMountOffset() != null,
                    "native model listener must retain rendering and passenger offset support: " + key);
            for (int seat = 1; seat <= 4; seat++)
                require(data.hasNull("NullMount" + seat), "native vehicle lost passenger socket " + seat);
        }

        private static void setFixtureField(Object object, String name, Object value) throws Exception {
            for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(object, value);
                    return;
                } catch (NoSuchFieldException missing) { }
            }
            throw new NoSuchFieldException(name);
        }
    }

    private static final float WORLD_X = 123.25f, WORLD_H = 7.5f, WORLD_Y = 456.75f;
    private static final int[][] EXITS = {{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};

    /** Native network and renderer paths differ for scenery and moving vehicles. */
    private static com.wurmonline.client.renderer.Matrix nativeItemMatrix(float serverYaw, boolean vehicle) {
        com.wurmonline.math.Transform transform = new com.wurmonline.math.Transform();
        // addItem -> GroundItemCellRenderable: client yaw = -server yaw.
        // addTheCreature/moveCreature -> CreatureCellRenderable (also item
        // vehicles) -> MobileModelRenderable: client yaw = 180-server yaw.
        float clientYaw = vehicle ? 180f - serverYaw : -serverYaw;
        transform.rotation.fromAngles(0f, (float)Math.toRadians(clientYaw), 0f);
        transform.translation.set(WORLD_X, WORLD_H, WORLD_Y);
        com.wurmonline.client.renderer.Matrix result = new com.wurmonline.client.renderer.Matrix();
        return vehicle ? result.fromTransformAndNonUniformScale(transform, 1f, 1f, 1f)
                : result.fromTransformAndScale(transform, 1f);
    }

    /** ColladaModelRender.renderTriangleList also applies the mesh's visual-scene bind matrix. */
    private static com.wurmonline.client.renderer.Matrix nativeMeshMatrix(
            com.wurmonline.client.renderer.Matrix item,
            com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh) {
        if (mesh.hasIdentityBindMatrix() || mesh.getBindmatrix() == null) return item;
        require(mesh.getBindmatrixForRender() != null, "nonidentity visual-scene bind must have a renderer matrix");
        return com.wurmonline.client.renderer.Matrix.mult(item, mesh.getBindmatrixForRender(),
                new com.wurmonline.client.renderer.Matrix());
    }

    private static void checkNativeItemOrientation(String key,
            com.wurmonline.client.renderer.model.collada.importer.ColladaModel model) {
        if(key.startsWith("model.wurmrail.junction.")) {
            for(com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry g:model.getGeometryArray())
                for(com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh:g.getTriangleMeshArray()) {
                    require(!mesh.getColladaMaterial().getTextureName().endsWith("cream.png"),
                            "native junction switches must not retain the obsolete white sign");
                    java.nio.FloatBuffer points=mesh.getVertexBuffer();
                    for(int i=1;i<points.limit();i+=3)
                        require(points.get(i)<1.10f,"native junction scenery stops at the mechanical switch lever");
                }
        }
        if(key.startsWith("model.wurmrail.signal.")) {
            String[] parts=key.split("\\."); int direction=Integer.parseInt(parts[3]); boolean green="green".equals(parts[4]);
            double length=Math.hypot(EXITS[direction][0],EXITS[direction][1]);
            double dx=EXITS[direction][0]/length,dz=EXITS[direction][1]/length;
            double anchorX=.8*EXITS[direction][0]-1.25*dz,anchorZ=.8*EXITS[direction][1]+1.25*dx;
            float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY; int lit=0;
            for(com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry g:model.getGeometryArray())
                for(com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh:g.getTriangleMeshArray()) {
                    String texture=mesh.getColladaMaterial().getTextureName();
                    require(!texture.endsWith((green?"signalred":"signalgreen")+".png"),"only the selected signal lens may be bright");
                    java.nio.FloatBuffer points=mesh.getVertexBuffer();
                    for(int i=0;i<points.limit();i+=3) {
                        float x=points.get(i),y=points.get(i+1),z=points.get(i+2);
                        low=Math.min(low,y);high=Math.max(high,y);
                        require(Math.abs(x)<=2.001&&Math.abs(z)<=2.001,"baked signal geometry remains within its own ground tile");
                        if(texture.endsWith((green?"signalgreen":"signalred")+".png")) {
                            lit++;
                            require((x-anchorX)*dx+(z-anchorZ)*dz>.12,"active signal lens faces the absolute controlled approach");
                            require(Math.abs(y-(green?1.74:2.23))<.181,"red is the upper lens and green is the lower lens");
                        }
                    }
                }
            require(low>=-.001f&&low<.001f&&high>2.5f&&high<2.55f&&lit>0,"native signal post stands upright with a visible aspect");
            return;
        }
        if(key.startsWith("model.wurmrail.alignment.")) {
            int mask=Integer.parseInt(key.substring(key.lastIndexOf('.')+1)); boolean[] ends=new boolean[8];
            for(com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry g:model.getGeometryArray())
                for(com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh:g.getTriangleMeshArray()) {
                    java.nio.FloatBuffer points=mesh.getVertexBuffer();
                    for(int i=0;i<points.limit();i+=3) {
                        require(points.get(i+1)>=.23f&&points.get(i+1)<=.4f,"junction indicator must lie above the rails in native Y-up coordinates");
                        for(int side=0;side<8;side++)if(Math.hypot(points.get(i)-2*EXITS[side][0],points.get(i+2)-2*EXITS[side][1])<.15)ends[side]=true;
                    }
                }
            for(int side=0;side<8;side++)if((mask&(1<<side))!=0)require(ends[side],"junction highlight must reach each absolute aligned side");
            return;
        }
        if(key.startsWith("model.wurmrail.platform.")) {
            float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
            for(com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry g:model.getGeometryArray())
                for(com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh:g.getTriangleMeshArray()) {
                    java.nio.FloatBuffer points=mesh.getVertexBuffer();
                    for(int i=1;i<points.limit();i+=3) { low=Math.min(low,points.get(i)); high=Math.max(high,points.get(i)); }
                }
            require(low>=-.001f&&low<.01f&&high>1.5f&&high<1.7f,"passenger platform pad must lie flat with an upright sign");
            return;
        }
        if(key.equals("model.wurmrail.bulkhopper")) {
            require(!model.getNotStaticData(),"a placed Bulk Hopper must use static model data");
            float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
            for(com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry:model.getGeometryArray())
                for(com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh:geometry.getTriangleMeshArray()) {
                    java.nio.FloatBuffer points=mesh.getVertexBuffer();
                    for(int i=0;i<points.limit();i+=3) {
                        require(Math.abs(points.get(i))<2&&Math.abs(points.get(i+2))<2,"hopper fits inside one ground tile");
                        low=Math.min(low,points.get(i+1));high=Math.max(high,points.get(i+1));
                    }
                }
            require(low>=-.001f&&low<.05f&&high>2.5f&&high<4f,"hopper feet sit on the ground and the bin stands upright");
            return;
        }
        if(key.equals("model.wurmrail.steamrockdrill")) {
            require(!model.getNotStaticData(),"a placed Steam Rock Drill must use static model data");
            float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
            for(com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry:model.getGeometryArray())
                for(com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh:geometry.getTriangleMeshArray()) {
                    java.nio.FloatBuffer points=mesh.getVertexBuffer();
                    for(int i=0;i<points.limit();i+=3) {
                        require(Math.abs(points.get(i))<2&&Math.abs(points.get(i+2))<2,"four-face drill stays inside its ground tile");
                        low=Math.min(low,points.get(i+1));high=Math.max(high,points.get(i+1));
                    }
                }
            require(low>=-.001f&&low<.01f&&high>1.7f&&high<2f,"drill feet sit flat and its boiler fits under a normal cave ceiling");
            return;
        }
        if(key.equals("model.wurmrail.steamwaterpump")) {
            require(!model.getNotStaticData(),"the Steam Water Pump is static industrial equipment");
            float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
            for(com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry:model.getGeometryArray())
                for(com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh:geometry.getTriangleMeshArray()) {
                    java.nio.FloatBuffer points=mesh.getVertexBuffer();
                    for(int i=0;i<points.limit();i+=3) {
                        require(Math.abs(points.get(i))<1.1&&Math.abs(points.get(i+2))<1.1,"water pump fits its compact ground footprint");
                        low=Math.min(low,points.get(i+1));high=Math.max(high,points.get(i+1));
                    }
                }
            require(low>=-.001f&&low<.01f&&high>2.1f&&high<2.21f,"water pump is upright and grounded");return;
        }
        if(key.equals("model.wurmrail.industrialwatertank")) {
            require(!model.getNotStaticData(),"the Industrial Water Tank uses native static model data");
            float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
            for(com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry:model.getGeometryArray())
                for(com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh:geometry.getTriangleMeshArray()) {
                    java.nio.FloatBuffer points=mesh.getVertexBuffer();
                    for(int i=0;i<points.limit();i+=3) {
                        require(Math.abs(points.get(i))<2.1&&Math.abs(points.get(i+2))<2.1,"industrial tank fits its ground footprint");
                        low=Math.min(low,points.get(i+1));high=Math.max(high,points.get(i+1));
                    }
                }
            require(low>=-.001f&&low<.05f&&high>4&&high<5,"industrial tank is upright with grounded feet");return;
        }
        if(key.equals("model.wurmrail.logbunk")||key.equals("model.wurmrail.steamyarder")) {
            require(!model.getNotStaticData(),"placed forestry equipment uses native static model data");
            float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
            for(com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry:model.getGeometryArray())
                for(com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh:geometry.getTriangleMeshArray()) {
                    java.nio.FloatBuffer points=mesh.getVertexBuffer();
                    for(int i=0;i<points.limit();i+=3) {
                        require(Math.abs(points.get(i))<2&&Math.abs(points.get(i+2))<2,"forestry equipment fits its one-tile footprint");
                        low=Math.min(low,points.get(i+1));high=Math.max(high,points.get(i+1));
                    }
                }
            require(low>=-.001f&&low<.01f,"forestry equipment feet sit on the ground");
            require(key.endsWith("logbunk")?high>2.2f&&high<2.4f:high>3.1f&&high<3.3f,"forestry equipment is upright at its authored height");
            return;
        }
        boolean engine = key.equals("model.wurmrail.locomotive");
        boolean tank = key.equals("model.wurmrail.tankcar");
        boolean timber = key.equals("model.wurmrail.logcar");
        boolean vehicle = engine || tank || timber || key.equals("model.wurmrail.cart");
        if (key.startsWith("model.wurmrail.trestlebent.")) {
            require(!model.getNotStaticData(), "timber bents use reusable static models");
            return;
        }
        if (vehicle) {
            require(model.getNotStaticData(), "vehicle must use dynamic model data for riders: " + key);
        }
        boolean curvedDeck = key.equals("model.wurmrail.trestlecurve");
        int variant = curvedDeck ? 2 : vehicle ? 0 : Integer.parseInt(key.substring(key.lastIndexOf('.') + 1));
        int mask = curvedDeck || key.startsWith("model.wurmrail.trestledeck.") ? (1<<variant)|(1<<(variant+4)) : variant;
        for (int heading = 0; heading < (vehicle ? 8 : 1); heading++) {
            float yaw = heading * 45f;
            double angle = Math.toRadians(yaw), sin = Math.sin(angle), cos = Math.cos(angle);
            com.wurmonline.client.renderer.Matrix item = nativeItemMatrix(yaw, vehicle);
            float minHeight = Float.POSITIVE_INFINITY, maxHeight = Float.NEGATIVE_INFINITY;
            double minAlong = Double.POSITIVE_INFINITY, maxAlong = Double.NEGATIVE_INFINITY;
            float minRailHeight = Float.POSITIVE_INFINITY, maxRailHeight = Float.NEGATIVE_INFINITY;
            int[] exitSides = new int[8];
            double lampForward = 0;
            int lampVertices = 0;
            com.wurmonline.math.Vector3f point = new com.wurmonline.math.Vector3f();
            for (com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry
                    : model.getGeometryArray())
                for (com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh
                        : geometry.getTriangleMeshArray()) {
                    com.wurmonline.client.renderer.Matrix render = nativeMeshMatrix(item, mesh);
                    String texture = mesh.getColladaMaterial().getTextureName();
                    boolean railhead = texture.endsWith("rail.png");
                    boolean lamp = texture.endsWith("lamp.png");
                    java.nio.FloatBuffer positions = mesh.getVertexBuffer();
                    for (int vertex = 0; vertex < positions.limit(); vertex += 3) {
                        point.set(positions.get(vertex), positions.get(vertex + 1), positions.get(vertex + 2));
                        render.transformInPlace(point);
                        float height = point.y - WORLD_H;
                        minHeight = Math.min(minHeight, height);
                        maxHeight = Math.max(maxHeight, height);
                        double east = point.x - WORLD_X, south = point.z - WORLD_Y;
                        double along = east * sin - south * cos;
                        minAlong = Math.min(minAlong, along); maxAlong = Math.max(maxAlong, along);
                        if (lamp) { lampForward += along; lampVertices++; }
                        if (!railhead) continue;
                        minRailHeight = Math.min(minRailHeight, height);
                        maxRailHeight = Math.max(maxRailHeight, height);
                        if (Math.abs(height - .21f) > .001f) continue;
                        for (int exit = 0; exit < 8; exit++) {
                            if (mask != 0 && (mask & (1 << exit)) == 0) continue;
                            if (mask == 0 && exit != 0 && exit != 4) continue;
                            int dx = EXITS[exit][0], dy = EXITS[exit][1];
                            double length = Math.hypot(dx, dy);
                            double offsetX = east - 2 * dx, offsetY = south - 2 * dy;
                            double forward = (offsetX * dx + offsetY * dy) / length;
                            double side = (-offsetX * dy + offsetY * dx) / length;
                            // Endpoint tangents use sampled Beziers, so their end
                            // cross sections differ by a few millimetres on bends.
                            if (Math.abs(forward) < .025 && Math.abs(Math.abs(side) - .55) < .065)
                                exitSides[exit] |= side < 0 ? 1 : 2;
                        }
                    }
                }
            if (!vehicle) {
                require(Math.abs(minRailHeight - .18f) < .001f && Math.abs(maxRailHeight - .21f) < .001f,
                        "railheads must render flat at world height +0.18..0.21m, using native Y-up item matrices: "
                                + key + " got " + minRailHeight + ".." + maxRailHeight);
                for (int exit = 0; exit < 8; exit++)
                    if ((mask == 0 && (exit == 0 || exit == 4)) || (mask & (1 << exit)) != 0)
                        require(exitSides[exit] == 3, "native world rails miss one or both railheads at compass exit "
                                + exit + " for " + key);
                continue;
            }
            require(minHeight >= .20f && minHeight <= .24f && maxHeight > (engine ? 2.6f : tank||timber ? 2.3f : 1.7f)
                            && maxHeight < (engine ? 2.9f : tank||timber ? 2.5f : 1.9f) && maxAlong - minAlong > 3.8
                            && maxAlong - minAlong < 4.0,
                    "native vehicle must stand upright with wheels on rails at server heading " + yaw + ": " + key
                            + " height " + minHeight + ".." + maxHeight + " length " + (maxAlong - minAlong));
            if (engine)
                require(lampVertices > 0 && lampForward / lampVertices > 1.45,
                        "locomotive headlamp must face the server's travel direction at heading " + yaw);
            for (int seat = 1; seat <= 4; seat++) {
                require(model.hasNull("NullMount" + seat), "vehicle missing native mount " + seat);
                // Geometric design coordinates (east, south, up). Wurm's
                // server Seat API instead receives (seatY, -seatX, seatZ).
                float seatX = (seat % 2 == 1 ? -1 : 1) * (engine ? .48f : .55f);
                float seatY = engine ? (seat <= 2 ? .65f : 1.25f) : (seat <= 2 ? -1 : 1)*(tank||timber ? 1.45f : .70f);
                // Passenger sockets locate the character root, not the seat cushion.
                float seatZ = engine ? .55f : .95f;
                com.wurmonline.math.Vector offset = model.getOriginalNullAttachmentOffset(
                        "NullMount" + seat, new com.wurmonline.math.Vector());
                require(Math.abs(offset.x() + seatX) < .001f && Math.abs(offset.y() - seatZ) < .001f
                                && Math.abs(offset.z() + seatY) < .001f,
                        "native moving-vehicle attachment must match authored seat geometry after its 180-degree"
                                + " asset rotation: " + key + " seat " + seat);
                com.wurmonline.math.Vector activeOffset = model.getNullAttachmentOffset(
                        "NullMount" + seat, new com.wurmonline.math.Vector());
                require(Math.abs(activeOffset.x() - offset.x()) < .001f
                                && Math.abs(activeOffset.y() - offset.y()) < .001f
                                && Math.abs(activeOffset.z() - offset.z()) < .001f,
                        "active native rider joint must agree with its bind position: " + key + " seat " + seat);
                com.wurmonline.math.Vector jointRotation = model.getNullRotation("NullMount" + seat);
                require(Math.abs(jointRotation.x()) < .001f && Math.abs(jointRotation.y()) < .001f
                                && Math.abs(jointRotation.z()) < .001f,
                        "native rider joint basis must stay upright after converting asset coordinates: "
                                + key + " seat " + seat);
                point.set(offset.x(), offset.y(), offset.z());
                item.transformInPlace(point);
                require(Math.abs(point.x - (WORLD_X + cos * seatX - sin * seatY)) < .001
                                && Math.abs(point.y - (WORLD_H + seatZ)) < .001
                                && Math.abs(point.z - (WORLD_Y + sin * seatX + cos * seatY)) < .001,
                        "native mounted player world position differs from server seat at heading " + yaw
                                + ": " + key + " seat " + seat);
                if (engine) {
                    float roof = roofUnderside(model, item, point.x, point.z);
                    // A fixed representative root-to-head extent catches the old
                    // 1.05 m root intersecting the actual roof. This is a geometric
                    // clearance check, not a live avatar, animation or camera test.
                    float representativeHeight = 1.80f;
                    require(Float.isFinite(roof) && roof - (point.y + representativeHeight) >= .20f,
                            "locomotive root must leave 20 cm above a representative 1.80 m rider at heading "
                                    + yaw + " seat " + seat);
                    require(WORLD_H + 1.05f + representativeHeight > roof,
                            "roof-clearance fixture must reject the old 1.05 m locomotive root");
                }
            }
        }
    }

    /** Native rendered horizontal ceiling triangles above a passenger's world X/Z. */
    private static float roofUnderside(
            com.wurmonline.client.renderer.model.collada.importer.ColladaModel model,
            com.wurmonline.client.renderer.Matrix item, float x, float z) {
        float ceiling = Float.POSITIVE_INFINITY;
        com.wurmonline.math.Vector3f a = new com.wurmonline.math.Vector3f();
        com.wurmonline.math.Vector3f b = new com.wurmonline.math.Vector3f();
        com.wurmonline.math.Vector3f c = new com.wurmonline.math.Vector3f();
        for (com.wurmonline.client.renderer.model.collada.importer.ColladaGeometry geometry : model.getGeometryArray())
            for (com.wurmonline.client.renderer.model.collada.importer.TriangleMesh mesh : geometry.getTriangleMeshArray()) {
                com.wurmonline.client.renderer.Matrix render = nativeMeshMatrix(item, mesh);
                java.nio.FloatBuffer vertices = mesh.getVertexBuffer();
                java.nio.IntBuffer indices = mesh.getIndexBuffer();
                int count = indices == null ? mesh.getVertexCount() : mesh.getIndexCount();
                for (int triangle = 0; triangle + 2 < count; triangle += 3) {
                    worldVertex(a, vertices, indices == null ? triangle : indices.get(triangle), render);
                    worldVertex(b, vertices, indices == null ? triangle + 1 : indices.get(triangle + 1), render);
                    worldVertex(c, vertices, indices == null ? triangle + 2 : indices.get(triangle + 2), render);
                    float min = Math.min(a.y, Math.min(b.y, c.y)), max = Math.max(a.y, Math.max(b.y, c.y));
                    if (min < WORLD_H + 2.40f || max - min > .001f) continue;
                    double denominator = (b.z - c.z) * (double)(a.x - c.x) + (c.x - b.x) * (double)(a.z - c.z);
                    if (Math.abs(denominator) < .00000001) continue;
                    double u = ((b.z - c.z) * (double)(x - c.x) + (c.x - b.x) * (double)(z - c.z)) / denominator;
                    double v = ((c.z - a.z) * (double)(x - c.x) + (a.x - c.x) * (double)(z - c.z)) / denominator;
                    if (u >= -.0001 && v >= -.0001 && u + v <= 1.0001)
                        ceiling = Math.min(ceiling, (float)(u * a.y + v * b.y + (1 - u - v) * c.y));
                }
            }
        return ceiling;
    }

    private static void worldVertex(com.wurmonline.math.Vector3f point, java.nio.FloatBuffer vertices,
            int index, com.wurmonline.client.renderer.Matrix render) {
        point.set(vertices.get(index * 3), vertices.get(index * 3 + 1), vertices.get(index * 3 + 2));
        render.transformInPlace(point);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
