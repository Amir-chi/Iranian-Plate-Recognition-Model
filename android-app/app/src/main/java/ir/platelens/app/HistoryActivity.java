package ir.platelens.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HistoryActivity extends EnglishActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private PlateLogStore store;
    private TextView status,empty;
    private Button previous,next,refresh,export;
    private ListView list;
    private int page=0;
    private long count=0;
    private List<PlateLogStore.Record> records=Collections.emptyList();
    private final ActivityResultLauncher<String> destination=registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"),uri->{if(uri!=null)exportTo(uri);});
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_history);store=new PlateLogStore(this);
        View root=findViewById(R.id.history_root);int padding=dp(16);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(padding+bars.left,padding+bars.top,padding+bars.right,padding+bars.bottom);return insets;});
        status=findViewById(R.id.history_status);empty=findViewById(R.id.history_empty);list=findViewById(R.id.history_list);
        previous=findViewById(R.id.history_previous);next=findViewById(R.id.history_next);refresh=findViewById(R.id.history_refresh);export=findViewById(R.id.history_export);
        list.setEmptyView(empty);list.setAdapter(adapter);
        findViewById(R.id.history_back).setOnClickListener(v->finish());
        refresh.setOnClickListener(v->{page=0;load();});previous.setOnClickListener(v->{page=Math.max(0,page-1);load();});next.setOnClickListener(v->{page++;load();});
        export.setOnClickListener(v->destination.launch("plate-lens-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".zip"));
        list.setOnItemClickListener((parent,view,position,id)->showRecord(records.get(position)));
        load();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void controls(boolean enabled){refresh.setEnabled(enabled);export.setEnabled(enabled&&count>0);previous.setEnabled(enabled&&page>0);next.setEnabled(enabled&&(long)(page+1)*PlateLogStore.PAGE_SIZE<count);}
    private void load(){
        controls(false);status.setText(R.string.history_loading);empty.setText(R.string.history_loading);final int requested=page;
        io.execute(()->{
            try{long total=store.count();List<PlateLogStore.Record> values=store.page(requested);
                runOnUiThread(()->{if(isDestroyed())return;count=total;records=values;adapter.notifyDataSetChanged();empty.setText(R.string.history_empty);status.setText(getString(R.string.history_page,total,page+1));controls(true);});
            }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()){status.setText(R.string.history_error);empty.setText(R.string.history_error);controls(true);}});}
        });
    }
    private void exportTo(Uri uri){
        controls(false);status.setText(R.string.exporting);
        io.execute(()->{
            try(OutputStream stream=getContentResolver().openOutputStream(uri,"w")){
                if(stream==null)throw new java.io.IOException("No output stream");int written=store.exportZip(stream);
                runOnUiThread(()->{if(!isDestroyed()){status.setText(getString(R.string.exported,written));controls(true);}});
            }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()){status.setText(R.string.export_failed);controls(true);}});}
        });
    }
    private String details(PlateLogStore.Record record){return getString(R.string.history_details,new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date(record.time)),record.confidence*100);}
    private void showRecord(PlateLogStore.Record record){
        LinearLayout content=row(record,false);
        new AlertDialog.Builder(this).setTitle(R.string.history_title).setView(content).setPositiveButton(R.string.close,null)
            .setNeutralButton(R.string.copy,(dialog,which)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name),record.text));Toast.makeText(this,R.string.copied,Toast.LENGTH_SHORT).show();}).show();
    }
    private LinearLayout row(PlateLogStore.Record record,boolean small){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),dp(12),dp(12),dp(12));content.setBackgroundResource(R.drawable.card);
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(getString(R.string.history_crop));
        content.addView(image,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(small?72:140)));
        content.addView(new PlateTextView(this,null),new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(44)));
        TextView info=new TextView(this);info.setTextColor(getColor(R.color.muted));info.setTextSize(14);content.addView(info);
        bind(content,record,small);return content;
    }
    private void bind(LinearLayout content,PlateLogStore.Record record,boolean small){
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=small?2:1;
        ((ImageView)content.getChildAt(0)).setImageBitmap(BitmapFactory.decodeByteArray(record.jpeg,0,record.jpeg.length,options));
        List<String> tokens=new ArrayList<>();
        try{JSONArray array=new JSONObject(record.metadata).getJSONArray("tokens");for(int i=0;i<array.length();i++)tokens.add(array.getString(i));}catch(Exception ignored){tokens.add(record.text);}
        ((PlateTextView)content.getChildAt(1)).setTokens(tokens,record.text);
        ((TextView)content.getChildAt(2)).setText(details(record));
    }
    private final BaseAdapter adapter=new BaseAdapter(){
        @Override public int getCount(){return records.size();}
        @Override public Object getItem(int position){return records.get(position);}
        @Override public long getItemId(int position){return records.get(position).id;}
        @Override public View getView(int position,View recycled,ViewGroup parent){if(recycled==null)return row(records.get(position),true);bind((LinearLayout)recycled,records.get(position),true);return recycled;}
    };
    @Override protected void onDestroy(){io.execute(store::close);io.shutdown();super.onDestroy();}
}
