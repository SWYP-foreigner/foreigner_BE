package core.domain.payment.controller;

import core.domain.payment.dto.UserItemDto;
import core.domain.payment.service.UserItemService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class UserItemController {


    private final UserItemService userItemService;

    @Operation(summary = "아이템 보유량 조회", description = "예: itemCode=boost/frame")
    @GetMapping
    public UserItemDto getUserItemQuantity(@RequestParam String itemCode) {
        return userItemService.getUserItemQuantity(itemCode);
    }

    @Operation(summary = "아이템 1회 소비", description = "부스팅 사용 등")
    @PostMapping("/consume")
    public void consumeOne(@RequestParam String itemCode) {
        userItemService.consumeOne(itemCode);
    }

}
