import com.wurmonline.client.game.PlayerObj;
import com.wurmonline.client.game.PlayerPosition;
import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.PlayerBodyRenderable;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.timing.IFloat;
import org.wurmrail.client.RailVehicleMotion;

/** The actual local player, World camera inputs and player-body getters between game ticks. */
public final class RailVehiclePlayerClientSmoke {
    private static int assertions;
    private RailVehiclePlayerClientSmoke() { }

    public static void run() throws Exception {
        World old=CellRenderable.world;
        try {
            for(int layer:new int[]{0,-1})
                for(String model:new String[]{"model.wurmrail.locomotive.","model.wurmrail.cart.large.","model.wurmrail.tankcar.","model.wurmrail.logcar."})
                    for(boolean controller:new boolean[]{false,true})
                        for(float[] seat:new float[][]{{.65f,-.48f,.55f},{-.65f,.48f,.75f},{0,0,0}})
                            movingLocalPlayer(model,layer,controller,seat);
            nativeFallbacks();sharedFramePose();nativePassengerBoarding();loadedMountCamera();
            System.out.println("PASS native local-player and World camera follow the same continuous rail pose between ticks and share one frame timestamp with the rendered train, curved motion and changing tilt, locomotive/cart/tank/log seats, surface/cave, controller/passenger, native eye height, unchanged simulation/view state, detach and ordinary-mount fallbacks ("+assertions+" assertions)");
        } finally { CellRenderable.world=old; }
    }

    private static void movingLocalPlayer(String model,int layer,boolean controller,float[] seat) throws Exception {
        World world=RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
        CreatureCellRenderable carrier=RailVehicleTerrainClientSmoke.makeVehicle(model,layer,46,46,5,180);
        PlayerObj player=player(world,carrier,controller,seat);
        PlayerPosition pos=player.getPos();
        PlayerBodyRenderable body=(PlayerBodyRenderable)allocate(PlayerBodyRenderable.class);
        set(body,"player",player);
        // Deliberately leave local simulation at stale tick coordinates. Camera
        // sampling must use the current rail playback without rewriting them.
        float[] nativeBefore={pos.getX(.3f),pos.getY(.3f),pos.getH(.3f),player.getXRot(.3f),player.getYRot(.3f)};
        carrier.move(50,42,6,90);
        long start=System.nanoTime()+1000000000L;
        RailVehicleMotion motion=new RailVehicleMotion(46,46,5,0,start);
        motion.accept(50,42,6,90,start+1000000000L);
        set(carrier,"wurmRailMotion",motion);set(carrier,"wurmRailHasExplicitHeight",true);
        IFloat[] pose=(IFloat[])get(carrier,"interTickPos");
        double[] previous=null;
        for(int frame=0;frame<=60;frame++) {
            long frameTime=start+250000000L+frame*1000000000L/60;
            call(carrier,"wurmRailApplyMotion",new Class<?>[]{long.class},frameTime);
            // Keep the actual smoothed tilt fields at a deterministic frame
            // value. This isolates camera attachment from terrain-filter QA.
            float pitch=-12+frame*.4f,roll=8-frame*.2f;
            pose[4].reallySetValue(pitch);pose[5].reallySetValue(roll);
            set(carrier,"wurmRailTiltReady",true);set(carrier,"wurmRailTiltPitch",(double)pitch);
            set(carrier,"wurmRailTiltRoll",(double)roll);set(carrier,"wurmRailTiltNanos",start+100000000000L);
            float x=pose[0].getValue(),y=pose[1].getValue(),h=pose[2].getValue(),yaw=pose[3].getValue();
            double[] full=rotateSeat(seat,yaw,pitch,roll),flat=rotateSeat(seat,yaw,0,0);
            double[] base={x+flat[0],y+flat[1],h+seat[2]};
            double[] correction={full[0]-flat[0],full[1]-flat[1],full[2]-seat[2]};
            for(float alpha:new float[]{0,.25f,.5f,.75f,1}) {
                set(world,"tickFraction",alpha);
                near(player.getX(alpha),base[0],"local player X uses continuous carrier playback");
                near(player.getY(alpha),base[1],"local player Y uses continuous carrier playback");
                near(player.getH(alpha),base[2]+1.5,"native sound-listener eye height is retained");
                near(world.getPlayerPosX(),base[0],"World camera base X bypasses stale local tick interpolation");
                near(world.getPlayerPosY(),base[1],"World camera base Y bypasses stale local tick interpolation");
                near(world.getPlayerPosH(),base[2],"World camera base H excludes eye height");
                near(player.getCarrierXPosOffset(alpha),correction[0],"current pitch/roll seat correction X");
                near(player.getCarrierYPosOffset(alpha),correction[1],"current pitch/roll seat correction Y");
                near(player.getCarrierHPosOffset(alpha),correction[2],"current pitch/roll seat correction H");
                near(world.getPlayerPosX()+player.getCarrierXPosOffset(alpha),x+full[0],"WorldRender final camera X stays fixed to the actual seat");
                near(world.getPlayerPosY()+player.getCarrierYPosOffset(alpha),y+full[1],"WorldRender final camera Y stays fixed to the actual seat");
                near(world.getPlayerPosH()+player.getCarrierHPosOffset(alpha)+player.getCameraHeightOffSet(),h+full[2]+1.5,"WorldRender final camera H includes tilted seat exactly once");
                near(body.getXPosValue(alpha),base[0],"native player body follows World base X");
                near(body.getYPosValue(alpha),base[1],"native player body follows World base Y");
                near(body.getHPosValue(alpha),base[2],"native player body follows World base H");
            }
            near(pos.getX(.3f),nativeBefore[0],"render-only camera follow preserves simulation X");
            near(pos.getY(.3f),nativeBefore[1],"render-only camera follow preserves simulation Y");
            near(pos.getH(.3f),nativeBefore[2],"render-only camera follow preserves simulation H");
            near(player.getXRot(.3f),nativeBefore[3],"rail camera attachment preserves player look pitch");
            near(player.getYRot(.3f),nativeBefore[4],"rail camera attachment preserves player look yaw");
            double[] current={world.getPlayerPosX()+player.getCarrierXPosOffset(1),world.getPlayerPosY()+player.getCarrierYPosOffset(1),world.getPlayerPosH()+player.getCarrierHPosOffset(1)};
            if(previous!=null)check(distance(current,previous)<.2,"consecutive rendered seat frames have no tick-sized attachment jumps");
            previous=current;
        }
        set(player,"carrierCreature",null);
        for(float alpha:new float[]{0,.3f,1}) {
            set(world,"tickFraction",alpha);
            near(player.getX(alpha),pos.getX(alpha),"dismount immediately restores native player X");
            near(player.getY(alpha),pos.getY(alpha),"dismount immediately restores native player Y");
            near(player.getH(alpha),pos.getH(alpha)+1.5,"dismount restores native eye position");
            near(world.getPlayerPosX(),pos.getX(alpha),"dismount restores native World interpolation");
            near(player.getCarrierXPosOffset(alpha)+player.getCarrierYPosOffset(alpha)+player.getCarrierHPosOffset(alpha),0,"dismount clears render attachment offsets");
        }
    }

