package com.blackkcold.simhub;
import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
public final class DiagnosticExporter {
    public static File create(Context c)throws Exception{
        File out=new File(c.getCacheDir(),"simhub-diagnostics.zip");if(out.exists())out.delete();
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(out))){
            AgentConfig cfg=new AgentConfig(c);
            String summary="SIM Hub diagnostics\n"+"appVersion="+BuildConfig.VERSION_NAME+"\n"+"device="+Build.MANUFACTURER+" "+Build.MODEL+"\n"+"android="+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")\n"+"developerMode="+DeveloperSettings.isEnabled(c)+"\n"+"enrolled="+cfg.isEnrolled()+"\n"+"alwaysOnRelay="+cfg.alwaysOn()+"\n"+"pendingEncryptedEvents="+LocalStore.get(c).pendingEventCount()+"\n";
            put(zip,"summary.txt",LogSanitizer.sanitize(summary).getBytes(StandardCharsets.UTF_8));
            put(zip,"compatibility-audit.json",CompatibilityAudit.export(c).getBytes(StandardCharsets.UTF_8));
            put(zip,"compatibility-status.json",CompatibilityManager.inspect(c,false).toString(2).getBytes(StandardCharsets.UTF_8));
            int index=0;for(File f:AppLogger.files(c)){try(FileInputStream in=new FileInputStream(f)){zip.putNextEntry(new ZipEntry("logs/"+(index++)+"-"+f.getName()));byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);zip.closeEntry();}}
        }return out;
    }
    private static void put(ZipOutputStream zip,String name,byte[] data)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
    private DiagnosticExporter(){}
}