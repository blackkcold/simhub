package com.blackkcold.simhub.p2p;

import java.net.*;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Bounded worker count, bounded frames and timeouts before any provider access. */
public final class PeerNodeServer implements AutoCloseable {
    private final PeerStore store;
    private final ServerSocket listener;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    public PeerNodeServer(PeerStore store)throws Exception{this.store=store;listener=new ServerSocket(0,8);}
    public int port(){return listener.getLocalPort();}
    public void run(){while(!closed){try{Socket s=listener.accept();s.setSoTimeout(15000);try{workers.execute(()->serve(s));}catch(RejectedExecutionException busy){s.close();}}catch(Exception e){if(!closed)close();}}}
    private void serve(Socket socket){byte[] root=null,session=null;
        try(socket){
            JSONObject hello=DirectPeerTransport.read(socket.getInputStream());String from=hello.getString("from"),to=store.myId();JSONObject peer=store.peer(from);
            if(!"NODE".equals(peer.optString("localRole"))||!"ACTIVE".equals(peer.optString("pairState"))||"RELAY_ONLY".equals(peer.optString("policy")))throw new SecurityException("peer_not_authorized");
            root=store.key(from);JSONObject h=PeerProtocol.open(root,from,to,hello,System.currentTimeMillis()/1000);
            if(!"hello".equals(h.optString("action")))throw new SecurityException("peer_hello_required");
            String helloId=hello.getString("id");KeyPair eph=PeerCrypto.ephemeral();byte[] shared=PeerCrypto.dh(eph.getPrivate(),PeerCrypto.publicKey(h.getString("ephemeral")));
            try{session=PeerCrypto.derive(shared,root,"session|"+from+"|"+to+"|"+helloId);}finally{Arrays.fill(shared,(byte)0);}
            DirectPeerTransport.write(socket.getOutputStream(),PeerProtocol.seal(root,to,from,helloId,hello.getLong("expires"),new JSONObject().put("action","hello_ack").put("ephemeral",PeerCrypto.b64(eph.getPublic().getEncoded()))));
            JSONObject frame=DirectPeerTransport.read(socket.getInputStream());JSONObject req=PeerProtocol.open(session,from,to,frame,System.currentTimeMillis()/1000);
            // Recheck revocation immediately before business dispatch.
            JSONObject result=new PeerRpc(store).dispatch(from,frame.getString("id"),req);
            DirectPeerTransport.write(socket.getOutputStream(),PeerProtocol.seal(session,to,from,frame.getString("id"),frame.getLong("expires"),result));
        }catch(Exception ignored){/* Never log request, SMS, OTP, keys or attacker input. */}
        finally{if(root!=null)Arrays.fill(root,(byte)0);if(session!=null)Arrays.fill(session,(byte)0);}
    }
    public void close(){closed=true;try{listener.close();}catch(Exception ignored){}workers.shutdownNow();}
}