    private static void nativeFallbacks() throws Exception {
        World world=RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
        for(String model:new String[]{"model.cart.large.","model.wurmrail.cartoon.","model.wurmrail.locomotivefake."}) {
            CreatureCellRenderable carrier=RailVehicleTerrainClientSmoke.makeVehicle(model,0,46,46,5,180);
            PlayerObj player=player(world,carrier,false,new float[]{1,2,3});
            for(float alpha:new float[]{0,.25f,.5f,1}) {
                set(world,"tickFraction",alpha);PlayerPosition p=player.getPos();
                near(player.getX(alpha),p.getX(alpha),"ordinary mounts retain native player interpolation");
                near(player.getY(alpha),p.getY(alpha),"ordinary mounts retain native player Y");
                near(player.getH(alpha),p.getH(alpha)+1.5,"ordinary mounts retain native eye height");
                near(world.getPlayerPosX(),p.getX(alpha),"ordinary mounts retain native World camera X");
                near(world.getPlayerPosY(),p.getY(alpha),"ordinary mounts retain native World camera Y");
                near(world.getPlayerPosH(),p.getH(alpha),"ordinary mounts retain native World camera H");
                near(player.getCarrierXPosOffset(alpha),10+2*alpha,"ordinary mount X correction keeps native interpolation");
                near(player.getCarrierYPosOffset(alpha),20+2*alpha,"ordinary mount Y correction keeps native interpolation");
                near(player.getCarrierHPosOffset(alpha),30+2*alpha,"ordinary mount H correction keeps native interpolation");
            }
        }
    }

