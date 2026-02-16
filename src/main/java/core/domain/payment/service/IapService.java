package core.domain.payment.service;

import core.domain.payment.AppleClient;
import core.domain.payment.GoogleClient;
import core.domain.payment.dto.AppleTransactionInfo;
import core.domain.payment.dto.EntitlementResponse;
import core.domain.payment.dto.GooglePurchase;
import core.domain.payment.dto.VerifyRequest;
import core.domain.payment.entity.*;
import core.domain.payment.repository.*;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.DeviceType;
import core.global.enums.errorcode.UserErrorCode;
import core.global.enums.payment.EntitlementStatus;
import core.global.enums.payment.PaymentProductType;
import core.global.enums.payment.PurchaseStatus;
import core.global.exception.BusinessException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IapService {

    private final GoogleClient googleClient;
    private final AppleClient appleClient;
    private final IapPurchaseRepository purchaseRepository;
    private final IapEntitlementRepository entitlementRepository;
    private final IapProductRepository productRepository;
    private final UserItemRepository userItemRepository;
    private final IapBonusGrantRepository bonusGrantRepository;
    private final UserRepository userRepository;

    @Transactional
    public EntitlementResponse verify(VerifyRequest req) {
        // 외부 API 호출 (비트랜잭션 영역)
        if ("ios".equalsIgnoreCase(req.platform())) {
            if (req.transactionId() == null || req.transactionId().isBlank()) {
                throw new IllegalArgumentException("iOS: transactionId is required");
            }

            AppleTransactionInfo tx = appleClient.getTransaction(req.transactionId());
            return verifyIosTx(req, tx);
        } else if ("android".equalsIgnoreCase(req.platform())) {
            if (req.purchaseToken() == null || req.purchaseToken().isBlank()) {
                throw new IllegalArgumentException("Android: purchaseToken is required");
            }

            GooglePurchase gp = googleClient.verify(req.productId(), req.purchaseToken());
            return verifyAndroidTx(req, gp);
        }
        throw new IllegalArgumentException("Unsupported platform: " + req.platform());
    }

    public EntitlementResponse getEntitlements() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        IapEntitlement e = entitlementRepository.findTopByUserIdOrderByUpdatedAtDesc(user.getId())
                .orElseGet(() -> new IapEntitlement(user.getId(), false, EntitlementStatus.EXPIRED));
        return EntitlementResponse.fromEntity(e);
    }

    public EntitlementResponse verifyIosTx(VerifyRequest req, AppleTransactionInfo tx) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // product 매핑: 애플 productId 기준
        IapProduct product = productRepository
                .findByPlatformAndStoreProductId(DeviceType.IOS, tx.productId())
                .orElse(null);

        // 멱등 업서트(ios, transactionId)
        IapPurchase purchase = purchaseRepository
                .findByPlatformAndStoreTxId(DeviceType.IOS, tx.transactionId())
                .orElseGet(() -> new IapPurchase(user.getId(), DeviceType.IOS, product, tx));

        purchaseRepository.save(purchase);
        applyPostPurchaseSideEffects(purchase);

        IapEntitlement ent = IapEntitlement.fromPurchase(purchase);
        entitlementRepository.save(ent);

        return new EntitlementResponse(user.getId(), ent.isActive(), ent.getFeature(), ent.getTier(),
                ent.getExpiresAt(), ent.getSource().name(), ent.getStatus().name());
    }


    public EntitlementResponse verifyAndroidTx(VerifyRequest req, GooglePurchase gp) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // product 매핑: 안드로이드 productId(구독은 lineItem.productId, 일회성은 요청 productId)
        String storeProductId = (gp.productId() != null) ? gp.productId() : req.productId();

        IapProduct product = productRepository
                .findByPlatformAndStoreProductId(DeviceType.ANDROID, storeProductId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_FOLLOW_STATUS)); //TODO

        // 멱등 업서트(android, purchaseToken)
        IapPurchase purchase = purchaseRepository
                .findByPlatformAndStoreTxId(DeviceType.ANDROID, req.purchaseToken())
                .orElseGet(() -> new IapPurchase(user.getId(), DeviceType.ANDROID, product, gp));

        purchaseRepository.save(purchase);
        applyPostPurchaseSideEffects(purchase);

        IapEntitlement ent = IapEntitlement.fromPurchase(purchase);
        entitlementRepository.save(ent);

        return new EntitlementResponse(user.getId(), ent.isActive(), ent.getFeature(), ent.getTier(),
                ent.getExpiresAt(), ent.getSource().name(), ent.getStatus().name());
    }


    private void applyPostPurchaseSideEffects(IapPurchase purchase) {
        applyConsumableCreditIfBoostPurchase(purchase);
        grantWelcomeFrameIfFirstPremiumActivation(purchase);
    }

    private void applyConsumableCreditIfBoostPurchase(IapPurchase purchase) {
        boolean isBoost = purchase.getProduct() != null
                          && purchase.getProduct().getType() == PaymentProductType.INAPP
                          && "boost_profile".equals(purchase.getProduct().getStoreProductId());

        if (isBoost && purchase.getStatus() == PurchaseStatus.ACTIVE) {
            incrementUserItemQuantity(purchase.getUserId(), "boost", 1);
        }
    }

    private void grantWelcomeFrameIfFirstPremiumActivation(IapPurchase purchase) {
        boolean isPremiumSubscription = purchase.getProduct() != null
                                        && purchase.getProduct().getType() == PaymentProductType.SUBS
                                        && "premium".equalsIgnoreCase(purchase.getProduct().getTier());

        if (!isPremiumSubscription) return;
        if (purchase.getStatus() != PurchaseStatus.ACTIVE) return;

        boolean alreadyGranted = bonusGrantRepository
                .findByUserIdAndBonusCode(purchase.getUserId(), "welcome_frame")
                .isPresent();

        if (alreadyGranted) return;

        incrementUserItemQuantity(purchase.getUserId(), "frame", 1);

        IapBonusGrant grant = new IapBonusGrant(purchase, "welcome_frame");
        bonusGrantRepository.save(grant);
    }

    private void incrementUserItemQuantity(Long userId, String itemCode, int delta) {
        UserItem item = userItemRepository
                .findByUserIdAndItemCode(userId, itemCode)
                .orElseGet(() -> new UserItem(userId, itemCode, 0));
        item.updateQuantity(Math.max(0, item.getQuantity() + delta));
        userItemRepository.save(item);
    }

}
