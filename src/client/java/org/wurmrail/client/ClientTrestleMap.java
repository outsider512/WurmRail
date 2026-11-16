package org.wurmrail.client;

import com.wurmonline.client.renderer.Matrix;
import com.wurmonline.client.renderer.backend.Primitive;
import com.wurmonline.client.renderer.backend.Queue;
import com.wurmonline.client.renderer.backend.ScissorControl;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.client.renderer.gui.Renderer;
import com.wurmonline.client.renderer.gui.text.TextFont;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** A north-up plan drawn in Wurm's native HUD queue. */
public final class ClientTrestleMap {
    private final short[] route, marks;
    private final Matrix matrix = new Matrix();

    private ClientTrestleMap(short[] route, short[] marks) {
        this.route = route;
        this.marks = marks;
    }

    public static ClientTrestleMap decode(String encoded) {
        if (encoded.length() < 16 || encoded.length() > 8000) return null;
        try {
            ByteBuffer data = ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded));
            int points = data.getShort() & 65535, poles = data.getShort() & 65535;
            if (poles < 2 || poles > 502 || points > 823 || data.remaining() != 4*(points+poles)) return null;
            return new ClientTrestleMap(read(data, points), read(data, poles));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static short[] read(ByteBuffer data, int points) {
        short[] coordinates = new short[2*points];
        for (int i = 0; i < coordinates.length; i++) coordinates[i] = data.getShort();
        return coordinates;
    }

    public void render(Queue queue, TextFont font, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        ScissorControl scissor = HeadsUpDisplay.scissor;
        boolean visible = scissor.pushClip(x,y,width,height);
        try {
            if (!visible) return;
            float scale = Math.min(width/512f,height/192f);
            float centerX = x + width/2f, centerY = y + height/2f;
            rectangle(queue,x,y,width,height,.09f,.11f,.11f);
            for (int col = 1; col < 8; col++) rectangle(queue,x+width*col/8f,y,1,height,.16f,.19f,.19f);
            for (int row = 1; row < 4; row++) rectangle(queue,x,y+height*row/4f,width,1,.16f,.19f,.19f);
            for (int i = 2; i < route.length; i += 2) {
                float ax = centerX+(route[i-2]-256)*scale, ay = centerY+(route[i-1]-96)*scale;
                float bx = centerX+(route[i]-256)*scale, by = centerY+(route[i+1]-96)*scale;
                if ((ax < x && bx < x) || (ax > x+width && bx > x+width)
                        || (ay < y && by < y) || (ay > y+height && by > y+height)) continue;
                float dx = bx-ax, dy = by-ay, length = (float)Math.hypot(dx,dy);
                if (length < .01f) continue;
                float acrossX = -3*dy/length, acrossY = 3*dx/length;
                matrix.fromTranslationAndNonUniformScale(ax-acrossX/2,ay-acrossY/2,0,dx,acrossY,0);
                matrix.m01(dy);
                matrix.m10(acrossX);
                square(queue,.96f,.69f,.31f);
            }
            List<Rectangle> labels = new ArrayList<>();
            paint(font,queue,"N ↑",x+8,y+font.getHeight()+4);
            labels.add(new Rectangle(x+4,y+2,font.getWidth("N ↑")+8,font.getHeight()+6));
            drawMark(queue,font,x,y,width,height,centerX,centerY,scale,0,"Start",labels);
            drawMark(queue,font,x,y,width,height,centerX,centerY,scale,marks.length-2,"End",labels);
            for (int i = 2; i < marks.length-2; i += 2)
                drawMark(queue,font,x,y,width,height,centerX,centerY,scale,i,Integer.toString(i/2),labels);
        } finally {
            scissor.popClip();
        }
    }

    private void drawMark(Queue queue, TextFont font, int x, int y, int width, int height,
                          float centerX, float centerY, float scale, int index, String name, List<Rectangle> labels) {
        int px = Math.round(centerX+(marks[index]-256)*scale), py = Math.round(centerY+(marks[index+1]-96)*scale);
        if (px < x || px >= x+width || py < y || py >= y+height) return;
        rectangle(queue,px-4,py-4,8,8,.08f,.11f,.11f);
        rectangle(queue,px-3,py-3,6,6,.48f,.84f,.81f);
        int w = font.getWidth(name), h = font.getHeight();
        for (int left : new int[]{px+6,px-w-6}) {
            for (int top : new int[]{py-h-2,py+4}) {
                Rectangle box = new Rectangle(left-2,top,w+4,h+2);
                if (left < x || left+w >= x+width || top < y || top+h >= y+height
                        || labels.stream().anyMatch(box::intersects)) continue;
                rectangle(queue,box.x,box.y,box.width,box.height,.09f,.11f,.11f);
                paint(font,queue,name,left,top+h);
                labels.add(box);
                return;
            }
        }
    }

    private static void paint(TextFont font, Queue queue, String text, int x, int y) {
        font.moveTo(x,y);
        font.paint(queue,text,.87f,.93f,.92f,1);
    }

    private void rectangle(Queue queue, float x, float y, float w, float h, float r, float g, float b) {
        matrix.fromTranslationAndNonUniformScale(x,y,0,w,h,0);
        square(queue,r,g,b);
    }

    private void square(Queue queue, float r, float g, float b) {
        Primitive primitive = queue.reservePrimitive();
        primitive.copyStateFrom(Renderer.stateAlphaBlend);
        primitive.setColor(r,g,b,1);
        primitive.program = null;
        primitive.vertex = Primitive.staticVertexSquare2D;
        primitive.index = null;
        primitive.type = Primitive.Type.TRIANGLESTRIP;
        primitive.num = 2;
        primitive.offset = 0;
        primitive.lightManager = null;
        primitive.texture[0] = null;
        primitive.texture[1] = null;
        primitive.texenv[0] = Primitive.TexEnv.MODULATE;
        primitive.clipRect = HeadsUpDisplay.scissor.getCurrent();
        queue.queue(primitive,matrix);
    }
}
