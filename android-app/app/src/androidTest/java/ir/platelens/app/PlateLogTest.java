package ir.platelens.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.ListView;
import android.widget.SeekBar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlateLogTest {
    @Rule public GrantPermissionRule camera=GrantPermissionRule.grant(Manifest.permission.CAMERA);
    private Context app(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private Recognition.Plate plate(float locator,float weakest) throws Exception {
        JSONArray scores=new JSONArray(),tokens=new JSONArray();String text="۲۴م۶۹۱۱۳";
        for(int i=0;i<text.length();i++){tokens.put(text.substring(i,i+1));scores.put(i==0?weakest:.97);}
        return new Recognition.Plate(new JSONObject().put("box",new JSONArray(new int[]{10,10,90,35})).put("confidence",locator).put("text",text).put("tokens",tokens).put("character_confidences",scores));
    }
    @Test public void thresholdUsesEveryCharacterAndLocator() throws Exception {
        assertFalse(PlateLogStore.eligible(plate(.99f,.79f),80));
        assertFalse(PlateLogStore.eligible(plate(.79f,.99f),80));
        assertTrue(PlateLogStore.eligible(plate(.99f,.80f),80));
        Recognition.Plate empty=new Recognition.Plate(new JSONObject("{\"box\":[0,0,1,1],\"confidence\":0.99,\"text\":\"\",\"tokens\":[],\"character_confidences\":[]}"));
        assertFalse(PlateLogStore.eligible(empty,1));
    }
    @Test public void recordsPersistDeduplicateAndExportMatchingJpegs() throws Exception {
        String name="storage-test-"+System.nanoTime()+".db";Bitmap crop=Bitmap.createBitmap(80,25,Bitmap.Config.ARGB_8888);crop.eraseColor(android.graphics.Color.WHITE);
        long when=System.currentTimeMillis();Recognition.Plate reading=plate(.95f,.90f);
        try {
            try(PlateLogStore store=new PlateLogStore(app(),name)){
                assertEquals(-1,store.save(crop,plate(.95f,.79f),80,when));
                assertTrue(store.save(crop,reading,80,when)>0);
                assertEquals(-1,store.save(crop,reading,80,when+29_999));
                assertTrue(store.save(crop,reading,80,when+30_000)>0);assertEquals(2,store.count());
            }
            try(PlateLogStore reopened=new PlateLogStore(app(),name)){
                assertEquals(2,reopened.count());assertEquals(reading.text,reopened.page(0).get(0).text);
                ByteArrayOutputStream output=new ByteArrayOutputStream();assertEquals(2,reopened.exportZip(output));int images=0;JSONObject manifest=null;
                try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(output.toByteArray()),StandardCharsets.UTF_8)){
                    ZipEntry entry;while((entry=zip.getNextEntry())!=null){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=zip.read(buffer))!=-1)bytes.write(buffer,0,n);
                        if(entry.getName().endsWith(".jpg")){Bitmap image=BitmapFactory.decodeByteArray(bytes.toByteArray(),0,bytes.size());assertNotNull(image);assertEquals(80,image.getWidth());image.recycle();images++;}
                        else if(entry.getName().equals("records.json"))manifest=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
                    }
                }
                assertEquals(2,images);assertNotNull(manifest);assertEquals(2,manifest.getJSONArray("records").length());
                JSONObject record=manifest.getJSONArray("records").getJSONObject(0);assertEquals(reading.text,record.getString("text"));assertEquals(80,record.getInt("threshold_percent"));assertEquals(.9,record.getDouble("confidence"),.0001);
            }
            Log.i("PlateLensBenchmark","storage=threshold,cooldown,reopen,JPEG-and-JSON ZIP passed");
        }finally{crop.recycle();app().deleteDatabase(name);}
    }
    @Test public void asyncWriterStoresOwnedCropOnce() throws Exception {
        Bitmap frame=Bitmap.createBitmap(100,50,Bitmap.Config.ARGB_8888);frame.eraseColor(android.graphics.Color.BLUE);
        Recognition.Plate reading=plate(.96f,.91f);CountDownLatch saved=new CountDownLatch(1);AtomicBoolean error=new AtomicBoolean(false);
        String name="writer-test-"+System.nanoTime()+".db";Recognition.Plate unique=reading;
        try(PlateLogWriter writer=new PlateLogWriter(app(),new PlateLogWriter.Listener(){public void saved(float confidence){saved.countDown();}public void failed(boolean busy,Exception failure){error.set(true);saved.countDown();}},name)){
            writer.offer(frame,unique,80);writer.offer(frame,unique,80);frame.recycle();
            assertTrue("Background save must finish",saved.await(5,TimeUnit.SECONDS));assertFalse(error.get());
            try(PlateLogStore store=new PlateLogStore(app(),name)){
                long matches=store.page(0).stream().filter(record->record.text.equals(unique.text)).count();assertEquals(1,matches);
            }
        }
    }
    @Test public void englishSettingsPersistAndHistoryShowsNativeReading() throws Exception {
        app().getSharedPreferences("logging",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).putInt("threshold",80).commit();
        try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
            screen.onActivity(activity->{assertEquals("Plate Lens",activity.getString(R.string.app_name));activity.findViewById(R.id.log_settings).performClick();});
            Thread.sleep(500);capture("plate-lens-test-settings.png");
            screen.onActivity(activity->{
                // Dialog views live in a separate window; find its focused root through the activity's dialog reference.
                AlertDialog dialog=activity.loggingDialog();SeekBar slider=dialog.findViewById(R.id.threshold_slider);assertEquals(80,slider.getProgress());slider.setProgress(85);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            assertEquals(85,app().getSharedPreferences("logging",Context.MODE_PRIVATE).getInt("threshold",0));
            screen.onActivity(activity->activity.findViewById(R.id.log_settings).performClick());
            screen.onActivity(activity->{AlertDialog dialog=activity.loggingDialog();((SeekBar)dialog.findViewById(R.id.threshold_slider)).setProgress(80);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
        }
        try(InputStream source=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("fixtures/000001.jpg");NativeEngine engine=new NativeEngine(app().getAssets(),false);PlateLogStore store=new PlateLogStore(app())){
            Bitmap image=BitmapFactory.decodeStream(source);Recognition.Plate reading=Recognition.parse(engine.detect(image)).get(0);Bitmap crop=PlateLogWriter.crop(image,reading.box,640);
            assertNotNull(crop);assertTrue(reading.saveConfidence>0);store.save(crop,reading,1,System.currentTimeMillis());crop.recycle();image.recycle();
        }
        try(ActivityScenario<HistoryActivity> history=ActivityScenario.launch(HistoryActivity.class)){
            AtomicBoolean loaded=new AtomicBoolean(false);for(int i=0;i<40&&!loaded.get();i++){history.onActivity(activity->loaded.set(((ListView)activity.findViewById(R.id.history_list)).getAdapter().getCount()>0));Thread.sleep(100);}
            assertTrue(loaded.get());Thread.sleep(500);capture("plate-lens-test-history.png");
        }
        Log.i("PlateLensBenchmark","history=English settings persisted, native fixture crop displayed");
    }
    private void capture(String filename) throws Exception {
        try(ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/"+filename);InputStream result=new FileInputStream(descriptor.getFileDescriptor())){byte[] buffer=new byte[1024];while(result.read(buffer)!=-1){} }
    }
}
