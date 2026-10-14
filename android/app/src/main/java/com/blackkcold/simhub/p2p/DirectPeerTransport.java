package com.blackkcold.simhub.p2p;

import org.json.*;
import java.net.*;
import java.io.*;
import java.security.KeyPair;
import java.util.*;

/** One authenticated ephemeral ECDH session per RPC; application AEAD over raw TCP. */
public final class DirectPeerTransport implements HubTransport {
    private final PeerStore store;
    private String actualRoute="LAN";
    public DirectPeerTransport(PeerStore store){this.store=store;}
    public String route(){return actualRoute;}
    public JSONObject request(JSONObject peer,String id,JSONObject req)throws Exception{
        JSONArray endpoints=peer.optJSONArray("endpoints");if(endpoints==null)throw new IOException("no_direct_endpoint");
        Exception last=new IOException("peer_offline_no_direct_endpoint");
        for(int i=0;i<endpoints.length();i++){
            JSONObject ep=endpoints.getJSONObject(i);
            try{return call(peer,ep.getString("host"),ep.getInt("port"),id,req);}catch(Exception e){last=e;if(e instanceof SecurityException)throw e;}
        }
        throw last;
    }
    private JSONObject call(JSONObject peer,String host,int port,String id,JSONObject req)throws Exception{
        if(host.isBlank()||host.length()>200||port<1024||port>65535)throw new SecurityException("invalid_peer_endpoint");
        // Numeric endpoint only: never resolves an untrusted QR to internal DNS names.
        if(!host.matches("[0-9a-fA-F:.%a-zA-Z_-]+")||(!host.contains(":")&&!host.matches("[0-9.]+")))throw new SecurityException("numeric_peer_address_required");
        String from=store.myId(),to=peer.getString("id"),helloId=UUID.randomUUID().toString();byte[] root=store.key(to),session=null;KeyPair eph=PeerCrypto.ephemeral();
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(host,port),3500);socket.setSoTimeout(15000);
            long exp=System.currentTimeMillis()/1000+120;
            write(socket.getOutputStream(),PeerProtocol.seal(root,from,to,helloId,exp,new JSONObject().put("action","hello").put("ephemeral",PeerCrypto.b64(eph.getPublic().getEncoded()))));
            JSONObject answer=read(socket.getInputStream());if(!helloId.equals(answer.optString("id")))throw new SecurityException("peer_hello_id_mismatch");
            JSONObject opened=PeerProtocol.open(root,to,from,answer,System.currentTimeMillis()/1000);
            if(!"hello_ack".equals(opened.optString("action")))throw new SecurityException("peer_hello_rejected");
            byte[] shared=PeerCrypto.dh(eph.getPrivate(),PeerCrypto.publicKey(opened.getString("ephemeral")));
            try{session=PeerCrypto.derive(shared,root,"session|"+from+"|"+to+"|"+helloId);}finally{Arrays.fill(shared,(byte)0);}
            write(socket.getOutputStream(),PeerProtocol.seal(session,from,to,id,exp,req));
            JSONObject response=read(socket.getInputStream());if(!id.equals(response.optString("id")))throw new SecurityException("peer_response_id_mismatch");
            JSONObject result=PeerProtocol.open(session,to,from,response,System.currentTimeMillis()/1000);
            InetAddress remote=socket.getInetAddress();String numeric=remote.getHostAddress();
            actualRoute=remote.isSiteLocalAddress()||remote.isLinkLocalAddress()||remote.isLoopbackAddress()||numeric.toLowerCase(java.util.Locale.ROOT).matches("f[cd].*")?"LAN":"DIRECT";
            return result;
        }finally{Arrays.fill(root,(byte)0);if(session!=null)Arrays.fill(session,(byte)0);}
    }
    public static JSONObject read(InputStream stream)throws Exception{DataInputStream in=new DataInputStream(stream);int n=in.readInt();if(n<2||n>PeerProtocol.MAX_FRAME)throw new SecurityException("peer_frame_size_rejected");byte[] b=new byte[n];in.readFully(b);return new JSONObject(new String(b,java.nio.charset.StandardCharsets.UTF_8));}
    public static void write(OutputStream stream,JSONObject frame)throws Exception{byte[] b=PeerCrypto.utf8(frame.toString());if(b.length>PeerProtocol.MAX_FRAME)throw new SecurityException("peer_frame_too_large");DataOutputStream out=new DataOutputStream(stream);out.writeInt(b.length);out.write(b);out.flush();}
}
