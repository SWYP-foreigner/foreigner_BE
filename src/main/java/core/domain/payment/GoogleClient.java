package core.domain.payment;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.ProductPurchase;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import core.domain.payment.dto.GooglePurchase;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

@Component
public class GoogleClient {

    private final String packageName;
    @Getter
    private final AndroidPublisher publisher;

    public GoogleClient(@Value("${iap.android.packageName}") String packageName,
                        @Value("${iap.android.serviceAccountJsonBase64}") String serviceAccountJsonBase64) throws Exception {
        this.packageName = packageName;
        byte[] json = Base64.getDecoder().decode(serviceAccountJsonBase64);
        GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(json))
                .createScoped(List.of("https://www.googleapis.com/auth/androidpublisher"));
        this.publisher = new AndroidPublisher.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), new HttpCredentialsAdapter(credentials))
                .setApplicationName("Kori-Service")
                .build();
    }

    public GooglePurchase verify(String productId, String purchaseToken) {
        try {
            if (productId != null && productId.startsWith("sub_")) {
                SubscriptionPurchaseV2 s = publisher.purchases().subscriptionsv2().get(packageName, purchaseToken).execute();
                return GooglePurchase.fromSubscriptionV2(s, purchaseToken);
            } else {
                ProductPurchase p = publisher.purchases().products().get(packageName, productId, purchaseToken).execute();
                return GooglePurchase.fromProduct(p, productId);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}