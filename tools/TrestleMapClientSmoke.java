import com.wurmonline.client.bml.XMLParser;
import com.wurmonline.client.renderer.backend.Primitive;
import com.wurmonline.client.renderer.backend.Queue;
import com.wurmonline.client.renderer.gui.FlexComponent;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.client.renderer.gui.text.TextFont;
import org.w3c.dom.Element;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Base64;

/** Parse and render a survey map through the native BML component in a headless HUD queue. */
public final class TrestleMapClientSmoke {
    public static void run() throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(4 + 7*4);
        for (int value : new int[]{4,3,32,126,170,71,310,35,475,105,32,126,170,71,475,105})
            bytes.putShort((short)value);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
        Element label = (Element)new XMLParser().parseBMLString("label{text=\"Fallback\";trestlemap=\"" + payload + "\"}")
                .getElementsByTagName("label").item(0);
        Class<?> windowType = Class.forName("com.wurmonline.client.renderer.gui.BmlWindowComponent");
        Class<?> componentType = Class.forName("com.wurmonline.client.renderer.gui.WurmComponent");
        Field screenWidth = componentType.getDeclaredField("SCREEN_WIDTH");
        Field screenHeight = componentType.getDeclaredField("SCREEN_HEIGHT");
        screenWidth.setAccessible(true);
        screenHeight.setAccessible(true);
        screenWidth.setInt(null,800);
        screenHeight.setInt(null,600);
        Field unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        Object allocator = unsafe.get(null);
        Object window = allocator.getClass().getMethod("allocateInstance",Class.class).invoke(allocator,windowType);
        Method parse = windowType.getDeclaredMethod("parse",Element.class);
        parse.setAccessible(true);
        FlexComponent map = (FlexComponent)parse.invoke(window,label);
        Field data = map.getClass().getDeclaredField("wurmRailMap");
        data.setAccessible(true);
        if (data.get(map)==null) throw new AssertionError("Native BML did not create the route map");
        map.setLocation(10,20,512,192);
        Field text = componentType.getDeclaredField("text");
        text.setAccessible(true);
        StringBuilder painted = new StringBuilder();
        text.set(map,new TextFont() {
            public void moveTo(int x,int y) { }
            public int paint(Queue q,String value,float r,float g,float b,float a) {
                painted.append(value).append(' ');
                return getWidth(value);
            }
            public int getWidth(String value) { return value.length()*7; }
            public int getWidth(char[] value,int offset,int length) { return length*7; }
            public int getHeight() { return 12; }
            public int getDescent() { return 3; }
            public int getAscent() { return 9; }
            public int getLeading() { return 0; }
        });
        Primitive.initializeResources();
        HeadsUpDisplay.scissor.reset(800,600);
        Queue queue = new Queue(Queue.QUEUE_HUD,false);
        Method render = map.getClass().getDeclaredMethod("renderComponent",Queue.class,float.class);
        render.setAccessible(true);
        render.invoke(map,queue,0f);
        if (queue.getQueueCount()<20 || !painted.toString().contains("Start End 1 "))
            throw new AssertionError("Route, compass, or map marks were not rendered: "+queue.getQueueCount()+" "+painted);
        ByteBuffer issue = ByteBuffer.allocate(2 + 34*4);
        issue.putShort((short)1).putShort((short)310).putShort((short)35);
        for (int i = 0; i <= 32; i++)
            issue.putShort((short)(170+i*9)).putShort((short)(71-36*Math.sin(Math.PI*i/32)));
        String diagnostic = Base64.getUrlEncoder().withoutPadding().encodeToString(issue.array());
        Element problem = (Element)new XMLParser().parseBMLString(
                "label{text=\"Fallback\";trestlemap=\"" + payload + "\";trestlemapissue=\"" + diagnostic + "\"}")
                .getElementsByTagName("label").item(0);
        FlexComponent rejected = (FlexComponent)parse.invoke(window,problem);
        rejected.setLocation(10,20,512,192);
        text.set(rejected,text.get(map));
        int before = queue.getQueueCount();
        render.invoke(rejected,queue,0f);
        if (!painted.toString().contains("Red: rejected curve") || queue.getQueueCount()-before < 50)
            throw new AssertionError("Rejected curve, hotspot, and legend were not rendered");
        System.out.println("PASS native survey map BML, approved route, rejected curve, hotspot and labelled poles");
    }
}
