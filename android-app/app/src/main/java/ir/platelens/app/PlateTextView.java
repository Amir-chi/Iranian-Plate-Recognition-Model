package ir.platelens.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import java.util.Collections;
import java.util.List;

public final class PlateTextView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<String> tokens=Collections.emptyList();
    public PlateTextView(Context context,AttributeSet attributes){super(context,attributes);paint.setTypeface(Typeface.create("sans",Typeface.BOLD));}
    public void setTokens(List<String> values,String text){tokens=values;setContentDescription(text);invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(tokens.isEmpty())return;
        paint.setColor(getContext().getColor(R.color.foreground));paint.setTextAlign(Paint.Align.CENTER);
        float cell=(float)getWidth()/Math.max(tokens.size(),8);float start=(getWidth()-cell*tokens.size())/2;
        paint.setTextSize(Math.min(32*getResources().getDisplayMetrics().scaledDensity,cell*.8f));
        float baseline=getHeight()/2f-(paint.ascent()+paint.descent())/2;
        // Draw tokens independently in physical plate order; bidi must not reorder digits.
        for(int i=0;i<tokens.size();i++)canvas.drawText(tokens.get(i),start+cell*(i+.5f),baseline,paint);
    }
}
