package core.domain.payment.dto;

public record UserItemDto(
        Long userId, String itemCode, int quantity
) {}
