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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
public class GoogleClient {

    private final String packageName;
    @Getter
    private final AndroidPublisher publisher;

    // ★ 수정 1: @Value("${...:null}") -> 설정 파일에 값이 없으면 null 문자열이 들어감 (앱 셧다운 방지)
    public GoogleClient(@Value("${iap.android.packageName:null}") String packageName,
                        @Value("${iap.android.serviceAccountJsonBase64:null}") String serviceAccountJsonBase64) {
        this.packageName = packageName;

        AndroidPublisher tempPublisher = null;

        try {
            // null 체크 또는 "null" 문자열 체크
            if (serviceAccountJsonBase64 == null || "null".equals(serviceAccountJsonBase64) || serviceAccountJsonBase64.isBlank()) {
                log.warn("Google Service Account Key 설정이 비어있습니다. 결제 검증 기능을 사용할 수 없습니다.");
            } else {
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

                log.info("GoogleClient 초기화 성공");
            }
        } catch (Exception e) {
            // ★ 수정 2: 생성자 에러를 삼킴 (앱 실행 유지)
            log.error("GoogleClient 초기화 실패 (서버는 계속 실행됨): {}", e.getMessage());
        }

        this.publisher = tempPublisher;
    }

    public GooglePurchase verify(String productId, String purchaseToken) {
        if (this.publisher == null) {
            log.error("GoogleClient가 초기화되지 않았습니다. (설정 확인 필요)");
            return null; // ★ 수정 3: throw 대신 null 반환
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
            // ★ 수정 4: 실행 중 에러도 로그만 남기고 null 반환
            log.error("Google 결제 검증 API 호출 중 오류: {}", e.getMessage());
            return null;
        }
    }
}