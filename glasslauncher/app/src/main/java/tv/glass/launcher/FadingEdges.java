package tv.glass.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;

/** Only edge bands need an off-screen destination for DST_OUT masking. */
final class FadingEdges {
    interface Content { void draw(Canvas canvas); }
    private FadingEdges() {}

    static void draw(Canvas canvas,int width,int top,int height,int topFade,int bottomFade,
                     boolean fadeTop,Shader topShader,Shader bottomShader,Paint mask,Content content){
        int topEnd=fadeTop?Math.min(height,top+topFade):top;
        int bottomStart=Math.max(topEnd,height-bottomFade);
        int save=canvas.save();
        canvas.clipRect(0,topEnd,width,bottomStart);
        content.draw(canvas);
        canvas.restoreToCount(save);
        if(fadeTop && topEnd>top)band(canvas,width,top,topEnd,topShader,mask,content);
        if(height>bottomStart)band(canvas,width,bottomStart,height,bottomShader,mask,content);
    }
    private static void band(Canvas canvas,int width,int top,int bottom,Shader shader,
                             Paint mask,Content content){
        int save=canvas.saveLayer(0,top,width,bottom,null);
        canvas.clipRect(0,top,width,bottom);
        content.draw(canvas);
        mask.setShader(shader);
        canvas.drawRect(0,top,width,bottom,mask);
        canvas.restoreToCount(save);
    }
}
