package ir.platelens.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.util.Log;
import android.view.View;
import android.view.ScaleGestureDetector;
import android.view.MotionEvent;
import androidx.camera.core.ZoomState;
import androidx.lifecycle.LiveData;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class MainActivity extends EnglishActivity {
    private PreviewView preview;
    private PlateOverlay overlay;
    private PlateTextView plateText;
    private ImageView cropView;
    private TextView status,quality,timing;
    private Button toggle,torch,copy,backend,zoomIn,zoomOut,zoomReset;
    private float requestedZoom=1f,minZoom=1f,maxZoom=1f;
    private int zoomRequest=0;
    private volatile boolean zoomChanging=false;
    private LiveData<ZoomState> zoomState;
    private Bitmap displayedCrop;
    private final AtomicInteger session=new AtomicInteger();
    private boolean preferGpu=true;
    private ProcessCameraProvider provider;
    private Camera camera;
    private NativeEngine engine;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean running=false;
    private volatile boolean analyzedFrame=false;
    private boolean ready=false,lightOn=false,denied=false;
    private long lastAnalysis=0;
    private String latestText="";
    private SharedPreferences logPreferences;
    private volatile boolean saveEnabled=true;
    private volatile int saveThreshold=80;
    private PlateLogWriter logWriter;
    private TextView logStatus;
    private Button logSettings;
    private AlertDialog logDialog;
    private final ActivityResultLauncher<String> permission=registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if(granted){denied=false;startCamera();}
            else{denied=true;status.setText(R.string.permission_denied);}
        });

    @Override public void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_main);
        View root=findViewById(R.id.root);int padding=dp(16);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(padding+bars.left,padding+bars.top,padding+bars.right,padding+bars.bottom);return insets;
        });
        preview=findViewById(R.id.preview);preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        overlay=findViewById(R.id.overlay);plateText=findViewById(R.id.plate_text);cropView=findViewById(R.id.crop);
        status=findViewById(R.id.status);quality=findViewById(R.id.quality);timing=findViewById(R.id.timing);
        backend=findViewById(R.id.backend);toggle=findViewById(R.id.toggle);torch=findViewById(R.id.torch);copy=findViewById(R.id.copy);
        zoomIn=findViewById(R.id.zoom_in);zoomOut=findViewById(R.id.zoom_out);zoomReset=findViewById(R.id.zoom_reset);
        zoomIn.setOnClickListener(v->setZoom(requestedZoom*1.25f));
        zoomOut.setOnClickListener(v->setZoom(requestedZoom/1.25f));
        zoomReset.setOnClickListener(v->setZoom(1f));
        ScaleGestureDetector pinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){setZoom(requestedZoom*detector.getScaleFactor());return true;}
        });
        overlay.setOnTouchListener((view,event)->{
            pinch.onTouchEvent(event);
            if(event.getActionMasked()==MotionEvent.ACTION_UP)view.performClick();
            return true;
        });
        toggle.setOnClickListener(v->{
            if(!ready)return;
            if(running){stopCamera();return;}
            if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();
            else if(denied&&!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
            else permission.launch(Manifest.permission.CAMERA);
        });
        torch.setOnClickListener(v->{
            if(camera==null)return;lightOn=!lightOn;camera.getCameraControl().enableTorch(lightOn);
            torch.setText(lightOn?R.string.torch_on:R.string.torch);torch.setSelected(lightOn);
        });
        copy.setOnClickListener(v->{
            if(latestText.isEmpty())return;
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name),latestText));
            Toast.makeText(this,R.string.copied,Toast.LENGTH_SHORT).show();
        });
        logPreferences=getSharedPreferences("logging",MODE_PRIVATE);
        saveEnabled=logPreferences.getBoolean("enabled",true);saveThreshold=Math.max(1,Math.min(100,logPreferences.getInt("threshold",80)));
        logStatus=findViewById(R.id.log_status);logSettings=findViewById(R.id.log_settings);
        logWriter=new PlateLogWriter(this,new PlateLogWriter.Listener(){
            @Override public void saved(float confidence){runOnUiThread(()->{if(!isDestroyed())logStatus.setText(getString(R.string.log_saved,confidence*100));});}
            @Override public void failed(boolean busy,Exception error){Log.e("PlateLens","Local record saving failed",error);runOnUiThread(()->{if(!isDestroyed())logStatus.setText(busy?R.string.log_busy:R.string.log_error);});}
        });
        updateLoggingControls();logSettings.setOnClickListener(v->showLogSettings());
        findViewById(R.id.history).setOnClickListener(v->{stopCamera();startActivity(new Intent(this,HistoryActivity.class));});
        backend.setOnClickListener(v->{stopCamera();preferGpu=!preferGpu;loadEngine();});
        loadEngine();
    }
    private void updateLoggingControls(){
        logSettings.setText(saveEnabled?getString(R.string.auto_save,saveThreshold):getString(R.string.auto_save_off));
        logStatus.setText(saveEnabled?getString(R.string.log_ready,saveThreshold):getString(R.string.log_disabled));
    }
    private void showLogSettings(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(8),dp(24),dp(8));
        Switch enabled=new Switch(this);enabled.setText(R.string.log_enable);enabled.setChecked(saveEnabled);enabled.setMinHeight(dp(48));content.addView(enabled);
        TextView threshold=new TextView(this);threshold.setText(getString(R.string.log_threshold,saveThreshold));content.addView(threshold);
        SeekBar slider=new SeekBar(this);slider.setId(R.id.threshold_slider);slider.setMax(100);slider.setProgress(saveThreshold);slider.setEnabled(saveEnabled);slider.setMinimumHeight(dp(48));slider.setContentDescription(getString(R.string.log_threshold,saveThreshold));content.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar view,int progress,boolean fromUser){if(progress<1){view.setProgress(1);return;}threshold.setText(getString(R.string.log_threshold,progress));view.setContentDescription(getString(R.string.log_threshold,progress));}
            @Override public void onStartTrackingTouch(SeekBar view){}
            @Override public void onStopTrackingTouch(SeekBar view){}
        });
        enabled.setOnCheckedChangeListener((view,checked)->slider.setEnabled(checked));
        TextView explanation=new TextView(this);explanation.setText(R.string.log_explanation);explanation.setTextSize(14);content.addView(explanation);
        logDialog=new AlertDialog.Builder(this).setTitle(R.string.log_settings).setView(content).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.save_settings,(dialog,which)->{
                saveEnabled=enabled.isChecked();saveThreshold=slider.getProgress();
                logPreferences.edit().putBoolean("enabled",saveEnabled).putInt("threshold",saveThreshold).apply();updateLoggingControls();
            }).create();
        logDialog.show();
    }
    AlertDialog loggingDialog(){return logDialog;}
    private void loadEngine(){
        ready=false;toggle.setEnabled(false);backend.setEnabled(false);status.setText(R.string.loading);
        final boolean requested=preferGpu;
        worker.execute(()->{
            try {
                if(engine!=null)engine.close();
                engine=new NativeEngine(getAssets(),requested);final String actual=engine.backend();
                runOnUiThread(()->{
                    if(isDestroyed())return;ready=true;toggle.setEnabled(true);backend.setEnabled(true);
                    backend.setText(getString(R.string.backend_switch,actual));
                    status.setText(R.string.permission_needed);
                    if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)preview.post(this::startCamera);
                });
            }catch(RuntimeException|LinkageError error){runOnUiThread(()->{if(!isDestroyed()){status.setText(R.string.model_error);backend.setEnabled(true);}});}
        });
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    boolean hasAnalyzedFrame(){return analyzedFrame;}
    private void startCamera(){
        if(!ready||isDestroyed())return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        final int requestedSession=session.incrementAndGet();
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            if(isDestroyed()||session.get()!=requestedSession)return;
            try {
                detachZoom();provider=future.get();provider.unbindAll();
                Preview stream=new Preview.Builder().setTargetResolution(new Size(1280,960)).build();
                stream.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(1280,960))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(worker,this::analyze);
                UseCaseGroup.Builder group=new UseCaseGroup.Builder().addUseCase(stream).addUseCase(analysis);
                ViewPort viewport=preview.getViewPort();if(viewport!=null)group.setViewPort(viewport);
                camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,group.build());
                analyzedFrame=false;running=true;toggle.setText(R.string.pause);status.setText(R.string.ready);
                attachZoom();
                torch.setEnabled(camera.getCameraInfo().hasFlashUnit());
            } catch(Exception error){Log.e("PlateLens","Camera initialization failed",error);running=false;status.setText(R.string.camera_error);toggle.setText(R.string.start);}
        },ContextCompat.getMainExecutor(this));
    }
    private void attachZoom(){
        final float restoreZoom=requestedZoom;
        final Camera attached=camera;zoomState=attached.getCameraInfo().getZoomState();
        zoomState.observe(this,state->{
            if(camera!=attached)return;
            minZoom=state.getMinZoomRatio();maxZoom=state.getMaxZoomRatio();
            if(!zoomChanging)requestedZoom=state.getZoomRatio();
            zoomReset.setText(String.format(java.util.Locale.ROOT,"%.1f×",state.getZoomRatio()));
            zoomReset.setContentDescription(getString(R.string.zoom_reset_description,state.getZoomRatio()));
            updateZoomControls();
        });
        ZoomState state=zoomState.getValue();
        if(state!=null){minZoom=state.getMinZoomRatio();maxZoom=state.getMaxZoomRatio();}
        setZoom(restoreZoom);
    }
    private void detachZoom(){
        zoomRequest++;zoomChanging=false;
        if(zoomState!=null){zoomState.removeObservers(this);zoomState=null;}
        zoomIn.setEnabled(false);zoomOut.setEnabled(false);zoomReset.setEnabled(false);
    }
    private void updateZoomControls(){
        boolean active=camera!=null&&running&&maxZoom>minZoom;
        zoomIn.setEnabled(active&&requestedZoom<maxZoom-.001f);
        zoomOut.setEnabled(active&&requestedZoom>minZoom+.001f);
        zoomReset.setEnabled(active);
    }
    private void setZoom(float ratio){
        if(camera==null||!running||!Float.isFinite(ratio))return;
        requestedZoom=Math.max(minZoom,Math.min(maxZoom,ratio));updateZoomControls();
        session.incrementAndGet();analyzedFrame=false;overlay.clear();zoomChanging=true;
        latestText="";copy.setEnabled(false);plateText.setTokens(Collections.emptyList(),getString(R.string.no_plate));
        cropView.setImageDrawable(null);cropView.setVisibility(View.GONE);
        if(displayedCrop!=null){displayedCrop.recycle();displayedCrop=null;}
        quality.setText(R.string.no_plate);status.setText(R.string.zoom_changing);
        final Camera attached=camera;final int request=++zoomRequest;
        ListenableFuture<Void> change=attached.getCameraControl().setZoomRatio(requestedZoom);
        change.addListener(()->{
            if(camera!=attached||request!=zoomRequest)return;
            try{change.get();}
            catch(Exception error){
                ZoomState state=attached.getCameraInfo().getZoomState().getValue();
                if(state!=null)requestedZoom=state.getZoomRatio();
                Toast.makeText(this,R.string.zoom_error,Toast.LENGTH_SHORT).show();
            }
            zoomChanging=false;updateZoomControls();status.setText(R.string.ready);
        },ContextCompat.getMainExecutor(this));
    }
    float cameraZoomRatio(){ZoomState state=zoomState==null?null:zoomState.getValue();return state==null?1f:state.getZoomRatio();}
    float maximumCameraZoom(){return maxZoom;}
    private void stopCamera(){
        detachZoom();session.incrementAndGet();running=false;analyzedFrame=false;if(provider!=null)provider.unbindAll();camera=null;lightOn=false;
        torch.setEnabled(false);torch.setText(R.string.torch);toggle.setText(R.string.start);
        overlay.clear();status.setText(R.string.paused);
    }
    private void analyze(ImageProxy image){
        Bitmap raw=null,cropped=null,rotated=null;
        try {
            long now=SystemClock.elapsedRealtime();
            if(!running||zoomChanging||now-lastAnalysis<100)return;
            final int frameSession=session.get();
            lastAnalysis=now;long started=SystemClock.elapsedRealtime();
            raw=image.toBitmap();Rect r=image.getCropRect();
            cropped=Bitmap.createBitmap(raw,r.left,r.top,r.width(),r.height());
            Matrix rotation=new Matrix();rotation.postRotate(image.getImageInfo().getRotationDegrees());
            rotated=Bitmap.createBitmap(cropped,0,0,cropped.getWidth(),cropped.getHeight(),rotation,true);
            if(rotated.getConfig()!=Bitmap.Config.ARGB_8888)rotated=rotated.copy(Bitmap.Config.ARGB_8888,false);
            final Bitmap frame=rotated;
            String result=engine.detect(frame,partial->publish(partial,frame,started,frameSession));
            if(running&&session.get()==frameSession)analyzedFrame=true;
            publish(result,frame,started,frameSession);
        }catch(Exception error){Log.e("PlateLens","Frame processing failed",error);runOnUiThread(()->{if(running)status.setText(R.string.processing_error);});}
        finally {
            image.close();
            if(rotated!=null&&rotated!=cropped&&rotated!=raw)rotated.recycle();
            if(cropped!=null&&cropped!=raw)cropped.recycle();
            if(raw!=null)raw.recycle();
        }
    }
    private void publish(String result,Bitmap frame,long started,int frameSession){
        if(!running||session.get()!=frameSession)return;
        try {
            JSONObject metadata=new JSONObject(result);List<Recognition.Plate> plates=Recognition.parse(result);
            int completed=metadata.getInt("completed");
            if(saveEnabled)for(int i=0;i<Math.min(completed,plates.size());i++){
                if(!running||session.get()!=frameSession)break;
                logWriter.offer(frame,plates.get(i),saveThreshold);
            }
            Bitmap thumbnail=null;
            if(completed>0&&!plates.isEmpty()){
                float[] b=plates.get(0).box;
                int x=Math.max(0,(int)Math.floor(b[0])-5),y=Math.max(0,(int)Math.floor(b[1])-5);
                int right=Math.min(frame.getWidth(),(int)Math.ceil(b[2])+5),bottom=Math.min(frame.getHeight(),(int)Math.ceil(b[3])+5);
                if(right>x&&bottom>y){
                    Bitmap region=Bitmap.createBitmap(frame,x,y,right-x,bottom-y);int width=Math.min(600,region.getWidth());
                    Bitmap scaled=Bitmap.createScaledBitmap(region,width,Math.max(1,Math.round((float)region.getHeight()*width/region.getWidth())),true);
                    thumbnail=scaled.copy(Bitmap.Config.ARGB_8888,false);
                    if(scaled!=region&&scaled!=frame)scaled.recycle();if(region!=frame)region.recycle();
                }
            }
            final Bitmap crop=thumbnail;final int width=frame.getWidth(),height=frame.getHeight();
            final long elapsed=SystemClock.elapsedRealtime()-started,detector=metadata.getLong("detector_ms"),reader=metadata.getLong("reader_ms");
            final String actual=metadata.getString("backend");
            runOnUiThread(()->{
                if(isDestroyed()||!running||session.get()!=frameSession){if(crop!=null)crop.recycle();return;}
                overlay.setPlates(plates,width,height);
                timing.setText(getString(R.string.timing,elapsed,completed,plates.size(),actual,detector,reader));
                backend.setText(getString(R.string.backend_switch,actual));
                cropView.setImageBitmap(crop);if(displayedCrop!=null)displayedCrop.recycle();displayedCrop=crop;
                cropView.setVisibility(crop==null?View.GONE:View.VISIBLE);
                if(plates.isEmpty()){
                    latestText="";copy.setEnabled(false);plateText.setTokens(Collections.emptyList(),getString(R.string.no_plate));
                    quality.setText(R.string.no_plate);quality.setTextColor(getColor(R.color.muted));
                }else{
                    Recognition.Plate plate=plates.get(0);latestText=plate.text;copy.setEnabled(!latestText.isEmpty());
                    plateText.setTokens(plate.tokens,plate.text);boolean standard=PlateFormat.isStandard(plate.text);
                    quality.setText(standard?R.string.standard:R.string.uncertain);quality.setTextColor(getColor(standard?R.color.accent:R.color.warning));
                }
                status.setText(completed<plates.size()?getString(R.string.progress,completed,plates.size()):getString(R.string.ready));
            });
        }catch(Exception error){Log.e("PlateLens","Result decoding failed",error);}
    }
    @Override protected void onDestroy(){
        detachZoom();session.incrementAndGet();running=false;if(provider!=null)provider.unbindAll();
        if(displayedCrop!=null){cropView.setImageDrawable(null);displayedCrop.recycle();displayedCrop=null;}
        if(logWriter!=null)logWriter.close();
        worker.execute(()->{if(engine!=null)engine.close();});worker.shutdown();super.onDestroy();
    }
}