    /** A slow camera/model render must not sample different places along one train path. */
    private static void sharedFramePose() throws Exception {
        World world=RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
        set(world,"hud",allocate(com.wurmonline.client.renderer.gui.HeadsUpDisplay.class));
        CreatureCellRenderable carrier=RailVehicleTerrainClientSmoke.makeVehicle("model.wurmrail.locomotive.",0,46,46,5,180);
        float[] seat={.65f,-.48f,.55f};PlayerObj player=player(world,carrier,false,seat);
        long before=System.nanoTime();world.setSecondsPlayed(.35f);long after=System.nanoTime();
        long stamp=(Long)call(world,"wurmRailRenderTime",new Class<?>[0]);
        check(stamp>=before&&stamp<=after,"native frame fraction update captures a monotonic frame timestamp");
        RailVehicleMotion motion=new RailVehicleMotion(46,46,5,0,stamp-400000000L);
        motion.accept(50,42,6,90,stamp+100000000L);
        set(carrier,"wurmRailMotion",motion);set(carrier,"wurmRailHasExplicitHeight",true);
        float[] first=motion.sample(stamp);
        IFloat[] pose=(IFloat[])get(carrier,"interTickPos");
        pose[4].reallySetValue(8);pose[5].reallySetValue(-5);
        set(carrier,"wurmRailTiltReady",true);set(carrier,"wurmRailTiltPitch",8d);set(carrier,"wurmRailTiltRoll",-5d);
        set(carrier,"wurmRailTiltNanos",stamp+100000000000L);
        checkFrame(world,player,carrier,seat,first,8,-5);
        Thread.sleep(20);
        check((Long)call(world,"wurmRailRenderTime",new Class<?>[0])==stamp,"one native frame keeps the same pose timestamp despite rendering work");
        checkFrame(world,player,carrier,seat,first,8,-5);
        world.setSecondsPlayed(.8f);
        long next=(Long)call(world,"wurmRailRenderTime",new Class<?>[0]);
        check(next>stamp,"the next native frame refreshes the shared timestamp");
        float[] second=motion.sample(next);
        check(Math.hypot(second[0]-first[0],second[1]-first[1])>.05,"next frame advances real continuous rail playback");
        checkFrame(world,player,carrier,seat,second,8,-5);
        System.out.println("PASS native shared render-frame timestamp for local camera, seats and train models");
    }

    private static void checkFrame(World world,PlayerObj player,CreatureCellRenderable carrier,float[] local,float[] p,float pitch,float roll) throws Exception {
        float nativeYaw=(180-p[3]+360)%360;
        double[] full=rotateSeat(local,nativeYaw,pitch,roll);
        for(float alpha:new float[]{0,.5f,1}) {
            near(world.getPlayerPosX()+player.getCarrierXPosOffset(alpha),p[0]+full[0],"camera X and train render share one complete frame pose");
            near(world.getPlayerPosY()+player.getCarrierYPosOffset(alpha),p[1]+full[1],"camera Y and train render share one complete frame pose");
            near(world.getPlayerPosH()+player.getCarrierHPosOffset(alpha),p[2]+full[2],"camera H and train render share one complete frame pose");
            call(carrier,"setRenderPos",new Class<?>[]{float.class},alpha);
            near((Float)get(carrier,"x"),p[0],"later native model rendering uses the camera frame X");
            near((Float)get(carrier,"y"),p[1],"later native model rendering uses the camera frame Y");
            near((Float)get(carrier,"h"),p[2],"later native model rendering uses the camera frame H");
            near(carrier.getXPosValue(alpha),p[0],"carrier X getter retains the shared frame timestamp");
            near(carrier.getYPosValue(alpha),p[1],"carrier Y getter retains the shared frame timestamp");
            near(carrier.getHPosValue(alpha),p[2],"carrier H getter retains the shared frame timestamp");
        }
    }

