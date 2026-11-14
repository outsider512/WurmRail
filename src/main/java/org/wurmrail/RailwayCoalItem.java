package org.wurmrail;

import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.items.ItemTypes;
import com.wurmonline.shared.constants.ItemMaterials;
import java.io.IOException;
import org.gotti.wurmunlimited.modsupport.ItemTemplateBuilder;

/** Mineral coal is a physical raw resource; vanilla charcoal keeps its original identity. */
public final class RailwayCoalItem {
    public static final int MINED_WEIGHT_GRAMS=20000,MINED_VOLUME_CC=20000;
    public static int templateId;
    private RailwayCoalItem(){ }
    public static void createTemplate()throws IOException {
        if(templateId!=0)return;
        try {
            ItemTemplate charcoal=ItemTemplateFactory.getInstance().getTemplate(ItemList.charcoal);
            templateId=new ItemTemplateBuilder("wurmrail.coal.v1")
                    .name("coal","coal","Mineral coal mined from an underground Coal Vein. It is distinct from wood-derived charcoal.")
                    .size(0).itemTypes(new short[]{ItemTypes.ITEM_TYPE_COMBINE,ItemTypes.ITEM_TYPE_BULK})
                    .imageNumber(charcoal.getImageNumber()).behaviourType((short)1)
                    .dimensions(20,25,40).weightGrams(MINED_WEIGHT_GRAMS)
                    .material(ItemMaterials.MATERIAL_COAL).modelName(charcoal.getModelName())
                    .difficulty(10f).decayTime(charcoal.getDecayTime()).build().getTemplateId();
            CoalItemRegistry.register(templateId);
        }catch(com.wurmonline.server.items.NoSuchTemplateException missing){throw new IOException("Native charcoal artwork is required for the distinct Coal item",missing);}
    }
}
