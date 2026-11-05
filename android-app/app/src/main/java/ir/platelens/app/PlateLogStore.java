package ir.platelens.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Private local storage; each JPEG and its metadata are committed as one row. */
public final class PlateLogStore extends SQLiteOpenHelper {
    static final long COOLDOWN_MS=30_000;
    static final int PAGE_SIZE=50;
    static final class Record {
        final long id,time;final String text,metadata;final float confidence;final byte[] jpeg;
        Record(Cursor cursor){id=cursor.getLong(0);time=cursor.getLong(1);text=cursor.getString(2);confidence=cursor.getFloat(3);metadata=cursor.getString(4);jpeg=cursor.getBlob(5);}
    }
    public PlateLogStore(Context context){this(context,"plate-log.db");}
    PlateLogStore(Context context,String name){super(context.getApplicationContext(),name,null,1);setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY, saved_at INTEGER NOT NULL, plate_text TEXT NOT NULL, confidence REAL NOT NULL, metadata TEXT NOT NULL, image BLOB NOT NULL)");
        db.execSQL("CREATE INDEX record_text_time ON records(plate_text,saved_at DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Unsupported log schema upgrade");}
    static boolean eligible(Recognition.Plate plate,int threshold){return !plate.text.isEmpty()&&!plate.tokens.isEmpty()&&plate.saveConfidence>0&&plate.saveConfidence*100>=threshold;}
    long save(Bitmap crop,Recognition.Plate plate,int threshold,long now) throws Exception {
        if(!eligible(plate,threshold))return -1;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor previous=db.rawQuery("SELECT saved_at FROM records WHERE plate_text=? ORDER BY saved_at DESC LIMIT 1",new String[]{plate.text})){
                if(previous.moveToFirst()){long last=previous.getLong(0);if(now>=last&&now-last<COOLDOWN_MS)return -1;}
            }
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!crop.compress(Bitmap.CompressFormat.JPEG,90,bytes))throw new IOException("JPEG encoding failed");
            JSONObject metadata=new JSONObject().put("schema_version",1).put("saved_at_epoch_ms",now).put("text",plate.text)
                .put("confidence",plate.saveConfidence).put("plate_confidence",plate.confidence)
                .put("character_confidences",new JSONArray(plate.characterConfidences)).put("tokens",new JSONArray(plate.tokens))
                .put("threshold_percent",threshold).put("standard_layout",PlateFormat.isStandard(plate.text))
                .put("crop_width",crop.getWidth()).put("crop_height",crop.getHeight());
            JSONArray box=new JSONArray();for(float coordinate:plate.box)box.put(coordinate);metadata.put("source_box",box);
            ContentValues values=new ContentValues();values.put("saved_at",now);values.put("plate_text",plate.text);
            values.put("confidence",plate.saveConfidence);values.put("metadata",metadata.toString());values.put("image",bytes.toByteArray());
            long id=db.insertOrThrow("records",null,values);db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    long count(){return DatabaseUtils.longForQuery(getReadableDatabase(),"SELECT COUNT(*) FROM records",null);}
    List<Record> page(int page){
        List<Record> result=new ArrayList<>();
        try(Cursor rows=getReadableDatabase().rawQuery("SELECT id,saved_at,plate_text,confidence,metadata,image FROM records ORDER BY id DESC LIMIT ? OFFSET ?",new String[]{String.valueOf(PAGE_SIZE),String.valueOf((long)page*PAGE_SIZE)})){
            while(rows.moveToNext())result.add(new Record(rows));
        }
        return result;
    }
    int exportZip(OutputStream output) throws Exception {
        SQLiteDatabase db=getReadableDatabase();long last=DatabaseUtils.longForQuery(db,"SELECT COALESCE(MAX(id),0) FROM records",null);int count=0;
        // Rows are immutable and never deleted here, so this ID bounds a consistent export while new rows arrive.
        try(ZipOutputStream zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            try(Cursor rows=db.rawQuery("SELECT id,image FROM records WHERE id<=? ORDER BY id",new String[]{String.valueOf(last)})){
                while(rows.moveToNext()){
                    zip.putNextEntry(new ZipEntry("plates/"+rows.getLong(0)+".jpg"));zip.write(rows.getBlob(1));zip.closeEntry();count++;
                }
            }
            zip.putNextEntry(new ZipEntry("records.json"));zip.write("{\"schema_version\":1,\"records\":[".getBytes(StandardCharsets.UTF_8));boolean first=true;
            try(Cursor rows=db.rawQuery("SELECT id,metadata FROM records WHERE id<=? ORDER BY id",new String[]{String.valueOf(last)})){
                while(rows.moveToNext()){
                    JSONObject record=new JSONObject(rows.getString(1)).put("id",rows.getLong(0)).put("image","plates/"+rows.getLong(0)+".jpg");
                    if(!first)zip.write(',');first=false;zip.write(record.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            zip.write("]}".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        return count;
    }
}
