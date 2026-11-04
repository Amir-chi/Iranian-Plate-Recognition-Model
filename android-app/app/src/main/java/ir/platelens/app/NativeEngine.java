package ir.platelens.app;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

public final class NativeEngine implements AutoCloseable {
    static { System.loadLibrary("platelens"); }
    private long handle;
    public NativeEngine(AssetManager assets) { handle=nativeLoad(assets); }
    public synchronized String detect(Bitmap frame) {
        if(handle==0)throw new IllegalStateException("Engine closed");
        return nativeDetect(handle,frame);
    }
    @Override public synchronized void close() { if(handle!=0){nativeClose(handle);handle=0;} }
    private static native long nativeLoad(AssetManager assets);
    private static native String nativeDetect(long handle,Bitmap frame);
    private static native void nativeClose(long handle);
}
