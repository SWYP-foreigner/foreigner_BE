package core.domain.payment.service;

import core.domain.payment.dto.UserItemDto;
import core.domain.payment.entity.UserItem;
import core.domain.payment.repository.UserItemRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.errorcode.PaymentErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserItemService {
    private final UserItemRepository userItemRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserItemDto getUserItemQuantity(String itemCode) {
        User user = currentUser();

        UserItem entity = userItemRepository
                .findByUserIdAndItemCode(user.getId(), itemCode)
                .orElseGet(() -> new UserItem(user.getId(), itemCode, 0));

        return new UserItemDto(user.getId(), itemCode, entity.getQuantity());
    }

    @Transactional
    public void consumeOne(String itemCode) {
        User user = currentUser();
        int updated = userItemRepository.decrementIfEnough(user.getId(), itemCode, 1);
        if (updated == 0) throw new BusinessException(PaymentErrorCode.ITEM_OUT_OF_STOCK);
    }

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }
}
