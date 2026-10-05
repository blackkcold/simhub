package com.blackkcold.simhub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class ComposeSmsActivity extends Activity {
    private final ArrayList<Integer> subIds=new ArrayList<>();private Spinner spinner;private EditText to,body;
    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_compose);spinner=findViewById(R.id.subscription);to=findViewById(R.id.to);body=findViewById(R.id.body);if(getIntent().getData()!=null)to.setText(getIntent().getData().getSchemeSpecificPart());String initial=getIntent().getStringExtra(Intent.EXTRA_TEXT);if(initial!=null)body.setText(initial);loadSubs();findViewById(R.id.send).setOnClickListener(v->send());}
    private void loadSubs(){ArrayList<String> labels=new ArrayList<>();try{List<SubscriptionInfo> list=getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();if(list!=null)for(SubscriptionInfo s:list){subIds.add(s.getSubscriptionId());labels.add(String.valueOf(s.getDisplayName())+" · "+s.getCarrierName());}}catch(Exception ignored){}spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));}
    private void send(){if(subIds.isEmpty()){Toast.makeText(this,"No active SIM",Toast.LENGTH_LONG).show();return;}int sub=subIds.get(spinner.getSelectedItemPosition());String d=to.getText().toString(),t=body.getText().toString();Executors.newSingleThreadExecutor().execute(()->{try{SmsSender.send(this,"local-"+UUID.randomUUID(),sub,d,t);runOnUiThread(()->{Toast.makeText(this,"SMS submitted",Toast.LENGTH_SHORT).show();finish();});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show());}});}
}