    /** Exercise the native attach operation, not just a preassigned carrier field. */
    private static void nativePassengerBoarding() throws Exception {
        for(String wagonModel:new String[]{"model.wurmrail.cart.large.","model.wurmrail.tankcar.","model.wurmrail.logcar."})
            for(int layer:new int[]{0,-1})for(boolean occupiedWagon:new boolean[]{false,true}) {
            World world=RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
            CreatureCellRenderable engine=RailVehicleTerrainClientSmoke.makeVehicle("model.wurmrail.locomotive.",layer,54,54,5,90);
            CreatureCellRenderable wagon=RailVehicleTerrainClientSmoke.makeVehicle(wagonModel,layer,50,54,5,90);
            CreatureCellRenderable carrier=occupiedWagon?wagon:engine;
            float[] seat={.55f,-.7f,.95f};PlayerObj player=player(world,null,false,seat);
            set(player,"movementChecker",allocate(Class.forName("com.wurmonline.client.game.PlayerMovementCheck")));
            PlayerBodyRenderable body=(PlayerBodyRenderable)allocate(PlayerBodyRenderable.class);
            set(body,"player",player);set(body,"mountOffset",new com.wurmonline.client.renderer.cell.MountOffsets());
            set(player,"playerBody",body);
            Object collision=allocate(com.wurmonline.client.collision.advanced.CollisionManagerAdvanced.class);
            set(collision,"reqPos",new javax.vecmath.Vector3f());set(world,"collisionManager",collision);
            // A loaded model without a mount null uses the server seat offsets.
            // The native attach still traverses model lookup, attached-body and
            // passenger-controller state, movement checker and collision setup.
            for(CreatureCellRenderable car:new CreatureCellRenderable[]{engine,wagon}) {
                Object data=car.getModelWrapper().getModelData();
                set(get(data,"model"),"nullAttachmentList",new java.util.ArrayList<Object>());
            }
            long start=System.nanoTime()+10000000000L;
            RailVehicleMotion front=new RailVehicleMotion(54,54,5,90,start);
            RailVehicleMotion rear=new RailVehicleMotion(50,54,5,90,start);
            set(engine,"wurmRailMotion",front);set(wagon,"wurmRailMotion",rear);
            set(engine,"wurmRailHasExplicitHeight",true);set(wagon,"wurmRailHasExplicitHeight",true);
            player.attachTo(carrier,seat[0],seat[1],seat[2],(byte)1);
            check(player.getCarrierCreature()==carrier&&!player.isCarrierController(),"native passenger attach retains the selected rail car without vehicle steering");
            check((Boolean)get(carrier,"hasPlayerBodyAttached"),"native attach sets the carrier's local body flag");
            check(front==get(engine,"wurmRailMotion")&&rear==get(wagon,"wurmRailMotion"),"native boarding keeps both authoritative playback buffers intact");
            for(int frame=0;frame<=60*8;frame++) {
                double t=frame/60d;
                double travel=t<=2?t*5:t<=3?10:t<=6?10-(t-3)*5:-5;
                long now=start+Math.round(t*1e9);
                front.accept((float)(54+travel),54,5,90,now);
                rear.accept((float)(50+travel),54,5,90,now);
                set(world,"wurmRailFrameNanos",now);
                // Mounted camera reads the occupied wagon first; the engine
                // can be rendered later in the same native frame.
                double cameraX=world.getPlayerPosX()+player.getCarrierXPosOffset(.5f);
                call(engine,"setRenderPos",new Class<?>[]{float.class},.5f);
                call(wagon,"setRenderPos",new Class<?>[]{float.class},.5f);
                double ex=(Float)get(engine,"x"),wx=(Float)get(wagon,"x");
                near(ex-wx,4,"actual boarded passenger keeps the physical 4m consist gap through forward/stop/reverse");
                near(cameraX,(occupiedWagon?wx:ex)-seat[0],"actual boarded passenger camera uses its car's rendered seat pose");
                check(front==get(engine,"wurmRailMotion")&&rear==get(wagon,"wurmRailMotion"),"rendering an occupied car never replaces its motion buffer");
            }
        }
        System.out.println("PASS native PlayerObj.attachTo passenger boarding, collision setup and consist reversal keep the rendered 4m gap and camera seat");
    }

