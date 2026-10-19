import com.blackkcold.simhub.p2p.*;
import java.security.*;
import java.util.*;
import javax.crypto.AEADBadTagException;

/** Executes the exact production crypto implementation, no reimplemented mock cipher. */
public final class PeerCryptoSandbox {
    static int assertions=0;
    static void check(boolean yes){if(!yes)throw new AssertionError();assertions++;}
    static void reject(Throwing r)throws Exception{boolean rejected=false;try{r.run();}catch(GeneralSecurityException|SecurityException expected){rejected=true;}check(rejected);}
    interface Throwing{void run()throws Exception;}
    public static void main(String[] args)throws Exception{
        KeyPair node=PeerCrypto.ephemeral(),controller=PeerCrypto.ephemeral();
        String a=PeerCrypto.b64(node.getPublic().getEncoded()),b=PeerCrypto.b64(controller.getPublic().getEncoded());
        String transcript=PeerCrypto.pairTranscript(UUID.randomUUID().toString(),2000000000,a,a,b,b,"read,history,state");
        String sig=PeerCrypto.sign(node.getPrivate(),transcript);PeerCrypto.verify(a,transcript,sig);assertions++;
        reject(()->PeerCrypto.verify(b,transcript,sig));reject(()->PeerCrypto.verify(a,transcript+",send",sig));
        byte[] rootA=PeerCrypto.derive(PeerCrypto.dh(node.getPrivate(),controller.getPublic()),PeerCrypto.utf8("test-session"),"content-root");
        byte[] rootB=PeerCrypto.derive(PeerCrypto.dh(controller.getPrivate(),node.getPublic()),PeerCrypto.utf8("test-session"),"content-root");check(Arrays.equals(rootA,rootB));
        byte[] nonce=PeerCrypto.random(12),sms=PeerCrypto.utf8("SMS sandbox OTP 123456"),ct=PeerCrypto.crypt(true,rootA,nonce,"A|B|command-1|2000000000",sms);
        check(Arrays.equals(sms,PeerCrypto.crypt(false,rootB,nonce,"A|B|command-1|2000000000",ct)));
        reject(()->PeerCrypto.crypt(false,rootB,nonce,"B|A|command-1|2000000000",ct));
        reject(()->PeerCrypto.crypt(false,rootB,nonce,"A|B|command-2|2000000000",ct));
        byte[] corrupted=ct.clone();corrupted[0]^=1;reject(()->PeerCrypto.crypt(false,rootB,nonce,"A|B|command-1|2000000000",corrupted));
        check(!Arrays.equals(rootA,PeerCrypto.derive(PeerCrypto.dh(node.getPrivate(),controller.getPublic()),PeerCrypto.utf8("other-pair"),"content-root")));
        check(!Arrays.equals(rootA,PeerCrypto.derive(PeerCrypto.dh(node.getPrivate(),controller.getPublic()),PeerCrypto.utf8("test-session"),"legacy-vault")));
        for(ConnectionPolicy p:ConnectionPolicy.values()){
            check(!p.permits("TURN",false,true));check(!p.permits("MAILBOX",true,false));
        }
        check(!ConnectionPolicy.P2P_ONLY.permits("TURN",true,true));check(ConnectionPolicy.P2P_ONLY.permits("DIRECT",false,false));
        check(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("MAILBOX",true,true));
        check(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("LEGACY_RELAY",true,true));
        check(!ConnectionPolicy.P2P_RELAY_FALLBACK.permits("TURN",true,true));
        check(ConnectionPolicy.P2P_RELAY_FALLBACK.permits("TURN",false,false,true));
        check(!ConnectionPolicy.P2P_ONLY.permits("TURN",true,true,true));
        System.out.println("{\"assertions\":"+assertions+",\"crypto\":\"passed\",\"policy\":\"passed\"}");
    }
}
