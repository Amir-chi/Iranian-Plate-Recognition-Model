package ir.platelens.app;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import org.json.JSONObject;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativePipelineTest {
    @Rule public GrantPermissionRule camera=GrantPermissionRule.grant(Manifest.permission.CAMERA);

    @Test public void nativeAndroidReadsTheFiveFullFrameFixtures() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test=InstrumentationRegistry.getInstrumentation().getContext();
        String[] expected={"۹۹ل۵۴۵۳۳","۸۱ص۹۹۹۲۲","۸۱ص۹۹۹۲۲","۸۱ص۹۹۹۲۲","۳۱ن۸۸۲۱۰"};
        for(boolean gpu:new boolean[]{false,true})try(NativeEngine engine=new NativeEngine(app.getAssets(),gpu)){
            for(int i=0;i<expected.length;i++){
                try(InputStream stream=test.getAssets().open(String.format(java.util.Locale.ROOT,"fixtures/%06d.jpg",i+1))){
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
                    Bitmap frame=BitmapFactory.decodeStream(stream,null,options);assertNotNull(frame);
                    long started=SystemClock.elapsedRealtime();String json=engine.detect(frame);
                    Log.i("PlateLensBenchmark","fixture="+i+" requestedGPU="+gpu+" totalMs="+(SystemClock.elapsedRealtime()-started)+" result="+json);
                    List<Recognition.Plate> result=Recognition.parse(json);
                    assertEquals("Plate count in fixture "+i,1,result.size());
                    assertEquals("OCR in fixture "+i,expected[i],result.get(0).text);
                    frame.recycle();
                }
            }
        }
    }

    @Test public void twoPlatesPublishBeforeTheWholeFrameCompletes() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test=InstrumentationRegistry.getInstrumentation().getContext();
        Bitmap montage=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(montage);canvas.drawColor(android.graphics.Color.DKGRAY);
        try(NativeEngine cpu=new NativeEngine(app.getAssets(),false)){
            int row=0;
            for(String source:new String[]{"fixtures/000001.jpg","fixtures/000005.jpg"}){
                try(InputStream stream=test.getAssets().open(source)){
                    Bitmap frame=BitmapFactory.decodeStream(stream);Recognition.Plate plate=Recognition.parse(cpu.detect(frame)).get(0);
                    float[] b=plate.box;Rect bounds=new Rect(Math.max(0,(int)b[0]-5),Math.max(0,(int)b[1]-5),Math.min(frame.getWidth(),(int)b[2]+5),Math.min(frame.getHeight(),(int)b[3]+5));
                    int h=Math.round(500f*bounds.height()/bounds.width());int y=80+row*300;
                    canvas.drawBitmap(frame,bounds,new Rect(70,y,570,y+h),null);frame.recycle();row++;
                }
            }
            List<Recognition.Plate> baseline=Recognition.parse(cpu.detect(montage));assertEquals("Synthetic two-plate scene",2,baseline.size());
            java.util.Set<String> expected=new java.util.HashSet<>(java.util.Arrays.asList("۹۹ل۵۴۵۳۳","۳۱ن۸۸۲۱۰"));
            java.util.Set<String> observed=new java.util.HashSet<>();
            for(Recognition.Plate plate:baseline)observed.add(plate.text);
            assertEquals("Both plates must be read, not merely located",expected,observed);
            try(NativeEngine automatic=new NativeEngine(app.getAssets(),true)){
                List<String> progress=new ArrayList<>();String result=automatic.detect(montage,progress::add);
                List<Recognition.Plate> actual=Recognition.parse(result);assertEquals(2,actual.size());
                assertEquals(1,progress.size());assertEquals(1,new JSONObject(progress.get(0)).getInt("completed"));
                List<Recognition.Plate> partial=Recognition.parse(progress.get(0));assertEquals(actual.get(0).text,partial.get(0).text);assertEquals("",partial.get(1).text);
                for(int i=0;i<2;i++)assertEquals(baseline.get(i).text,actual.get(i).text);
                Log.i("PlateLensBenchmark","twoPlates="+result);
            }
        }finally{montage.recycle();}
    }

    @Test public void liveCameraActivityStartsAndCanStop() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicBoolean ready=new AtomicBoolean(false);
            for(int i=0;i<80&&!ready.get();i++){
                scenario.onActivity(activity->ready.set(activity.findViewById(R.id.toggle).isEnabled()));
                Thread.sleep(250);
            }
            assertTrue("Bundled models must load",ready.get());
            AtomicBoolean started=new AtomicBoolean(false);
            for(int i=0;i<120&&!started.get();i++){
                scenario.onActivity(activity->started.set(activity.hasAnalyzedFrame()&&activity.getString(R.string.pause).equals(((android.widget.Button)activity.findViewById(R.id.toggle)).getText().toString())));
                Thread.sleep(250);
            }
            AtomicBoolean supportsZoom=new AtomicBoolean(false);
            scenario.onActivity(activity->supportsZoom.set(activity.maximumCameraZoom()>1.01f));
            if(supportsZoom.get()){
                scenario.onActivity(activity->activity.findViewById(R.id.zoom_in).performClick());
                waitForZoom(scenario,true);
                scenario.onActivity(activity->activity.findViewById(R.id.zoom_reset).performClick());
                waitForZoom(scenario,false);
                scenario.onActivity(activity->pinchOut(activity.findViewById(R.id.overlay)));
                waitForZoom(scenario,true);
                scenario.onActivity(activity->activity.findViewById(R.id.zoom_reset).performClick());
                waitForZoom(scenario,false);
                Log.i("PlateLensBenchmark","cameraZoom=button,pinch,reset applied to CameraX ZoomState");
            }else{
                scenario.onActivity(activity->assertFalse(activity.findViewById(R.id.zoom_in).isEnabled()));
                Log.i("PlateLensBenchmark","cameraZoom=unsupported by emulator; controls disabled");
            }
            Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(screenshot!=null){
                Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
                try(FileOutputStream stream=new FileOutputStream(new File(app.getFilesDir(),"preview.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,stream);}
                screenshot.recycle();
            }
            scenario.onActivity(activity->assertTrue("Camera must deliver an analyzed frame; status: "+((android.widget.TextView)activity.findViewById(R.id.status)).getText(),started.get()));
            scenario.onActivity(activity->{
                activity.findViewById(R.id.toggle).performClick();
                assertEquals(activity.getString(R.string.start),((android.widget.Button)activity.findViewById(R.id.toggle)).getText().toString());
                assertFalse(activity.findViewById(R.id.zoom_in).isEnabled());
                assertFalse(activity.findViewById(R.id.zoom_out).isEnabled());
            });
            scenario.onActivity(activity->activity.findViewById(R.id.backend).performClick());
            AtomicBoolean switched=new AtomicBoolean(false);
            for(int i=0;i<120&&!switched.get();i++){
                scenario.onActivity(activity->switched.set(activity.hasAnalyzedFrame()&&((android.widget.Button)activity.findViewById(R.id.backend)).getText().toString().startsWith("CPU")));
                Thread.sleep(250);
            }
            assertTrue("Mode switch must restart with a fresh CPU camera result",switched.get());
            scenario.onActivity(activity->activity.findViewById(R.id.toggle).performClick());
        }
    }
    private static void waitForZoom(ActivityScenario<MainActivity> scenario,boolean increased) throws Exception {
        AtomicBoolean applied=new AtomicBoolean(false);
        for(int i=0;i<120&&!applied.get();i++){
            scenario.onActivity(activity->applied.set(activity.hasAnalyzedFrame()&&(increased?activity.cameraZoomRatio()>1.01f:Math.abs(activity.cameraZoomRatio()-1f)<.01f)));
            Thread.sleep(100);
        }
        assertTrue("CameraX must apply requested zoom",applied.get());
    }
    private static void pinchOut(View view){
        long start=SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++){
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i]=new MotionEvent.PointerCoords();coordinates[i].pressure=1;coordinates[i].size=1;coordinates[i].y=view.getHeight()/2f;
        }
        // Span and duration exceed the device's gesture thresholds; the preview is not a fixed DPI.
        int[] actions={MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),MotionEvent.ACTION_UP};
        for(int j=0;j<actions.length;j++){
            float span=view.getWidth()*(.2f+Math.min(Math.max(j-1,0),8)*.075f);
            coordinates[0].x=view.getWidth()/2f-span/2;coordinates[1].x=view.getWidth()/2f+span/2;
            int count=j==0||j==actions.length-1?1:2;
            MotionEvent event=MotionEvent.obtain(start,start+j*80,actions[j],count,properties,coordinates,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
            view.dispatchTouchEvent(event);event.recycle();
        }
    }

}
