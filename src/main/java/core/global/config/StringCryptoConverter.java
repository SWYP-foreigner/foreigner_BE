package core.global.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Component
@Converter
public class StringCryptoConverter implements AttributeConverter<String, String> {

    private static String KEY;
    private static final String ALGORITHM = "AES";

    @Value("${encryption.secret}")
    public void setKey(String key) {
        KEY = key;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            SecretKeySpec keySpec = new SecretKeySpec(KEY.getBytes(), ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return Base64.getEncoder().encodeToString(cipher.doFinal(attribute.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("암호화 실패", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            SecretKeySpec keySpec = new SecretKeySpec(KEY.getBytes(), ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return new String(cipher.doFinal(Base64.getDecoder().decode(dbData)));
        } catch (Exception e) {
            throw new RuntimeException("복호화 실패", e);
        }
    }
}
