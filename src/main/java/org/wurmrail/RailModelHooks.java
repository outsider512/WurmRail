package org.wurmrail;

import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Install model selection without loading Item, Player, or any gameplay helper. */
public final class RailModelHooks {
    private static boolean installed;
    private RailModelHooks() { }

    public static synchronized void init() {
        if (installed) return;
        try {
            CtClass item = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item");
            item.getDeclaredMethod("getModelName").insertBefore(
                    "{ if (org.wurmrail.RailModelRegistry.isCustomTemplate(this.getTemplateId())) {"
                    + " return org.wurmrail.RailModelRegistry.modelName(this.getWurmId(), this.getTemplateId(),"
                    + " this.getAuxData(), this.getData1()); } }");
            // VolaTile marks deck vehicles as floor zero. The moving-item
            // spawn packet must still carry Z for clients joining mid-crossing.
            CtClass communicator = HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Communicator");
            CtClass zone = HookManager.getInstance().getClassPool().get("com.wurmonline.server.zones.VirtualZone");
            for (String method : new String[]{"sendMoveMovingItem", "sendMoveMovingItemAndSetZ"})
                zone.getDeclaredMethod(method).insertBefore(
                        "{ if (org.wurmrail.RailModelRegistry.suppressNativeRailMove($1)) return; }");
            for (javassist.CtMethod method : zone.getDeclaredMethods("addItem")) {
                if (method.getParameterTypes().length != 4) continue;
                method.insertBefore("{ if (org.wurmrail.RailModelRegistry.isTrestle($1.getTemplateId())) $4=false; }");
            }
            for (javassist.CtMethod method : communicator.getDeclaredMethods("sendNewMovingItem")) {
                if (method.getParameterTypes().length != 15) continue;
                method.insertBefore("{ float h = org.wurmrail.RailModelRegistry.elevatedCarHeight($1);"
                        + " if (java.lang.Float.isFinite(h)) { $7 = h; $11 = false; }"
                        + " else if ($8 > 0L && org.wurmrail.RailModelRegistry.isRailVehicleModel($4)) $11 = false; }");
            }
            CtClass queued = HookManager.getInstance().getClassPool().get("com.wurmonline.server.players.PlayerCommunicatorQueued");
            queued.addInterface(queued.getClassPool().get("org.wurmrail.RailConsistTransport$PacketQueue"));
            queued.addMethod(CtNewMethod.make(
                    "public void wurmRailQueueConsist(int channel,java.nio.ByteBuffer payload) {"
                    + " if (!this.player.hasLink()) return;"
                    + " java.nio.ByteBuffer packet=this.getBuffer(6+payload.remaining());"
                    + " packet.put((byte)-100); packet.put((byte)1); packet.putInt(channel);"
                    + " packet.put(payload); this.addMessageToQueue(packet); }",queued));
            // PlayerMessage looks up every queued opcode for logging.
            queued.getClassPool().get("com.wurmonline.server.utils.ProtocolUtilities")
                    .getDeclaredMethod("getDescriptionForCommand")
                    .insertBefore("{ if ($1==(byte)-100) return \"ModComm\"; }");
            // The shipped queued overload allocates 14 bytes and writes XYZ
            // then heading. Opcode 72 is 22 bytes and the client reads Z, X,
            // heading, Y, as written by the native non-queued communicator.
            queued.getMethod("sendMoveMovingItemAndSetZ","(JFFFI)V").setBody(
                    "{ if (!this.player.hasLink()) return;"
                    + " try { java.nio.ByteBuffer packet=this.getBuffer(22);"
                    + " packet.put((byte)72); packet.putLong($1); packet.putFloat($4);"
                    + " packet.putFloat($2); packet.put((byte)$5); packet.putFloat($3);"
                    + " this.addMessageToQueue(packet); }"
                    + " catch (java.lang.Exception failure) {"
                    + " logger.log(java.util.logging.Level.WARNING,\"Could not queue moving item position\",failure);"
                    + " this.player.setLink(false); } }");
            for (javassist.CtMethod method : queued.getDeclaredMethods("sendNewMovingItem")) {
                if (method.getParameterTypes().length != 14) continue;
                method.insertBefore("{ float h = org.wurmrail.RailModelRegistry.elevatedCarHeight($1);"
                        + " if (java.lang.Float.isFinite(h)) { $6 = h; $10 = false; } }");
            }
            guardNativeBoarding();
            installed = true;
        } catch (Exception e) {
            throw new IllegalStateException("Could not install custom rail model selection", e);
        }
    }

    private static void guardNativeBoarding() throws Exception {
        CtClass behaviour = HookManager.getInstance().getClassPool().get("com.wurmonline.server.behaviours.VehicleBehaviour");
        CtMethod nativeBoard = behaviour.getDeclaredMethod("addPassenger");
        CtMethod board = CtNewMethod.copy(nativeBoard, behaviour, null);
        nativeBoard.setName("wurmRailNativeAddPassenger");
        board.setBody("{ if (!org.wurmrail.RailModelRegistry.isRailVehicleTemplate($3.getTemplateId()))"
                + " return wurmRailNativeAddPassenger($$);"
                + " synchronized ($2) {"
                + " if ($1.getVehicle()!=-10L) return false;"
                + " if ($4>=0 && $4<$2.seats.length && $2.seats[$4].type==1 && !$2.seats[$4].isOccupied())"
                + " return wurmRailNativeAddPassenger($$);"
                + " for (int i=0;i<$2.seats.length;i++)"
                + " if ($2.seats[i].type==1 && !$2.seats[i].isOccupied()) return wurmRailNativeAddPassenger($1,$2,$3,i);"
                + " return false; } }");
        behaviour.addMethod(board);
    }
}
