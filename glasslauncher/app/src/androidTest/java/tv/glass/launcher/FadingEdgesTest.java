package tv.glass.launcher;

import static org.junit.Assert.*;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FadingEdgesTest {
    @Test public void edgeBandsMatchTheOriginalFullLayerAtTop(){compare(false);}
    @Test public void edgeBandsMatchTheOriginalFullLayerWhileScrolled(){compare(true);}
    private void compare(boolean fadeTop){
        int w=120,h=160,top=18,tf=16,bf=30;
        Shader ts=new LinearGradient(0,top,0,top+tf,Color.BLACK,Color.TRANSPARENT,Shader.TileMode.CLAMP);
        Shader bs=new LinearGradient(0,h-bf,0,h,Color.TRANSPARENT,0xE6000000,Shader.TileMode.CLAMP);
        FadingEdges.Content content=c->{
            Paint p=new Paint();p.setColor(0xCC80D0FF);c.drawRect(10,4,100,70,p);
            p.setColor(0xAAEE8050);c.drawRect(30,55,115,152,p);
        };
        Bitmap old=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888),next=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try{
            Canvas c=new Canvas(old);c.drawColor(0xFF152535);
            Paint mask=new Paint();mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            int save=c.saveLayer(0,top,w,h,null);c.clipRect(0,top,w,h);content.draw(c);
            if(fadeTop){mask.setShader(ts);c.drawRect(0,top,w,top+tf,mask);}
            mask.setShader(bs);c.drawRect(0,h-bf,w,h,mask);c.restoreToCount(save);
            Canvas n=new Canvas(next);n.drawColor(0xFF152535);
            FadingEdges.draw(n,w,top,h,tf,bf,fadeTop,ts,bs,mask,content);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int a=old.getPixel(x,y),b=next.getPixel(x,y);
                assertTrue("red at "+x+","+y,Math.abs(Color.red(a)-Color.red(b))<=1);
                assertTrue("green at "+x+","+y,Math.abs(Color.green(a)-Color.green(b))<=1);
                assertTrue("blue at "+x+","+y,Math.abs(Color.blue(a)-Color.blue(b))<=1);
                assertEquals(Color.alpha(a),Color.alpha(b));
            }
        }finally{old.recycle();next.recycle();}
    }
}
