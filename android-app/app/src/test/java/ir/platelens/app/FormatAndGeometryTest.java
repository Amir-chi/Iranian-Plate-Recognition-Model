package ir.platelens.app;
import org.junit.Test;
import static org.junit.Assert.*;

public class FormatAndGeometryTest {
    @Test public void extraDetectionIsUnverifiedWithoutTruncation(){
        assertTrue(PlateFormat.isStandard("۲۴م۶۹۱۱۳"));
        assertFalse(PlateFormat.isStandard("۲۴م۶۹۱۱۲۹"));
        assertFalse(PlateFormat.isStandard("۲۴۶م۹۱۱۳"));
        // Wrong digits can still match a shape; never label this as a verified plate.
        assertTrue(PlateFormat.isStandard("۲۴م۶۹۱۱۲"));
    }
    @Test public void landscapeFrameMapsInsidePortraitPreview(){
        assertArrayEquals(new float[]{0,187.5f,300,412.5f},PreviewGeometry.fitCenter(new float[]{0,0,640,480},640,480,300,600),.001f);
    }
    @Test public void portraitFramePreservesCenterAndScale(){
        assertArrayEquals(new float[]{0,33.333f,300,566.667f},PreviewGeometry.fitCenter(new float[]{0,0,720,1280},720,1280,300,600),.01f);
    }
}
