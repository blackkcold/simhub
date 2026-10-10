package com.blackkcold.simhub;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Public, read-only GitHub stable-release metadata source. OTA must never use
 * enrollment state, Relay credentials, SMS permissions or Vault secrets.
 * The downloaded APK is additionally SHA-256 and signing-certificate pinned.
 */
public final class ReleaseUpdateSource {
    private static final String API="https://api.github.com/repos/blackkcold/simhub/releases/latest";
    private static final Pattern VERSION=Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern DIGEST=Pattern.compile("[0-9a-f]{64}");
    private ReleaseUpdateSource(){}

    private static JSONObject fetchJson(String url,int limit)throws Exception{
        URL target=new URL(url);
        if(!"https".equalsIgnoreCase(target.getProtocol()))throw new SecurityException("HTTPS release metadata required");
        HttpURLConnection connection=(HttpURLConnection)target.openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent","simhub-android-updater");
        connection.setRequestProperty("Accept","application/vnd.github+json");
        try{
            if(connection.getResponseCode()!=200)throw new java.io.IOException("Update source HTTP "+connection.getResponseCode());
            try(InputStream stream=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] chunk=new byte[4096];int n;
                while((n=stream.read(chunk))!=-1){
                    if(out.size()+n>limit)throw new SecurityException("Update metadata exceeds limit");
                    out.write(chunk,0,n);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8));
            }
        }finally{connection.disconnect();}
    }

    private static String assetUrl(JSONObject release,String name,String version)throws Exception{
        JSONArray assets=release.optJSONArray("assets");
        if(assets==null)throw new SecurityException("Release assets missing");
        String required="https://github.com/blackkcold/simhub/releases/download/v"+version+"/"+name;
        for(int i=0;i<assets.length();i++){
            JSONObject asset=assets.optJSONObject(i);
            if(asset!=null && name.equals(asset.optString("name")) &&
                    required.equals(asset.optString("browser_download_url")))return required;
        }
        throw new SecurityException("Expected stable release asset missing");
    }

    /** Pure metadata validation, independent of transport and enrollment. */
    static JSONObject validate(JSONObject release,JSONObject manifest)throws Exception{
        String tag=release.optString("tag_name","");
        if(!tag.startsWith("v")||!VERSION.matcher(tag.substring(1)).matches()||
                release.optBoolean("draft",true)||release.optBoolean("prerelease",true)||
                release.optString("published_at","").isBlank())
            throw new SecurityException("No verified stable release");
        String version=tag.substring(1);
        if(manifest.optInt("schemaVersion")!=1||
                !"stable".equals(manifest.optString("channel"))||
                !version.equals(manifest.optString("release")))
            throw new SecurityException("Release manifest mismatch");
        JSONObject android=manifest.optJSONObject("android");
        if(android==null||!"com.blackkcold.simhub".equals(android.optString("packageName"))||
                android.optInt("versionCode",0)<=0||
                !version.equals(android.optString("versionName")))
            throw new SecurityException("APK version or package mismatch");
        String name="simhub-agent-v"+version+"-release.apk";
        if(!name.equals(android.optString("asset"))||
                !DIGEST.matcher(android.optString("sha256","").toLowerCase(Locale.ROOT)).matches())
            throw new SecurityException("Invalid APK release metadata");
        return new JSONObject().put("available",true)
                .put("versionName",version).put("versionCode",android.getInt("versionCode"))
                .put("sha256",android.getString("sha256"))
                .put("url",assetUrl(release,name,version))
                .put("notes",manifest.optString("notes","").substring(0,
                    Math.min(1000,manifest.optString("notes","").length())));
    }

    public static JSONObject latest()throws Exception{
        JSONObject release=fetchJson(API,262144);
        String tag=release.optString("tag_name","");
        if(!tag.startsWith("v")||!VERSION.matcher(tag.substring(1)).matches()||
                release.optBoolean("draft",true)||release.optBoolean("prerelease",true))
            throw new SecurityException("Untrusted release tag");
        String version=tag.substring(1);
        String manifestUrl=assetUrl(release,"update-manifest.json",version);
        return validate(release,fetchJson(manifestUrl,8192));
    }
}
