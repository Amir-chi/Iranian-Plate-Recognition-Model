package ir.platelens.app;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

public final class NativeEngine implements AutoCloseable {
    static { System.loadLibrary("platelens"); }
    private long handle;
    private final AssetManager assets;
    public interface Progress { void onProgress(String result); }
    public NativeEngine(AssetManager assets) { this(assets,true); }
    public NativeEngine(AssetManager assets,boolean preferGpu) { this.assets=assets;handle=nativeLoad(this.assets,preferGpu); }
    public synchronized String backend() { return nativeBackend(handle); }
    public synchronized String detect(Bitmap frame) {
        return detect(frame,null);
    }
    public synchronized String detect(Bitmap frame,Progress progress) {
        if(handle==0)throw new IllegalStateException("Engine closed");
        return nativeDetect(handle,frame,progress);
    }
    @Override public synchronized void close() { if(handle!=0){nativeClose(handle);handle=0;} }
    private static native long nativeLoad(AssetManager assets,boolean preferGpu);
    private static native String nativeBackend(long handle);
    private static native String nativeDetect(long handle,Bitmap frame,Progress progress);
    private static native void nativeClose(long handle);
}
