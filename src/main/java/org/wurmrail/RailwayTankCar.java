package org.wurmrail;

import com.wurmonline.server.items.AdvancedCreationEntry;
import com.wurmonline.server.items.CreationCategories;
import com.wurmonline.server.items.CreationEntryCreator;
import com.wurmonline.server.items.CreationRequirement;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.items.ItemTypes;
import com.wurmonline.server.skills.SkillList;
import com.wurmonline.shared.constants.ItemMaterials;
import java.io.IOException;
import org.gotti.wurmunlimited.modsupport.ItemTemplateBuilder;

/** Native liquid wagon. World registration supplies the shared rail vehicle behavior. */
public final class RailwayTankCar {
    public static int templateId;
    private static boolean recipesCreated;
    private RailwayTankCar() { }

    public static void createTemplate() throws IOException {
        if(templateId!=0)return;
        try {
            int image=ItemTemplateFactory.getInstance().getTemplate(ItemList.wagon).getImageNumber();
            templateId=new ItemTemplateBuilder("wurmrail.tankcar.v1")
                    .name("rail tank car","rail tank cars","A rail tank car for one supported liquid at a time. Couple it to a locomotive and add a Water Fill Station to its route. Solid cargo is not accepted.")
                    .size(4).itemTypes(new short[]{ItemTypes.ITEM_TYPE_NAMED,ItemTypes.ITEM_TYPE_HOLLOW,
                            ItemTypes.ITEM_TYPE_NOTAKE,ItemTypes.ITEM_TYPE_METAL,ItemTypes.ITEM_TYPE_DECORATION,
                            ItemTypes.ITEM_TYPE_REPAIRABLE,ItemTypes.ITEM_TYPE_VEHICLE,ItemTypes.ITEM_TYPE_LOCKABLE,
                            ItemTypes.ITEM_TYPE_CART,ItemTypes.ITEM_TYPE_NOMOVE,ItemTypes.ITEM_TYPE_CONTAINER_LIQUID,
                            ItemTypes.ITEM_TYPE_USES_SPECIFIED_CONTAINER_VOLUME,ItemTypes.ITEM_TYPE_NOWORKPARENT})
                    .imageNumber((short)image).behaviourType((short)41).dimensions(240,190,410)
                    .containerSize(200,100,250).maxItemCount(LiquidTankPolicy.MAX_ITEMS)
                    .maxItemWeight((int)LiquidTankPolicy.MAX_WEIGHT_GRAMS).weightGrams(500000)
                    .material(ItemMaterials.MATERIAL_IRON).modelName("model.wurmrail.tankcar.")
                    .difficulty(35).decayTime(Long.MAX_VALUE).build().getTemplateId();
            TankCarRegistry.register(templateId);
        } catch(com.wurmonline.server.items.NoSuchTemplateException missing) {
            throw new IOException("The Tank Car requires the native wagon template",missing);
        }
    }

    public static void createRecipes() {
        if(recipesCreated)return;
        if(templateId==0)throw new IllegalStateException("Create the Tank Car template first.");
        AdvancedCreationEntry recipe=CreationEntryCreator.createAdvancedEntry(SkillList.SMITHING_BLACKSMITHING,
                ItemList.plank,ItemList.ironBand,templateId,true,true,0,false,false,CreationCategories.CARTS);
        recipe.setFinalMaterial(ItemMaterials.MATERIAL_IRON);
        recipe.addRequirement(new CreationRequirement(1,ItemList.wheelAxleSmall,2,true));
        recipe.addRequirement(new CreationRequirement(2,ItemList.sheetIron,16,true));
        recipe.addRequirement(new CreationRequirement(3,ItemList.ironBand,8,true));
        recipe.addRequirement(new CreationRequirement(4,ItemList.plank,12,true));
        recipe.addRequirement(new CreationRequirement(5,ItemList.nailsIronLarge,4,true));
        recipesCreated=true;
    }
}
