package com.empmg.platform.hr;

import com.empmg.platform.common.EncryptionKeyHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

@Component
@RequiredArgsConstructor
public class HrCrypto {
    private final EncryptionKeyHolder keys;
    private static final SecureRandom RANDOM = new SecureRandom();
    public byte[] encrypt(byte[] data) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(), new GCMParameterSpec(128,iv));
            byte[] encrypted = cipher.doFinal(data);
            return ByteBuffer.allocate(12+encrypted.length).put(iv).put(encrypted).array();
        } catch(Exception e) { throw new IllegalStateException("HR encryption failed",e); }
    }
    public byte[] decrypt(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,keys.get(),new GCMParameterSpec(128,data,0,12));
            return cipher.doFinal(data,12,data.length-12);
        } catch(Exception e) { throw new IllegalStateException("HR decryption failed",e); }
    }
}
