package org.wurmrail;

import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import java.util.Collections;
import java.util.List;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModAction;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;

/** Reuse the vanilla tools through independent mod actions. */
final class RailwayTrestleActions implements ModAction, ActionPerformer, BehaviourProvider {
    enum Operation {
        START("Plan Railway Trestle"),
        MARK("Mark Trestle Destination"),
        OPEN("Review Trestle Survey");

        final String label;
        Operation(String label) { this.label = label; }
    }

    interface Api {
        boolean canSurvey(Creature actor);
        boolean hasSurvey(Creature actor);
        void submit(Creature actor, Operation operation, long tool, int x, int y, long target);
    }

    private final Operation operation;
    private final Api api;
    private final ActionEntry entry;

    static void register(Api api) {
        for (Operation operation : Operation.values())
            ModActions.registerAction(new RailwayTrestleActions(operation, api));
    }

    private RailwayTrestleActions(Operation operation, Api api) {
        this.operation = operation;
        this.api = api;
        // Wurm's quick skill-less dispatch discards the active item for custom
        // actions on both tiles and items. Surveying needs the real dioptra.
        entry = ActionEntry.createEntry((short) ModActions.getNextActionId(), operation.label,
                "surveying a railway trestle", new int[0]);
        ModActions.registerAction(entry);
    }

    @Override public ActionPerformer getActionPerformer() { return this; }
    @Override public BehaviourProvider getBehaviourProvider() { return this; }
    @Override public short getActionId() { return entry.getNumber(); }

    @Override public List<ActionEntry> getBehavioursFor(Creature actor, Item tool, Item target) {
        return acceptsItem(actor, tool, target) ? Collections.singletonList(entry) : null;
    }

    @Override public List<ActionEntry> getBehavioursFor(Creature actor, Item tool, int x, int y,
                                                         boolean surface, int tile) {
        return surface && acceptsTile(actor, tool, x, y) ? Collections.singletonList(entry) : null;
    }

    @Override public List<ActionEntry> getBehavioursFor(Creature actor, Item tool, int x, int y,
                                                         boolean surface, int tile, int corner) {
        return corner == 0 ? getBehavioursFor(actor, tool, x, y, surface, tile) : null;
    }

    @Override public boolean action(Action action, Creature actor, Item tool, Item target, short number, float counter) {
        if (number == getActionId()) {
            if (acceptsItem(actor, tool, target))
                api.submit(actor, operation, tool.getWurmId(), target.getTileX(), target.getTileY(), target.getWurmId());
            else explainUnavailable(actor, tool);
        }
        return true;
    }

    @Override public boolean action(Action action, Creature actor, Item target, short number, float counter) {
        if (number == getActionId()) explainUnavailable(actor, null);
        return true;
    }

    @Override public boolean action(Action action, Creature actor, int x, int y, boolean surface,
                                    int tile, short number, float counter) {
        if (number == getActionId()) explainUnavailable(actor, null);
        return true;
    }

    @Override public boolean action(Action action, Creature actor, Item tool, int x, int y, boolean surface,
                                    int heightOffset, int tile, short number, float counter) {
        if (number == getActionId()) {
            // A plain terrain target arrives with Wurm's unset offset (-1).
            boolean ground = surface && (heightOffset == -1 || heightOffset == 0);
            if (ground && acceptsTile(actor, tool, x, y))
                api.submit(actor, operation, tool.getWurmId(), x, y, -10L);
            else if (!ground)
                actor.getCommunicator().sendNormalServerMessage("Choose a surface ground tile for the survey.");
            else explainUnavailable(actor, tool);
        }
        return true;
    }

    private void explainUnavailable(Creature actor, Item tool) {
        String message;
        if (!ownsTool(actor, tool, ItemList.dioptra)) message = "Activate a dioptra in your inventory to survey a railway trestle.";
        else if (!api.canSurvey(actor)) message = "Only railway builders can survey trestles.";
        else if (operation != Operation.START && !api.hasSurvey(actor)) message = "Plan a railway trestle before using this action.";
        else message = "Stand within eight metres of the survey target on the surface at ground level.";
        actor.getCommunicator().sendNormalServerMessage(message);
    }

    @Override public boolean action(Action action, Creature actor, Item tool, int x, int y, boolean surface,
                                    int heightOffset, int tile, int corner, short number, float counter) {
        return corner != 0 || action(action, actor, tool, x, y, surface, heightOffset, tile, number, counter);
    }

    private boolean acceptsTile(Creature actor, Item tool, int x, int y) {
        return operation != Operation.MARK && ownsTool(actor, tool, ItemList.dioptra)
                && api.canSurvey(actor) && reachable(actor, x, y)
                && (operation != Operation.OPEN || api.hasSurvey(actor));
    }

    private boolean acceptsItem(Creature actor, Item tool, Item target) {
        if (target == null || target.getParentId() != -10L || target.getOwnerId() != -10L
                || !target.isOnSurface() || target.getFloorLevel() != 0 || target.getBridgeId() != -10L)
            return false;
        int x = target.getTileX(), y = target.getTileY();
        if (operation == Operation.MARK)
            return target.getTemplateId() == ItemList.rangePole && api.hasSurvey(actor) && api.canSurvey(actor)
                    && ownsTool(actor, tool, ItemList.dioptra) && reachable(actor, x, y);
        return target.getTemplateId() == WurmRailWorld.trackTemplateId && acceptsTile(actor, tool, x, y);
    }

    static boolean ownsTool(Creature actor, Item item, int template) {
        return actor != null && item != null && item.getTemplateId() == template
                && item.getOwnerId() == actor.getWurmId() && actor.getInventory() != null
                && item.getTopParent() == actor.getInventory().getWurmId()
                && !item.isTraded() && !item.isMailed() && !item.isBanked();
    }

    static boolean reachable(Creature actor, int x, int y) {
        return actor != null && actor.isPlayer() && actor.isOnSurface()
                && actor.getBridgeId() == -10L && actor.getFloorLevel() == 0
                && Math.hypot(actor.getPosX() - (4.0 * x + 2), actor.getPosY() - (4.0 * y + 2)) <= 8;
    }
}
