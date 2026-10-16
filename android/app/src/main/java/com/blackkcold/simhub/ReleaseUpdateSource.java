package com.blackkcold.simhub;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release discovery is pinned at build time to the installed Android package.
 * Neither Relay state nor an untrusted response may switch Stable to Dev.
 */
public final class ReleaseUpdateSource {
    private static final String STABLE_API="https://api.github.com/repos/blackkcold/simhub/releases/latest";
    private static final String DEV_API="https://api.github.com/repos/blackkcold/simhub/releases?per_page=100";
    private static final Pattern STABLE=Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern DEV=Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)-dev\\.([1-9][0-9]*)");
    private static final Pattern DIGEST=Pattern.compile("[0-9a-f]{64}");
    private ReleaseUpdateSource(){}

    static boolean matchesChannel(String version){
        return ("dev".equals(BuildConfig.RELEASE_CHANNEL)?DEV:STABLE).matcher(version).matches();
    }

    private static String fetchText(String url,int limit)throws Exception{
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
                return out.toString(StandardCharsets.UTF_8);
            }
        }finally{connection.disconnect();}
    }

    private static JSONObject fetchJson(String url,int limit)throws Exception{
        return new JSONObject(fetchText(url,limit));
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
        throw new SecurityException("Expected channel release asset missing");
    }

    /** Verify the release and manifest together. This is independent of transport credentials. */
    static JSONObject validate(JSONObject release,JSONObject manifest)throws Exception{
        return validateChannel(release,manifest,BuildConfig.RELEASE_CHANNEL);
    }

    static JSONObject validateChannel(JSONObject release,JSONObject manifest,String channel)throws Exception{
        if(!"stable".equals(channel)&&!"dev".equals(channel))throw new SecurityException("Unknown release channel");
        String tag=release.optString("tag_name","");
        String version=tag.startsWith("v")?tag.substring(1):"";
        Pattern expected="dev".equals(channel)?DEV:STABLE;
        if(!expected.matcher(version).matches()||
                release.optBoolean("draft",true)||
                release.optBoolean("prerelease",false)!="dev".equals(channel)||
                release.optString("published_at","").isBlank())
            throw new SecurityException("Release channel or version mismatch");
        if(manifest.optInt("schemaVersion")!=1||
                !channel.equals(manifest.optString("channel"))||
                !version.equals(manifest.optString("release")))
            throw new SecurityException("Release manifest mismatch");
        JSONObject android=manifest.optJSONObject("android");
        String packageName="dev".equals(channel)?"com.blackkcold.simhub.dev":"com.blackkcold.simhub";
        if(android==null||!packageName.equals(android.optString("packageName"))||
                android.optInt("versionCode",0)<=0||
                !version.equals(android.optString("versionName")))
            throw new SecurityException("APK version or package mismatch");
        String name="simhub-agent-v"+version+"-release.apk";
        if(!name.equals(android.optString("asset"))||
                !DIGEST.matcher(android.optString("sha256","").toLowerCase(Locale.ROOT)).matches())
            throw new SecurityException("Invalid APK release metadata");
        return new JSONObject().put("available",true).put("channel",channel)
                .put("versionName",version).put("versionCode",android.getInt("versionCode"))
                .put("sha256",android.getString("sha256"))
                .put("url",assetUrl(release,name,version))
                .put("notes",manifest.optString("notes","").substring(0,
                    Math.min(1000,manifest.optString("notes","").length())));
    }

    private static int compareDev(String first,String second){
        Matcher a=DEV.matcher(first),b=DEV.matcher(second);
        if(!a.matches()||!b.matches())throw new IllegalArgumentException("Invalid Dev version");
        for(int i=1;i<=4;i++){
            int x=Integer.parseInt(a.group(i)),y=Integer.parseInt(b.group(i));
            if(x!=y)return Integer.compare(x,y);
        }
        return 0;
    }

    /** Select latest published Dev without trusting list order or accepting stable tags. */
    static JSONObject selectDev(JSONArray releases){
        JSONObject winner=null;
        for(int i=0;i<releases.length();i++){
            JSONObject next=releases.optJSONObject(i);
            if(next==null||next.optBoolean("draft",true)||!next.optBoolean("prerelease",false)
                    ||next.optString("published_at","").isBlank())continue;
            String name=next.optString("tag_name","");
            if(!name.startsWith("v")||!DEV.matcher(name.substring(1)).matches())continue;
            try{
                if(winner==null||compareDev(name.substring(1),
                        winner.getString("tag_name").substring(1))>0)winner=next;
            }catch(Exception ignored){/* Untrusted oversized numeric tag: skip it. */}
        }
        if(winner==null)throw new SecurityException("No verified Dev prerelease");
        return winner;
    }

    public static JSONObject latest()throws Exception{
        boolean dev="dev".equals(BuildConfig.RELEASE_CHANNEL);
        JSONObject release=dev
                ?selectDev(new JSONArray(fetchText(DEV_API,1048576)))
                :fetchJson(STABLE_API,262144);
        String tag=release.optString("tag_name","");
        if(!tag.startsWith("v")||!(dev?DEV:STABLE).matcher(tag.substring(1)).matches())
            throw new SecurityException("Untrusted release tag");
        String version=tag.substring(1);
        String manifestUrl=assetUrl(release,"update-manifest.json",version);
        return validate(release,fetchJson(manifestUrl,8192));
    }
}
