package ir.platelens.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import java.util.Collections;
import java.util.List;

public final class PlateOverlay extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Recognition.Plate> plates=Collections.emptyList();
    private int frameWidth=1,frameHeight=1;
    public PlateOverlay(Context context,AttributeSet attributes){super(context,attributes);}
    public void setPlates(List<Recognition.Plate> values,int width,int height){plates=values;frameWidth=width;frameHeight=height;invalidate();}
    public void clear(){plates=Collections.emptyList();invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*getResources().getDisplayMetrics().density);
        for(Recognition.Plate plate:plates){
            paint.setColor(getContext().getColor(PlateFormat.isStandard(plate.text)?R.color.accent:R.color.warning));
            float[] b=PreviewGeometry.fitCenter(plate.box,frameWidth,frameHeight,getWidth(),getHeight());
            canvas.drawRoundRect(b[0],b[1],b[2],b[3],8,8,paint);
        }
    }
}
