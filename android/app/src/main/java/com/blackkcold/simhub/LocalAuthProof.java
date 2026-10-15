package com.blackkcold.simhub;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.function.Consumer;

/** A fresh, auth-per-operation Android Keystore signature gates local controller grants. */
public final class LocalAuthProof {
    private static final String ALIAS = "simhub-local-auth-proof-v1";
    private LocalAuthProof() {}

    private static KeyPair key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true);
            if (Build.VERSION.SDK_INT >= 30) {
                spec.setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
            } else {
                spec.setUserAuthenticationValidityDurationSeconds(-1);
            }
            generator.initialize(spec.build());
            generator.generateKeyPair();
        }
        return new KeyPair(store.getCertificate(ALIAS).getPublicKey(),
                (java.security.PrivateKey) store.getKey(ALIAS, null));
    }

    public static void authenticate(Activity activity, String title, Runnable success,
                                    Consumer<String> failure) {
        final KeyPair pair;
        final Signature signer;
        final byte[] challenge = new byte[32];
        try {
            pair = key();
            signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(pair.getPrivate());
            new SecureRandom().nextBytes(challenge);
        } catch (Exception e) {
            failure.accept("需要设置安全锁屏及受支持的强生物识别，才能使用本机解锁。");
            return;
        }
        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity).setTitle(title);
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG |
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                // API 29 cannot combine credentials with an auth-per-use CryptoObject.
                builder.setNegativeButton("取消", activity.getMainExecutor(), (dialog, which) -> {});
            }
            builder.build().authenticate(new BiometricPrompt.CryptoObject(signer),
                new CancellationSignal(), activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        try {
                            BiometricPrompt.CryptoObject verified = result.getCryptoObject();
                            if (verified == null || verified.getSignature() == null)
                                throw new SecurityException("missing_authenticated_signature");
                            Signature authenticated = verified.getSignature();
                            authenticated.update(challenge);
                            byte[] signed = authenticated.sign();
                            try {
                                Signature verify = Signature.getInstance("SHA256withECDSA");
                                verify.initVerify(pair.getPublic());
                                verify.update(challenge);
                                if (!verify.verify(signed))
                                    throw new SecurityException("local_auth_proof_invalid");
                            } finally { Arrays.fill(signed, (byte) 0); }
                            success.run();
                        } catch (Exception e) { failure.accept("身份验证没有完成加密证明，请重新验证。"); }
                        finally { Arrays.fill(challenge, (byte) 0); }
                    }
                    @Override public void onAuthenticationError(int code, CharSequence message) {
                        Arrays.fill(challenge, (byte) 0);
                        failure.accept(message.toString());
                    }
                });
        } catch (Exception e) {
            Arrays.fill(challenge, (byte) 0);
            failure.accept("本机身份验证不可用，请检查锁屏与生物识别设置。");
        }
    }
}