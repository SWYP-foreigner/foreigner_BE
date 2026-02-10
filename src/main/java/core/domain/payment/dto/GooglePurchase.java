package core.domain.payment.dto;

import com.google.api.client.json.GenericJson;
import com.google.api.services.androidpublisher.model.ProductPurchase;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseLineItem;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2;
import core.global.enums.payment.PurchaseStatus;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

public record GooglePurchase(
        String productId,
        String purchaseToken,
        PurchaseStatus status,
        Instant purchaseTime,
        Instant expiresTime,
        String raw
) {
    public static GooglePurchase fromSubscriptionV2(SubscriptionPurchaseV2 s, String purchaseToken) {
        PurchaseStatus status = switch (String.valueOf(s.getSubscriptionState())) {
            case "SUBSCRIPTION_STATE_ACTIVE" -> PurchaseStatus.ACTIVE;
            case "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" -> PurchaseStatus.IN_GRACE;
            case "SUBSCRIPTION_STATE_ON_HOLD" -> PurchaseStatus.ON_HOLD;
            case "SUBSCRIPTION_STATE_PAUSED" -> PurchaseStatus.PAUSED;
            case "SUBSCRIPTION_STATE_CANCELED", "SUBSCRIPTION_STATE_EXPIRED" -> PurchaseStatus.EXPIRED;
            default -> PurchaseStatus.EXPIRED;
        };
        SubscriptionPurchaseLineItem li = (s.getLineItems() != null && !s.getLineItems().isEmpty())
                ? s.getLineItems().get(0) : null;
        Instant expires = (li != null && li.getExpiryTime() != null)
                ? parseInstantFlexible(li.getExpiryTime()) : null;
        Instant start = parseInstantFlexible(s.getStartTime());

        return new GooglePurchase(
                (li != null) ? li.getProductId() : null,
                purchaseToken,
                status,
                start,
                expires,
                safePretty(s)
        );
    }

    public static GooglePurchase fromProduct(ProductPurchase p, String productId) {
        PurchaseStatus st = Boolean.TRUE.equals(p.getConsumptionState()) ? PurchaseStatus.ACTIVE : PurchaseStatus.PENDING;
        return new GooglePurchase(
                productId,
                p.getPurchaseToken(),
                st,
                p.getPurchaseTimeMillis() != null ? Instant.ofEpochMilli(p.getPurchaseTimeMillis()) : null,
                null,
                safePretty(p)
        );
    }

    private static Instant parseInstantFlexible(String s) {
        if (s == null) return null;
        if (s.chars().allMatch(Character::isDigit)) {
            return Instant.ofEpochMilli(Long.parseLong(s));
        }
        try { return Instant.parse(s); }
        catch (DateTimeParseException e) { return null; }
    }

    private static String safePretty(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof GenericJson gj) {
                return gj.toPrettyString();
            }
        } catch (IOException ignored) {
            // 무시하고 아래 fallback 사용
        }
        return o.toString();
    }
}