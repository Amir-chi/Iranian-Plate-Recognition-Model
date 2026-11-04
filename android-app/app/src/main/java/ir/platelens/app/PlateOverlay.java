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
        int number=0;
        for(Recognition.Plate plate:plates){
            number++;paint.setStyle(Paint.Style.STROKE);
            paint.setColor(getContext().getColor(PlateFormat.isStandard(plate.text)?R.color.accent:R.color.warning));
            float[] b=PreviewGeometry.fitCenter(plate.box,frameWidth,frameHeight,getWidth(),getHeight());
            canvas.drawRoundRect(b[0],b[1],b[2],b[3],8,8,paint);
            // Token cells keep Persian letters/digits in physical plate order, including multi-plate scenes.
            float density=getResources().getDisplayMetrics().density;
            float cell=Math.min(22*density,Math.max(12*density,(b[2]-b[0])/Math.max(8,plate.tokens.size())));
            float labelWidth=Math.min(getWidth(),cell*Math.max(plate.tokens.size(),2));
            float x=Math.max(0,Math.min(b[0],getWidth()-labelWidth)),height=28*density;
            float top=Math.max(0,b[1]-height);paint.setStyle(Paint.Style.FILL);paint.setColor(getContext().getColor(R.color.background));
            canvas.drawRoundRect(x,top,x+labelWidth,top+height,4*density,4*density,paint);
            paint.setColor(getContext().getColor(R.color.foreground));paint.setTextSize(16*getResources().getDisplayMetrics().scaledDensity);
            paint.setTextAlign(Paint.Align.CENTER);float baseline=top+height/2-(paint.ascent()+paint.descent())/2;
            if(plate.tokens.isEmpty())canvas.drawText("#"+number,x+labelWidth/2,baseline,paint);
            else for(int i=0;i<plate.tokens.size();i++)canvas.drawText(plate.tokens.get(i),x+cell*(i+.5f),baseline,paint);
        }
    }
}