    /** Loaded COLLADA NullMount seats use a separate native camera transform. */
    private static void loadedMountCamera() throws Exception {
        com.wurmonline.client.resources.Resources resources=new com.wurmonline.client.resources.Resources(
                new java.io.File("packs"),java.util.Collections.<String>emptyList());
        for(String model:new String[]{"model.wurmrail.cart","model.wurmrail.locomotive","model.wurmrail.tankcar","model.wurmrail.logcar"}) {
            com.wurmonline.client.resources.ResourceUrl url=resources.getResource(model);
            com.wurmonline.client.renderer.model.collada.importer.ColladaModel source;
            try(java.io.InputStream stream=url.openStream()) {
                source=com.wurmonline.client.renderer.model.collada.importer.ColladaImporter.loadModel(url.toString(),stream);
            }
            String otherModel=model.equals("model.wurmrail.cart")?"model.wurmrail.locomotive":"model.wurmrail.cart";
            com.wurmonline.client.resources.ResourceUrl otherUrl=resources.getResource(otherModel);
            com.wurmonline.client.renderer.model.collada.importer.ColladaModel otherSource;
            try(java.io.InputStream stream=otherUrl.openStream()) {
                otherSource=com.wurmonline.client.renderer.model.collada.importer.ColladaImporter.loadModel(otherUrl.toString(),stream);
            }
            for(int layer:new int[]{0,-1})for(int place=1;place<=4;place++) {
                World world=RailVehicleTerrainClientSmoke.setupWorld(0,0,5,0,0,5);
                CreatureCellRenderable car=RailVehicleTerrainClientSmoke.makeVehicle(model+".",layer,54,54,5,180);
                com.wurmonline.client.renderer.model.collada.ColladaModelData data=
                        new com.wurmonline.client.renderer.model.collada.ColladaModelData(url,
                                new com.wurmonline.client.renderer.model.collada.importer.ColladaModel(source));
                set(data,"bounds",source.calculateBoundingBox());set(car.getModelWrapper(),"modelData",data);
                set(car,"xScale",1f);set(car,"hScale",1f);set(car,"yScale",1f);
                PlayerObj player=player(world,null,false,new float[]{.55f,-.7f,.95f});
                set(player,"movementChecker",allocate(Class.forName("com.wurmonline.client.game.PlayerMovementCheck")));
                PlayerBodyRenderable body=(PlayerBodyRenderable)allocate(PlayerBodyRenderable.class);
                com.wurmonline.client.renderer.cell.MountOffsets mount=new com.wurmonline.client.renderer.cell.MountOffsets();
                set(body,"player",player);set(body,"mountOffset",mount);set(player,"playerBody",body);
                Object collision=allocate(com.wurmonline.client.collision.advanced.CollisionManagerAdvanced.class);
                set(collision,"reqPos",new javax.vecmath.Vector3f());set(world,"collisionManager",collision);
                player.attachTo(car,.55f,-.7f,.95f,(byte)place);
                check(mount.hasCarrierCreatureMountJoint(),"actual imported model attaches native NullMount"+place);
                near((Float)get(player,"xOffset"),0,"a loaded native mount replaces the fallback server X seat");
                near((Float)get(player,"yOffset"),0,"a loaded native mount replaces the fallback server Y seat");
                near((Float)get(player,"hOffset"),0,"a loaded native mount replaces the fallback server H seat");
                long start=System.nanoTime()+10000000000L;
                RailVehicleMotion motion=new RailVehicleMotion(54,54,5,0,start);
                motion.accept(56,52,5,90,start+1000000000L);
                set(car,"wurmRailMotion",motion);set(car,"wurmRailHasExplicitHeight",true);
                Object render=allocate(com.wurmonline.client.renderer.WorldRender.class);set(render,"world",world);set(world,"worldRenderer",render);
                for(int frame=0;frame<=60;frame++) {
                    long now=start+250000000L+frame*1000000000L/60;
                    set(world,"wurmRailFrameNanos",now);
                    // Leave cached native yaw/pitch/roll at the previous frame,
                    // as WorldRender prepares camera matrices before car models.
                    IFloat[] pose=(IFloat[])get(car,"interTickPos");
                    float pitch=-12+frame*.4f,roll=8-frame*.2f;
                    pose[4].reallySetValue(pitch);pose[5].reallySetValue(roll);
                    set(car,"wurmRailTiltReady",true);set(car,"wurmRailTiltPitch",(double)pitch);
                    set(car,"wurmRailTiltRoll",(double)roll);set(car,"wurmRailTiltNanos",now+100000000000L);
                    double x=world.getPlayerPosX()+player.getCarrierXPosOffset(.5f);
                    double y=world.getPlayerPosY()+player.getCarrierYPosOffset(.5f);
                    double h=world.getPlayerPosH()+player.getCarrierHPosOffset(.5f)+player.getCameraHeightOffSet();
                    set(render,"cameraX",(float)x);set(render,"cameraY",(float)h);set(render,"cameraZ",(float)y);
                    com.wurmonline.math.Vector3f joint=rotatedMount(mount,pose[3].getValue(),pitch,roll);
                    for(boolean reflection:new boolean[]{false,true}) {
                        com.wurmonline.math.Transform view=new com.wurmonline.math.Transform();
                        call(render,reflection?"getReflectionViewTransform":"getWorldViewTransform",
                                new Class<?>[]{com.wurmonline.math.Transform.class},view);
                        near(view.translation.x,-x-joint.x,"loaded mount camera X shares current model yaw/pitch/roll before model preparation");
                        near(view.translation.z,-y-joint.z,"loaded mount camera Y shares current model yaw/pitch/roll before model preparation");
                        near(view.translation.y,reflection?h+joint.y:-h-joint.y,"loaded mount camera height and water reflection use the complete rotated seat");
                    }
                    com.wurmonline.math.Transform localBody=new com.wurmonline.math.Transform();
                    mount.addRotatedMountOffset(localBody,car,false,false);
                    near(localBody.translation.x,joint.x,"native local-body mount uses the same current seat X as the camera");
                    near(localBody.translation.y,joint.y,"native local-body mount uses the same current seat height as the camera");
                    near(localBody.translation.z,joint.z,"native local-body mount uses the same current seat Y as the camera");
                    near(car.getRot(),pose[3].getValue(),"native cached model yaw is current before the camera's loaded mount is applied");
                    long playback=(Long)get(motion,"lastPlayback");
                    call(car,"setRenderPos",new Class<?>[]{float.class},.5f);
                    check((Long)get(motion,"lastPlayback")==playback,"extra local mount samples cannot advance only the occupied car's playback clock");
                    near((Float)get(car,"x"),x,"loaded mount camera leaves authoritative car X unchanged");
                    near((Float)get(car,"y"),y,"loaded mount camera leaves authoritative car Y unchanged");
                }
                // Real native disembark clears the carrier, loaded mount and
                // attached-body flag. Keep socket/UI outputs at their boundary.
                Object client=allocate(com.wurmonline.client.WurmClientBase.class);
                MountConnection connection=new MountConnection();set(client,"serverConnection",connection);set(world,"client",client);
                Object hud=allocate(com.wurmonline.client.renderer.gui.HeadsUpDisplay.class);
                set(hud,"selectBar",allocate(com.wurmonline.client.renderer.gui.SelectBar.class));set(world,"hud",hud);
                PlayerPosition position=player.getPos();
                player.teleportTo(position.getX(),position.getH(),position.getY(),0,true,layer,true,(byte)2,false,1);
                check(player.getCarrierCreature()==null&&!mount.hasCarrierCreatureMountJoint()
                        &&!(Boolean)get(car,"hasPlayerBodyAttached")&&connection.acknowledgements==1,
                        "native disembark clears loaded mount state and acknowledges its position");
                com.wurmonline.math.Transform detached=new com.wurmonline.math.Transform();
                call(render,"getWorldViewTransform",new Class<?>[]{com.wurmonline.math.Transform.class},detached);
                near(detached.translation.x,-(Float)get(render,"cameraX"),"dismount immediately removes loaded mount camera translation");
                CreatureCellRenderable other=RailVehicleTerrainClientSmoke.makeVehicle(otherModel+".",layer,60,52,5,90);
                com.wurmonline.client.renderer.model.collada.ColladaModelData otherData=
                        new com.wurmonline.client.renderer.model.collada.ColladaModelData(otherUrl,
                                new com.wurmonline.client.renderer.model.collada.importer.ColladaModel(otherSource));
                set(otherData,"bounds",otherSource.calculateBoundingBox());set(other.getModelWrapper(),"modelData",otherData);
                set(other,"xScale",1f);set(other,"hScale",1f);set(other,"yScale",1f);
                RailVehicleMotion partner=new RailVehicleMotion(60,52,5,90,start+1000000000L);
                set(other,"wurmRailMotion",partner);set(other,"wurmRailHasExplicitHeight",true);
                prepareModelFixture(car);prepareModelFixture(other);
                double nextPacket=.05;
                for(int frame=0;frame<=120;frame++) {
                    double time=.25+frame/60d;long now=start+1000000000L+Math.round(time*1e9);
                    while(nextPacket<=time+.0000001) {
                        float travel=(float)(Math.sin(nextPacket*2)*2);
                        long packet=start+1000000000L+Math.round(nextPacket*1e9);
                        motion.accept(56+travel,52,5,90,packet);partner.accept(60+travel,52,5,90,packet);
                        nextPacket+=.05;
                    }
                    set(world,"wurmRailFrameNanos",now);
                    if(frame==60) {
                        long oldPlayback=(Long)get(motion,"lastPlayback");
                        player.attachTo(other,.55f,-.7f,.95f,(byte)(place%4+1));
                        check(mount.hasCarrierCreatureMountJoint()&&mount.getMountPlace().equals("NullMount"+(place%4+1)),"native reboarding refreshes the selected loaded seat without stale prior-car offsets");
                        check((Long)get(motion,"lastPlayback")==oldPlayback,"reboarding the other vehicle leaves the departed car's playback state untouched");
                    }
                    double cameraX=world.getPlayerPosX()+player.getCarrierXPosOffset(.5f);
                    double cameraY=world.getPlayerPosY()+player.getCarrierYPosOffset(.5f);
                    double cameraH=world.getPlayerPosH()+player.getCarrierHPosOffset(.5f)+player.getCameraHeightOffSet();
                    set(render,"cameraX",(float)cameraX);set(render,"cameraY",(float)cameraH);set(render,"cameraZ",(float)cameraY);
                    com.wurmonline.math.Transform view=new com.wurmonline.math.Transform();
                    call(render,"getWorldViewTransform",new Class<?>[]{com.wurmonline.math.Transform.class},view);
                    com.wurmonline.math.Vector3f joint=frame<60?new com.wurmonline.math.Vector3f():rotatedMount(mount,90,0,0);
                    near(view.translation.x,-cameraX-joint.x,"moving after detach/reboard has no stale mount camera X");
                    near(view.translation.z,-cameraY-joint.z,"moving after detach/reboard has no stale mount camera Y");
                    near(view.translation.y,-cameraH-joint.y,"moving after detach/reboard has no stale mount camera height");
                    com.wurmonline.math.Vector3f a=modelPosition(car),b=modelPosition(other);
                    near(b.x-a.x,4,"native model matrices preserve 4m spacing while moving forward/reverse after detach and other-car reboard");
                    near(a.z,52,"departed car's native model matrix follows its own path after dismount");
                    near(b.z,52,"reboarded car's native model matrix follows its own path");
                    check(((Long)get(motion,"lastPlayback")).longValue()==((Long)get(partner,"lastPlayback")).longValue(),"both motion buffers keep one playback time after detach/reboard and extra mounted camera reads");
                }
                com.wurmonline.client.renderer.cell.MountOffsets unrelated=new com.wurmonline.client.renderer.cell.MountOffsets();
                unrelated.setHasCarrierCreatureMountJoint(true);unrelated.setMountOffsetX(.7f);unrelated.setMountOffsetH(.9f);unrelated.setMountOffsetY(-.4f);
                set(other,"yaw",30f);
                checkNativeMountFallback(unrelated,other,"an unrelated avatar's mount preserves native yaw-only translation");
                set(other,"modelName",new com.wurmonline.client.util.SecureStrings("model.cart.large."));
                set(get(other,"creature"),"modelName",new com.wurmonline.client.util.SecureStrings("model.cart.large."));
                checkNativeMountFallback(mount,other,"an ordinary mounted cart preserves native yaw-only translation");
            }
        }
        System.out.println("PASS imported NullMount passenger seats and native view/reflection matrices use current continuous pose through camera-first rendering, tilt, detach and reboard");
    }
    private static final class MountConnection extends com.wurmonline.client.comm.SimpleServerConnectionClass {
        int acknowledgements;
        MountConnection() {super(null);}
        @Override public void sendTeleportOkNew(int counter) {acknowledgements++;}
    }
    private static void prepareModelFixture(CreatureCellRenderable car) throws Exception {
        set(car,"renderState",new com.wurmonline.client.renderer.backend.RenderState());
        set(car,"renderColor",new com.wurmonline.client.renderer.Color(1,1,1,1));
        set(car,"renderTransform",new com.wurmonline.math.Transform());set(car,"rotQuat",new com.wurmonline.math.Quaternion());
        set(car,"renderModel",new com.wurmonline.client.renderer.Matrix());
    }
    private static com.wurmonline.math.Vector3f modelPosition(CreatureCellRenderable car) throws Exception {
        call(car,"setRenderPos",new Class<?>[]{float.class},.5f);
        call(car,"prepareRender",new Class<?>[0]);
        com.wurmonline.math.Vector3f result=new com.wurmonline.math.Vector3f();
        ((com.wurmonline.client.renderer.Matrix)get(car,"renderModel")).transformInPlace(result);return result;
    }
    private static void checkNativeMountFallback(com.wurmonline.client.renderer.cell.MountOffsets mount,CreatureCellRenderable carrier,String message) {
        com.wurmonline.math.Vector3f expected=rotatedMount(mount,carrier.getRot(),0,0);
        for(boolean negate:new boolean[]{false,true})for(boolean reflection:new boolean[]{false,true}) {
            com.wurmonline.math.Transform transform=new com.wurmonline.math.Transform();
            mount.addRotatedMountOffset(transform,carrier,negate,reflection);
            float sign=negate?-1f:1f;
            near(transform.translation.x,sign*expected.x,message+" X");
            near(transform.translation.y,(reflection?-sign:sign)*expected.y,message+" height");
            near(transform.translation.z,sign*expected.z,message+" Y");
        }
    }
    private static com.wurmonline.math.Vector3f rotatedMount(com.wurmonline.client.renderer.cell.MountOffsets mount,float yaw,float pitch,float roll) {
        com.wurmonline.math.Quaternion rotation=new com.wurmonline.math.Quaternion().fromAngles(0,(float)Math.toRadians(yaw),0);
        rotation.mult(new com.wurmonline.math.Quaternion().fromAngles((float)Math.toRadians(pitch),0,0));
        rotation.mult(new com.wurmonline.math.Quaternion().fromAngles(0,0,(float)Math.toRadians(roll)));
        com.wurmonline.math.Vector3f local=new com.wurmonline.math.Vector3f(mount.getMountOffsetX(),mount.getMountOffsetH(),mount.getMountOffsetY());
        return rotation.rotate(local,new com.wurmonline.math.Vector3f());
    }

