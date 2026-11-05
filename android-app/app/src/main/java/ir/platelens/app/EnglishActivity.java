package ir.platelens.app;

import android.content.Context;
import android.content.res.Configuration;
import androidx.activity.ComponentActivity;
import java.util.Locale;

/** Keep app controls and number formatting English, independently of the phone language. */
public class EnglishActivity extends ComponentActivity {
    @Override protected void attachBaseContext(Context base){
        Configuration configuration=new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(Locale.ENGLISH);configuration.setLayoutDirection(Locale.ENGLISH);
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }
}
