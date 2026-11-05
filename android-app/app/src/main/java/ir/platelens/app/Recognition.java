package ir.platelens.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class Recognition {
    public static final class Plate {
        final float[] box;
        final float confidence,saveConfidence;
        final List<Float> characterConfidences;
        final String text;
        final List<String> tokens;
        Plate(JSONObject value) throws JSONException {
            JSONArray coordinates=value.getJSONArray("box");box=new float[4];
            for(int i=0;i<4;i++)box[i]=(float)coordinates.getDouble(i);
            confidence=(float)value.getDouble("confidence");text=value.getString("text");
            tokens=new ArrayList<>();JSONArray items=value.getJSONArray("tokens");
            for(int i=0;i<items.length();i++)tokens.add(items.getString(i));
            characterConfidences=new ArrayList<>();JSONArray scores=value.optJSONArray("character_confidences");
            float minimum=confidence;
            if(scores==null||scores.length()!=tokens.size()||tokens.isEmpty()||!Float.isFinite(confidence)||confidence<0||confidence>1)minimum=0;
            if(scores!=null)for(int i=0;i<scores.length();i++){
                float score=(float)scores.getDouble(i);characterConfidences.add(score);
                minimum=!Float.isFinite(score)||score<0||score>1?0:Math.min(minimum,score);
            }
            saveConfidence=minimum;
        }
    }
    public static List<Plate> parse(String json) throws JSONException {
        List<Plate> result=new ArrayList<>();JSONArray values=new JSONObject(json).getJSONArray("plates");
        for(int i=0;i<values.length();i++)result.add(new Plate(values.getJSONObject(i)));
        return result;
    }
}