    private static PlayerObj player(World world,CreatureCellRenderable carrier,boolean controller,float[] seat) throws Exception {
        PlayerObj player=(PlayerObj)allocate(PlayerObj.class);PlayerPosition p=new PlayerPosition();
        p.setX(100);p.setY(200);p.setH(300);p.moveFast();p.setX(104);p.setY(204);p.setH(304);p.move();
        ((IFloat)get(p,"xRot")).reallySetValue(123);((IFloat)get(p,"yRot")).reallySetValue(17);
        set(player,"pos",p);set(player,"worldused",world);set(world,"player",player);
        set(player,"carrierCreature",carrier);set(player,"isCarrierController",controller);set(player,"cameraHeightOffSet",1.5f);
        set(player,"xOffset",seat[0]);set(player,"yOffset",seat[1]);set(player,"hOffset",seat[2]);
        for(String field:new String[]{"xPosOffset","yPosOffset","hPosOffset"}) {
            int axis=field.charAt(0)=='x'?1:field.charAt(0)=='y'?2:3;
            IFloat offset=new IFloat(axis*10);offset.setValue(axis*10+2);set(player,field,offset);
        }
        return player;
    }
    private static double[] rotateSeat(float[] local,float yaw,float pitch,float roll) {
        double r=Math.toRadians(roll),p=Math.toRadians(pitch),a=Math.toRadians(yaw)+Math.PI/2;
        double rx=local[0],ry=Math.cos(r)*local[1]-Math.sin(r)*local[2],rz=Math.cos(r)*local[2]+Math.sin(r)*local[1];
        double px=Math.cos(p)*rx-Math.sin(p)*rz,pz=Math.cos(p)*rz+Math.sin(p)*rx;
        return new double[]{Math.cos(a)*px+Math.sin(a)*ry,Math.cos(a)*ry-Math.sin(a)*px,pz};
    }
    private static double distance(double[] a,double[] b) {double sum=0;for(int i=0;i<3;i++)sum+=(a[i]-b[i])*(a[i]-b[i]);return Math.sqrt(sum);}
    private static Object allocate(Class<?> type) throws Exception {java.lang.reflect.Method method=RailVehicleTerrainClientSmoke.class.getDeclaredMethod("allocate",Class.class);method.setAccessible(true);return method.invoke(null,type);}
    private static Object get(Object object,String name) throws Exception {return RailVehicleTerrainClientSmoke.get(object,name);}
    private static void set(Object object,String name,Object value) throws Exception {RailVehicleTerrainClientSmoke.set(object,name,value);}
    private static Object call(Object object,String name,Class<?>[] types,Object...values) throws Exception {return RailVehicleTerrainClientSmoke.call(object,name,types,values);}
    private static void near(double actual,double expected,String message) {check(Math.abs(actual-expected)<.0002,message+": expected "+expected+", got "+actual);}
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
}
