package com.blackkcold.simhub;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

public final class ContactResolver {
    public static String lookup(Context c, String number){
        if(number==null||number.isEmpty()||c.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED) return null;
        Uri uri=Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,Uri.encode(number));
        try(Cursor cur=c.getContentResolver().query(uri,new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},null,null,null)){
            if(cur!=null&&cur.moveToFirst()) return cur.getString(0);
        }catch(Exception ignored){}
        return null;
    }
}
