package ir.platelens.app;

public final class PreviewGeometry {
    private PreviewGeometry() {}
    public static float[] fitCenter(float[] box,int frameWidth,int frameHeight,int viewWidth,int viewHeight) {
        if(frameWidth<=0||frameHeight<=0)throw new IllegalArgumentException("Invalid frame size");
        float scale=Math.min((float)viewWidth/frameWidth,(float)viewHeight/frameHeight);
        float dx=(viewWidth-frameWidth*scale)/2,dy=(viewHeight-frameHeight*scale)/2;
        return new float[]{box[0]*scale+dx,box[1]*scale+dy,box[2]*scale+dx,box[3]*scale+dy};
    }
}
