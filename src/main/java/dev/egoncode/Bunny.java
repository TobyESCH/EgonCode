package dev.egoncode;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Plays the three GIF animations using terminal half-block pixels. */
final class Bunny {
    private static final int WIDTH=27, PIXEL_HEIGHT=28;
    private static final int BACKGROUND=0x10141c;
    private static final BufferedImage[][] FRAMES={load("bunny-idle.gif"),load("bunny-run.gif"),load("bunny-sleep.gif")};

    private Bunny() {}

    static List<String> frame(String state,int tick) {
        int pose=state.equals("running")?1:state.equals("sleeping")?2:0;
        BufferedImage[] frames=FRAMES[pose];
        BufferedImage image=frames[Math.floorMod(tick,frames.length)];
        List<String> rows=new ArrayList<>(PIXEL_HEIGHT/2);
        for (int y=0;y<PIXEL_HEIGHT;y+=2) {
            StringBuilder line=new StringBuilder("   ");
            int previousTop=-1,previousBottom=-1;
            for (int x=0;x<WIDTH;x++) {
                int top=sample(image,x,y);
                int bottom=sample(image,x,y+1);
                if (top!=previousTop || bottom!=previousBottom) {
                    line.append("\033[38;2;").append(rgb(top)).append(";48;2;").append(rgb(bottom)).append('m');
                    previousTop=top; previousBottom=bottom;
                }
                line.append('▀');
            }
            line.append("\033[0m");
            rows.add(line.toString());
        }
        return rows;
    }

    private static int sample(BufferedImage image,int x,int y) {
        if (y>=27) return BACKGROUND;
        int sx=(int)((x+0.5)*image.getWidth()/WIDTH);
        int sy=(int)((y+0.5)*image.getHeight()/27);
        int pixel=image.getRGB(Math.min(sx,image.getWidth()-1),Math.min(sy,image.getHeight()-1));
        if ((pixel>>>24)<96) return BACKGROUND;
        return switch (pixel&0xffffff) {
            case 0xe6e6e6 -> 0x75452d;  // Main fur: rich brown.
            case 0xcecece -> 0x593421;  // Fur shadow.
            case 0xa9a9a9 -> 0x3b2924;  // Outline.
            case 0xf5bbbb -> 0xc27f84;  // Inner ears.
            case 0x000000 -> 0x211916;  // Eye.
            default -> 0x75452d;
        };
    }
    private static String rgb(int colour) {
        return ((colour>>16)&255)+";"+((colour>>8)&255)+";"+(colour&255);
    }
    private static BufferedImage[] load(String name) {
        try (InputStream input=Bunny.class.getResourceAsStream("/"+name)) {
            if (input==null) throw new IllegalStateException("Missing rabbit animation: "+name);
            try (ImageInputStream stream=ImageIO.createImageInputStream(input)) {
                ImageReader reader=ImageIO.getImageReadersByFormatName("gif").next();
                try {
                    reader.setInput(stream);
                    int count=reader.getNumImages(true);
                    BufferedImage[] frames=new BufferedImage[count];
                    BufferedImage canvas=null;
                    int fullFramePixels=0;
                    for (int i=0;i<count;i++) {
                        BufferedImage update=reader.read(i);
                        int pixels=opaquePixels(update);
                        if (i==0) fullFramePixels=pixels;
                        if (canvas==null || pixels>fullFramePixels/3)
                            canvas=new BufferedImage(update.getWidth(),update.getHeight(),BufferedImage.TYPE_INT_ARGB);
                        for (int y=0;y<update.getHeight();y++) for (int x=0;x<update.getWidth();x++) {
                            int pixel=update.getRGB(x,y);
                            if ((pixel>>>24)>0) canvas.setRGB(x,y,pixel);
                        }
                        BufferedImage snapshot=new BufferedImage(canvas.getWidth(),canvas.getHeight(),BufferedImage.TYPE_INT_ARGB);
                        snapshot.setRGB(0,0,canvas.getWidth(),canvas.getHeight(),
                                canvas.getRGB(0,0,canvas.getWidth(),canvas.getHeight(),null,0,canvas.getWidth()),0,canvas.getWidth());
                        frames[i]=snapshot;
                    }
                    return frames;
                } finally { reader.dispose(); }
            }
        } catch (Exception e) { throw new IllegalStateException("Could not load rabbit animation: "+name,e); }
    }
    private static int opaquePixels(BufferedImage image) {
        int count=0;
        for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++)
            if ((image.getRGB(x,y)>>>24)>96) count++;
        return count;
    }
}
