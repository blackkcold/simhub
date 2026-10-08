package com.blackkcold.simhub;
import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
public final class AppLogger {
    private static final Object LOCK=new Object();private static final long MAX_BYTES=512L*1024L;private static final int MAX_FILES=5;private static final String CURRENT="simhub.log";
    public static void i(Context c,String tag,String message){write(c,"INFO",tag,message,null);}
    public static void w(Context c,String tag,String message){write(c,"WARN",tag,message,null);}
    public static void e(Context c,String tag,String message,Throwable error){write(c,"ERROR",tag,message,error);}
    private static void write(Context c,String level,String tag,String message,Throwable error){
        String safe=LogSanitizer.sanitize(message+(error==null?"":" | "+error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage())));
        if("ERROR".equals(level))Log.e("SIMHub/"+tag,safe);else if("WARN".equals(level))Log.w("SIMHub/"+tag,safe);else Log.i("SIMHub/"+tag,safe);
        if(c==null||!DeveloperSettings.isEnabled(c))return;
        synchronized(LOCK){try{File dir=dir(c);if(!dir.exists()&&!dir.mkdirs())return;rotateIfNeeded(dir);File f=new File(dir,CURRENT);String ts=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());String line=ts+"  "+level+"  "+cleanTag(tag)+"  "+safe+"\n";try(FileOutputStream out=new FileOutputStream(f,true)){out.write(line.getBytes(StandardCharsets.UTF_8));}}catch(Exception ignored){}}
    }
    public static String recent(Context c,int maxChars){if(c==null)return "";synchronized(LOCK){try{File f=new File(dir(c),CURRENT);if(!f.exists())return "";byte[] all=read(f);String s=new String(all,StandardCharsets.UTF_8);return s.length()<=maxChars?s:s.substring(s.length()-maxChars);}catch(Exception e){return "";}}}
    public static List<File> files(Context c){ArrayList<File> out=new ArrayList<>();File d=dir(c);File current=new File(d,CURRENT);if(current.exists())out.add(current);for(int i=1;i<MAX_FILES;i++){File f=new File(d,"simhub."+i+".log");if(f.exists())out.add(f);}return out;}
    public static void clear(Context c){synchronized(LOCK){for(File f:files(c))try{f.delete();}catch(Exception ignored){}}}
    private static void rotateIfNeeded(File d){File current=new File(d,CURRENT);if(!current.exists()||current.length()<MAX_BYTES)return;File oldest=new File(d,"simhub."+(MAX_FILES-1)+".log");if(oldest.exists())oldest.delete();for(int i=MAX_FILES-2;i>=1;i--){File src=new File(d,"simhub."+i+".log");if(src.exists())src.renameTo(new File(d,"simhub."+(i+1)+".log"));}current.renameTo(new File(d,"simhub.1.log"));}
    private static byte[] read(File f)throws Exception{long len=f.length();if(len<=0)return new byte[0];int size=(int)Math.min(len,Integer.MAX_VALUE);byte[] out=new byte[size];int off=0;try(FileInputStream in=new FileInputStream(f)){while(off<size){int n=in.read(out,off,size-off);if(n<0)break;off+=n;}}if(off==size)return out;byte[] trimmed=new byte[off];System.arraycopy(out,0,trimmed,0,off);return trimmed;}
    private static File dir(Context c){return new File(c.getFilesDir(),"logs");}
    private static String cleanTag(String tag){String s=tag==null?"General":tag.replaceAll("[^A-Za-z0-9_.-]","_");return s.length()>40?s.substring(0,40):s;}
    private AppLogger(){}
}