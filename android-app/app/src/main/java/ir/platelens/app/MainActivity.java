package ir.platelens.app;

import android.Manifest;
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
import android.view.View;
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

public final class MainActivity extends ComponentActivity {
    private PreviewView preview;
    private PlateOverlay overlay;
    private PlateTextView plateText;
    private ImageView cropView;
    private TextView status,quality,timing;
    private Button toggle,torch,copy;
    private ProcessCameraProvider provider;
    private Camera camera;
    private NativeEngine engine;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean running=false;
    private boolean ready=false,lightOn=false,denied=false;
    private long lastAnalysis=0;
    private String latestText="";
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
        toggle=findViewById(R.id.toggle);torch=findViewById(R.id.torch);copy=findViewById(R.id.copy);
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
        worker.execute(()->{
            try {
                engine=new NativeEngine(getAssets());
                runOnUiThread(()->{
                    if(isDestroyed())return;ready=true;toggle.setEnabled(true);
                    status.setText(R.string.permission_needed);
                    if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)preview.post(this::startCamera);
                });
            }catch(RuntimeException|LinkageError error){runOnUiThread(()->status.setText(R.string.model_error));}
        });
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void startCamera(){
        if(!ready||isDestroyed())return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            if(isDestroyed())return;
            try {
                provider=future.get();provider.unbindAll();
                Preview stream=new Preview.Builder().setTargetResolution(new Size(1280,960)).build();
                stream.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(1280,960))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(worker,this::analyze);
                UseCaseGroup.Builder group=new UseCaseGroup.Builder().addUseCase(stream).addUseCase(analysis);
                ViewPort viewport=preview.getViewPort();if(viewport!=null)group.setViewPort(viewport);
                camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,group.build());
                running=true;toggle.setText(R.string.pause);status.setText(R.string.ready);
                torch.setEnabled(camera.getCameraInfo().hasFlashUnit());
            } catch(Exception error){running=false;status.setText(R.string.camera_error);toggle.setText(R.string.start);}
        },ContextCompat.getMainExecutor(this));
    }
    private void stopCamera(){
        running=false;if(provider!=null)provider.unbindAll();camera=null;lightOn=false;
        torch.setEnabled(false);torch.setText(R.string.torch);toggle.setText(R.string.start);
        overlay.clear();status.setText(R.string.paused);
    }
    private void analyze(ImageProxy image){
        Bitmap raw=null,cropped=null,rotated=null;
        try {
            long now=SystemClock.elapsedRealtime();
            if(!running||now-lastAnalysis<350)return;
            lastAnalysis=now;long started=SystemClock.elapsedRealtime();
            raw=image.toBitmap();Rect r=image.getCropRect();
            cropped=Bitmap.createBitmap(raw,r.left,r.top,r.width(),r.height());
            Matrix rotation=new Matrix();rotation.postRotate(image.getImageInfo().getRotationDegrees());
            rotated=Bitmap.createBitmap(cropped,0,0,cropped.getWidth(),cropped.getHeight(),rotation,true);
            if(rotated.getConfig()!=Bitmap.Config.ARGB_8888)rotated=rotated.copy(Bitmap.Config.ARGB_8888,false);
            List<Recognition.Plate> plates=Recognition.parse(engine.detect(rotated));
            Bitmap thumbnail=null;
            if(!plates.isEmpty()){
                float[] b=plates.get(0).box;
                int x=Math.max(0,(int)Math.floor(b[0])-5),y=Math.max(0,(int)Math.floor(b[1])-5);
                int right=Math.min(rotated.getWidth(),(int)Math.ceil(b[2])+5),bottom=Math.min(rotated.getHeight(),(int)Math.ceil(b[3])+5);
                if(right>x&&bottom>y){Bitmap region=Bitmap.createBitmap(rotated,x,y,right-x,bottom-y);
                    int width=Math.min(600,region.getWidth());thumbnail=Bitmap.createScaledBitmap(region,width,Math.max(1,Math.round((float)region.getHeight()*width/region.getWidth())),true).copy(Bitmap.Config.ARGB_8888,false);}
            }
            final Bitmap crop=thumbnail;final int width=rotated.getWidth(),height=rotated.getHeight();final long elapsed=SystemClock.elapsedRealtime()-started;
            runOnUiThread(()->{
                if(isDestroyed()||!running)return;
                overlay.setPlates(plates,width,height);timing.setText(getString(R.string.timing,elapsed,plates.size()));
                if(plates.isEmpty()){
                    latestText="";copy.setEnabled(false);plateText.setTokens(Collections.emptyList(),getString(R.string.no_plate));
                    cropView.setImageDrawable(null);cropView.setVisibility(View.GONE);quality.setText(R.string.no_plate);quality.setTextColor(getColor(R.color.muted));
                }else{
                    Recognition.Plate plate=plates.get(0);latestText=plate.text;copy.setEnabled(!latestText.isEmpty());
                    plateText.setTokens(plate.tokens,plate.text);boolean standard=PlateFormat.isStandard(plate.text);
                    quality.setText(standard?R.string.standard:R.string.uncertain);quality.setTextColor(getColor(standard?R.color.accent:R.color.warning));
                    cropView.setImageBitmap(crop);cropView.setVisibility(crop==null?View.GONE:View.VISIBLE);
                }
                status.setText(R.string.ready);
            });
        }catch(Exception error){runOnUiThread(()->{if(running)status.setText(R.string.processing_error);});}
        finally {
            image.close();
            if(rotated!=null&&rotated!=cropped&&rotated!=raw)rotated.recycle();
            if(cropped!=null&&cropped!=raw)cropped.recycle();
            if(raw!=null)raw.recycle();
        }
    }
    @Override protected void onDestroy(){
        running=false;if(provider!=null)provider.unbindAll();
        worker.execute(()->{if(engine!=null)engine.close();});worker.shutdown();super.onDestroy();
    }
}
