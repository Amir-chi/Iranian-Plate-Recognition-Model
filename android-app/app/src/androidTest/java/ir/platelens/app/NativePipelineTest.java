package ir.platelens.app;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
        try(NativeEngine engine=new NativeEngine(app.getAssets())){
            for(int i=0;i<expected.length;i++){
                try(InputStream stream=test.getAssets().open(String.format(java.util.Locale.ROOT,"fixtures/%06d.jpg",i+1))){
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
                    Bitmap frame=BitmapFactory.decodeStream(stream,null,options);assertNotNull(frame);
                    List<Recognition.Plate> result=Recognition.parse(engine.detect(frame));
                    assertEquals("Plate count in fixture "+i,1,result.size());
                    assertEquals("OCR in fixture "+i,expected[i],result.get(0).text);
                    frame.recycle();
                }
            }
        }
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
            for(int i=0;i<80&&!started.get();i++){
                scenario.onActivity(activity->started.set(activity.getString(R.string.pause).equals(((android.widget.Button)activity.findViewById(R.id.toggle)).getText().toString())));
                Thread.sleep(250);
            }
            Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(screenshot!=null){
                Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
                try(FileOutputStream stream=new FileOutputStream(new File(app.getFilesDir(),"preview.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,stream);}
                screenshot.recycle();
            }
            scenario.onActivity(activity->assertTrue("Camera must start; status: "+((android.widget.TextView)activity.findViewById(R.id.status)).getText(),started.get()));
            scenario.onActivity(activity->{
                activity.findViewById(R.id.toggle).performClick();
                assertEquals(activity.getString(R.string.start),((android.widget.Button)activity.findViewById(R.id.toggle)).getText().toString());
            });
        }
    }
}
