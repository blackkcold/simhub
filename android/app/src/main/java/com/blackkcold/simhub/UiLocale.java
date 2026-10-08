package com.blackkcold.simhub;
import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;
public final class UiLocale {
    public static final String SYSTEM="system",ZH_CN="zh-CN",EN="en";private static final String PREF="simhub_ui",KEY="language";
    public static String get(Context c){return prefs(c).getString(KEY,SYSTEM);}
    public static void set(Context c,String value){prefs(c).edit().putString(KEY,normalize(value)).apply();}
    public static void apply(Activity a){String value=get(a),tag=SYSTEM.equals(value)?"":value;if(Build.VERSION.SDK_INT>=33){LocaleManager lm=a.getSystemService(LocaleManager.class);if(lm!=null){LocaleList desired=tag.isEmpty()?LocaleList.getEmptyLocaleList():LocaleList.forLanguageTags(tag);if(!lm.getApplicationLocales().equals(desired))lm.setApplicationLocales(desired);}return;}Locale locale=tag.isEmpty()?Resources.getSystem().getConfiguration().getLocales().get(0):Locale.forLanguageTag(tag);Locale.setDefault(locale);Resources r=a.getResources();Configuration cfg=new Configuration(r.getConfiguration());cfg.setLocale(locale);r.updateConfiguration(cfg,r.getDisplayMetrics());}
    public static int index(Context c){String v=get(c);return ZH_CN.equals(v)?1:EN.equals(v)?2:0;}
    public static String fromIndex(int index){return index==1?ZH_CN:index==2?EN:SYSTEM;}
    private static String normalize(String v){return ZH_CN.equals(v)?ZH_CN:EN.equals(v)?EN:SYSTEM;}
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private UiLocale(){}
}