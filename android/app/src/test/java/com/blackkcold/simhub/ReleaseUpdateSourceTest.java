package com.blackkcold.simhub;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseUpdateSourceTest {
    private static final String VERSION="0.12.2";
    private static final String ASSET="simhub-agent-v"+VERSION+"-release.apk";
    private static final String BASE="https://github.com/blackkcold/simhub/releases/download/v"+VERSION+"/";
    private static final String HASH="c".repeat(64);

    private JSONObject release()throws Exception{
        return new JSONObject().put("tag_name","v"+VERSION).put("draft",false)
            .put("prerelease",false).put("published_at","2026-10-10T03:00:00Z")
            .put("assets",new JSONArray()
                .put(new JSONObject().put("name","update-manifest.json")
                    .put("browser_download_url",BASE+"update-manifest.json"))
                .put(new JSONObject().put("name",ASSET)
                    .put("browser_download_url",BASE+ASSET)));
    }
    private JSONObject manifest()throws Exception{
        return new JSONObject().put("schemaVersion",1).put("channel","stable").put("release",VERSION)
            .put("notes","Android update without pairing")
            .put("android",new JSONObject().put("versionName",VERSION)
                .put("versionCode",28).put("packageName","com.blackkcold.simhub")
                .put("asset",ASSET).put("sha256",HASH));
    }

    @Test public void validReleaseNeedsNoEnrollmentOrRelay()throws Exception{
        JSONObject m=ReleaseUpdateSource.validate(release(),manifest());
        assertEquals(28,m.getInt("versionCode"));
        assertEquals(BASE+ASSET,m.getString("url"));
        assertEquals(HASH,m.getString("sha256"));
        assertTrue(m.getBoolean("available"));
    }
    @Test public void rejectsPrerelease()throws Exception{
        JSONObject r=release().put("prerelease",true);
        try{ReleaseUpdateSource.validate(r,manifest());fail("prerelease accepted");}
        catch(SecurityException expected){}
    }
    @Test public void rejectsManifestVersionMismatch()throws Exception{
        JSONObject m=manifest().put("release","0.12.1");
        try{ReleaseUpdateSource.validate(release(),m);fail("mismatch accepted");}
        catch(SecurityException expected){}
    }
    @Test public void rejectsUntrustedDownloadHost()throws Exception{
        JSONObject r=release();
        r.getJSONArray("assets").getJSONObject(1)
            .put("browser_download_url","https://example.invalid/"+ASSET);
        try{ReleaseUpdateSource.validate(r,manifest());fail("external asset accepted");}
        catch(SecurityException expected){}
    }
    @Test public void rejectsUnexpectedPackageOrHash()throws Exception{
        JSONObject m=manifest();m.getJSONObject("android").put("packageName","attacker.package");
        try{ReleaseUpdateSource.validate(release(),m);fail("package accepted");}
        catch(SecurityException expected){}
        m=manifest();m.getJSONObject("android").put("sha256","bad");
        try{ReleaseUpdateSource.validate(release(),m);fail("hash accepted");}
        catch(SecurityException expected){}
    }
}
