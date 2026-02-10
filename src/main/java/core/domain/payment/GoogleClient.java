package core.domain.payment;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.ProductPurchase;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import core.domain.payment.dto.GooglePurchase;
import core.global.enums.errorcode.PaymentErrorCode;
import core.global.exception.BusinessException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j; // 로깅을 위해 추가
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

@Slf4j // 로그 사용
@Component
public class GoogleClient {

    private final String packageName;
    @Getter
    private final AndroidPublisher publisher;

    public GoogleClient(@Value("${iap.android.packageName}") String packageName,
                        @Value("${iap.android.serviceAccountJsonBase64}") String serviceAccountJsonBase64) {
        this.packageName = packageName;

        AndroidPublisher tempPublisher = null; // 임시 변수 사용

        try {
            // credentials 값이 비어있거나 잘못된 경우를 대비해 체크 (선택사항)
            if (serviceAccountJsonBase64 == null || serviceAccountJsonBase64.isBlank()) {
                throw new IllegalArgumentException("Google Service Account Key is missing");
            }

            byte[] json = Base64.getDecoder().decode(serviceAccountJsonBase64);
            GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(json))
                    .createScoped(List.of("https://www.googleapis.com/auth/androidpublisher"));

            tempPublisher = new AndroidPublisher.Builder(
                    new NetHttpTransport(),
                    JacksonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
            )
                    .setApplicationName("Kori-Service")
                    .build();

            log.info("GoogleClient initialized successfully.");

        } catch (Exception e) {
            // ★ 핵심 변경: 여기서 throw를 하지 않고 에러 로그만 남김
            log.error("GoogleClient 초기화 실패 (앱 실행은 계속됨): {}", e.getMessage());
            // 초기화 실패 시 publisher는 null 상태가 됨
        }

        this.publisher = tempPublisher;
    }

    public GooglePurchase verify(String productId, String purchaseToken) {
        // ★ 사용 시점에 체크: 초기화가 실패했다면 이때 예외 발생
        if (this.publisher == null) {
            log.error("GoogleClient가 정상적으로 생성되지 않았습니다. 설정을 확인하세요.");
            throw new BusinessException(PaymentErrorCode.GOOGLE_WEBHOOK_FAILED);
        }

        try {
            if (productId != null && productId.startsWith("sub_")) {
                SubscriptionPurchaseV2 s = publisher.purchases().subscriptionsv2().get(packageName, purchaseToken).execute();
                return GooglePurchase.fromSubscriptionV2(s, purchaseToken);
            } else {
                ProductPurchase p = publisher.purchases().products().get(packageName, productId, purchaseToken).execute();
                return GooglePurchase.fromProduct(p, productId);
            }
        } catch (Exception e) {
            throw new BusinessException(PaymentErrorCode.GOOGLE_VERIFY_FAILED, e);
        }
    }
}