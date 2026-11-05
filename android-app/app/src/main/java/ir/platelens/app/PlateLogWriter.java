package ir.platelens.app;

import android.content.Context;
import android.graphics.Bitmap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded background image encoding/storage so camera analysis never waits for disk. */
final class PlateLogWriter implements AutoCloseable {
    interface Listener {void saved(float confidence);void failed(boolean busy,Exception error);}
    private final PlateLogStore store;
    private final Listener listener;
    private final Set<String> pending=new HashSet<>();
    private final Map<String,Long> recent=new HashMap<>();
    private final ThreadPoolExecutor io;
    PlateLogWriter(Context context,Listener listener){this(context,listener,"plate-log.db");}
    PlateLogWriter(Context context,Listener listener,String name){
        this.store=new PlateLogStore(context,name);this.listener=listener;
        io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8)){
            @Override protected void terminated(){store.close();}
        };
    }
    void offer(Bitmap frame,Recognition.Plate plate,int threshold){
        if(!PlateLogStore.eligible(plate,threshold))return;
        long now=System.currentTimeMillis();
        synchronized(pending){
            Long last=recent.get(plate.text);
            if(pending.contains(plate.text)||(last!=null&&now>=last&&now-last<PlateLogStore.COOLDOWN_MS))return;
            pending.add(plate.text);
        }
        Bitmap crop;
        try{crop=crop(frame,plate.box,640);}
        catch(RuntimeException error){synchronized(pending){pending.remove(plate.text);}listener.failed(false,error);return;}
        if(crop==null){synchronized(pending){pending.remove(plate.text);}return;}
        try{io.execute(()->{
            try {
                long id=store.save(crop,plate,threshold,now);
                if(id>=0){
                    synchronized(pending){recent.entrySet().removeIf(entry->now>=entry.getValue()&&now-entry.getValue()>=PlateLogStore.COOLDOWN_MS);recent.put(plate.text,now);}
                    listener.saved(plate.saveConfidence);
                }
            }catch(Exception error){listener.failed(false,error);}
            finally{crop.recycle();synchronized(pending){pending.remove(plate.text);}}
        });}catch(RejectedExecutionException error){crop.recycle();synchronized(pending){pending.remove(plate.text);}listener.failed(true,error);}
    }
    static Bitmap crop(Bitmap frame,float[] box,int limit){
        for(float value:box)if(!Float.isFinite(value))return null;
        int left=Math.max(0,(int)Math.floor(box[0])-5),top=Math.max(0,(int)Math.floor(box[1])-5);
        int right=Math.min(frame.getWidth(),(int)Math.ceil(box[2])+5),bottom=Math.min(frame.getHeight(),(int)Math.ceil(box[3])+5);
        if(right<=left||bottom<=top)return null;
        Bitmap region=Bitmap.createBitmap(frame,left,top,right-left,bottom-top);
        float scale=Math.min(1f,(float)limit/Math.max(region.getWidth(),region.getHeight()));
        Bitmap result=Bitmap.createScaledBitmap(region,Math.max(1,Math.round(region.getWidth()*scale)),Math.max(1,Math.round(region.getHeight()*scale)),true);
        if(result==frame)result=frame.copy(Bitmap.Config.ARGB_8888,false);
        if(region!=frame&&region!=result)region.recycle();return result;
    }
    @Override public void close(){io.shutdown();}
}
