package core.domain.notification.controller;

import core.domain.notification.dto.*;
import core.domain.notification.service.UserNotificationService; // 서비스 임포트 경로 수정
import core.global.config.CustomUserDetails;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


@Tag(name = "사용자 알림 및 설정", description = "사용자 기기 토큰 및 알림 설정 관련 API")
@RestController
@RequestMapping("/api/v1/user/notification")
@RequiredArgsConstructor
public class UserNotificationController {

    private final UserNotificationService userNotificationService;

    @Operation(summary = "FCM 기기 토큰 등록/갱신", description = "클라이언트의 FCM 기기 토큰을 서버에 등록하거나 최신으로 업데이트합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @PostMapping("/device-token")
    public ResponseEntity<ApiResponse<Void>> registerDeviceToken(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody DeviceTokenRequest request
    ) {
        userNotificationService.registerDeviceToken(userDetails.getUserId(), request.deviceToken());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "알림 설정 상태 확인 (마이그레이션용)", description = "사용자가 새로운 알림 설정 절차를 완료했는지 확인합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = NotificationSettingStatusResponse.class)))
    })
    @GetMapping("/settings-status")
    public ResponseEntity<ApiResponse<NotificationSettingStatusResponse>> getNotificationSettingStatus(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        NotificationSettingStatusResponse response = userNotificationService.getNotificationSettingStatus(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "알림 설정 초기화", description = "신규/기존 사용자의 앱 내부 알림 동의 선택 결과를 서버에 저장합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @PostMapping("/settings")
    public ResponseEntity<ApiResponse<Void>> initializeNotificationSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody NotificationSettingInitRequest request
    ) {
        userNotificationService.initializeNotificationSettings(userDetails.getUserId(), request.agreed());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "OS 푸시 권한 상태 동기화", description = "사용자의 실제 OS 푸시 권한 상태를 서버와 동기화합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @PutMapping("/push-agreement")
    public ResponseEntity<ApiResponse<Void>> syncPushAgreement(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody PushAgreementRequest request
    ) {
        userNotificationService.syncPushAgreement(userDetails.getUserId(), request.osPermissionGranted());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "사용자 알림 카테고리별 설정 조회", description = "앱 내 '알림 설정' 화면에 필요한 카테고리별 알림 설정 목록을 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = NotificationSettingListResponse.class)))
    })
    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<NotificationSettingListResponse>> getNotificationSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        NotificationSettingListResponse response = userNotificationService.getNotificationSettings(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}