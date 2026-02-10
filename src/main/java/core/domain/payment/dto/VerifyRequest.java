package core.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyRequest(
        @NotBlank String platform, // "ios" | "android"
        String transactionId,                                     // iOS 필수
        String purchaseToken,                                     // Android 필수
        String productId                                         // Android 권장
) {
}