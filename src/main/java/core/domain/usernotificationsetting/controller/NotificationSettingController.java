package core.domain.usernotificationsetting.controller;

import core.domain.usernotificationsetting.dto.NotificationSettingInitRequestDto;
import core.domain.usernotificationsetting.dto.NotificationSettingResponseDto;
import core.domain.usernotificationsetting.dto.NotificationSettingUpdateRequestDto;
import core.domain.usernotificationsetting.service.UserNotificationSettingService;
import core.global.config.CustomUserDetails;
import core.global.dto.ApiResponse;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "알림 설정", description = "유저 알림 설정 조회, 수정, 초기화 API")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationSettingController {

    private final UserNotificationSettingService notificationSettingService;

    @Operation(
            summary = "사용자 알림 설정 조회",
            description = "현재 로그인한 사용자의 알림 설정 목록을 조회합니다.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "조회 성공",
                            content = @Content(
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "NotificationSettingsExample",
                                            value = """
                                                    {
                                                      "message": "success",
                                                      "data": [
                                                        { "notificationType": "post", "enabled": true },
                                                        { "notificationType": "comment", "enabled": true },
                                                        { "notificationType": "chat", "enabled": false }
                                                      ],
                                                      "timestamp": "2025-10-03T12:00:00"
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "401",
                            description = "인증되지 않은 사용자",
                            content = @Content(
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "UnauthorizedExample",
                                            value = """
                                                    {
                                                      "message": "로그인이 필요합니다.",
                                                      "data": null,
                                                      "timestamp": "2025-10-03T12:00:00"
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = "알림 설정을 찾을 수 없음",
                            content = @Content(
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "NotFoundExample",
                                            value = """
                                                    {
                                                      "message": "알림 설정을 찾을 수 없습니다.",
                                                      "data": null,
                                                      "timestamp": "2025-10-03T12:00:00"
                                                    }
                                                    """
                                    )
                            )
                    )
            }
    )
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationSettingResponseDto>>> getNotificationSettings() {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(notificationSettingService.getUserNotificationSettings(userId)));
    }

    @Operation(
            summary = "사용자 알림 설정 수정",
            description = "현재 로그인한 사용자의 특정 알림 설정을 변경합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "변경할 알림 설정 데이터",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = NotificationSettingUpdateRequestDto.class),
                            examples = @ExampleObject(
                                    name = "NotificationSettingUpdateExample",
                                    value = """
                                            {
                                              "notificationType": "chat",
                                              "enabled": true
                                            }
                                            """
                            )
                    )
            ),
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "변경 성공",
                            content = @Content(
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "NotificationSettingUpdateResponseExample",
                                            value = """
                                                    {
                                                      "message": "success",
                                                      "data": {
                                                        "notificationType": "chat",
                                                        "enabled": true
                                                      },
                                                      "timestamp": "2025-10-03T12:00:00"
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "401",
                            description = "인증되지 않은 사용자",
                            content = @Content(
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "UnauthorizedExample",
                                            value = """
                                                    {
                                                      "message": "로그인이 필요합니다.",
                                                      "data": null,
                                                      "timestamp": "2025-10-03T12:00:00"
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = "알림 설정을 찾을 수 없음",
                            content = @Content(
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "NotFoundExample",
                                            value = """
                                                    {
                                                      "message": "알림 설정을 찾을 수 없습니다.",
                                                      "data": null,
                                                      "timestamp": "2025-10-03T12:00:00"
                                                    }
                                                    """
                                    )
                            )
                    )
            }
    )
    @PutMapping
    public ResponseEntity<ApiResponse<NotificationSettingResponseDto>> updateNotificationSetting(
            @RequestBody NotificationSettingUpdateRequestDto request) {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(notificationSettingService.updateUserNotificationSetting(userId, request)));
    }

    @Operation(
            summary = "사용자 알림 설정 초기화",
            description = "유저가 알림 설정을 초기값으로 설정합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "초기 알림 설정 데이터",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = NotificationSettingInitRequestDto.class),
                            examples = @ExampleObject(
                                    name = "NotificationInitExample",
                                    value = """
                                            {
                                              "settings": [
                                                { "notificationType": "post", "enabled": true },
                                                { "notificationType": "comment", "enabled": true },
                                                { "notificationType": "chat", "enabled": false },
                                                { "notificationType": "follow", "enabled": true },
                                                { "notificationType": "receive", "enabled": false }
                                              ]
                                            }
                                            """
                            )
                    )
            ),
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "초기화 성공",
                            content = @Content(
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "NotificationInitResponseExample",
                                            value = """
                                                    {
                                                      "message": "success",
                                                      "data": null,
                                                      "timestamp": "2025-10-03T12:00:00"
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "401",
                            description = "인증되지 않은 사용자",
                            content = @Content(
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "UnauthorizedExample",
                                            value = """
                                                    {
                                                      "message": "로그인이 필요합니다.",
                                                      "data": null,
                                                      "timestamp": "2025-10-03T12:00:00"
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = "사용자 없음",
                            content = @Content(
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "UserNotFoundExample",
                                            value = """
                                                    {
                                                      "message": "존재하지 않는 유저입니다.",
                                                      "data": null,
                                                      "timestamp": "2025-10-03T12:00:00"
                                                    }
                                                    """
                                    )
                            )
                    )
            }
    )
    @PostMapping("/init")
    public ResponseEntity<ApiResponse<Void>> initializeSettings(
            @RequestBody NotificationSettingInitRequestDto request) {

        Long userId = getCurrentUserId();
        notificationSettingService.initializeNotificationSettings(userId, request);

        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private Long getCurrentUserId() {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal == null || principal.getUserId() == null) {
            throw new BusinessException(ErrorCode.USER_UNAUTHORIZED);
        }
        return principal.getUserId();
    }
}
