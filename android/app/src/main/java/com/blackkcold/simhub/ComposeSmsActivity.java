package com.blackkcold.simhub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Android default-SMS SENDTO entry point. Never auto-send from external intents. */
public final class ComposeSmsActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        Intent source=getIntent();
        Intent target=new Intent(this,HubActivity.class).setAction(Intent.ACTION_SENDTO);
        if(source!=null){
            target.setData(source.getData());
            String body=source.getStringExtra(Intent.EXTRA_TEXT);
            if(body!=null)target.putExtra(Intent.EXTRA_TEXT,body);
        }
        startActivity(target);
        finish();
    }
}
