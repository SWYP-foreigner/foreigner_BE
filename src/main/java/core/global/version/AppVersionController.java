package core.global.version;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "App Version", description = "앱 버전 관리 및 강제 업데이트 체크 API")
@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
public class AppVersionController {

    private final AppVersionService appVersionService;

    @Operation(
            summary = "앱 버전 체크 (스플래시 화면용)",
            description = """
            앱 실행 시 현재 버전을 서버에 전송하여 업데이트 필요 여부를 확인합니다.
            
            **로직 설명:**
            1. **FORCE_UPDATE**: 현재 버전 < 최소 지원 버전 (앱 사용 차단)
            2. **RECOMMEND_UPDATE**: 최소 버전 <= 현재 버전 < 최신 버전 (업데이트 권장 팝업)
            3. **PASS**: 현재 버전 == 최신 버전 (정상 진입)
            """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "요청 성공",
                    content = @Content(schema = @Schema(implementation = VersionCheckDto.Response.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (지원하지 않는 플랫폼 파라미터)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "INVALID_PLATFORM",
                                    summary = "잘못된 플랫폼 값 입력 시",
                                    value = """
                                    {
                                        "code": "INVALID_PLATFORM",
                                        "message": "지원하지 않는 플랫폼입니다. (허용: ANDROID, IOS)",
                                        "status": 400
                                    }
                                    """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "버전 정보 없음 (DB 데이터 누락)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "VERSION_INFO_NOT_FOUND",
                                    summary = "해당 플랫폼 데이터가 없을 시",
                                    value = """
                                    {
                                        "code": "VERSION_INFO_NOT_FOUND",
                                        "message": "해당 플랫폼의 버전 정보를 찾을 수 없습니다.",
                                        "status": 404
                                    }
                                    """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 에러",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "INTERNAL_SERVER_ERROR",
                                    value = """
                                    {
                                        "code": "INTERNAL_SERVER_ERROR",
                                        "message": "서버 내부 오류가 발생했습니다.",
                                        "status": 500
                                    }
                                    """
                            )
                    )
            )
    })
    @GetMapping("/version")
    public ResponseEntity<VersionCheckDto.Response> checkVersion(
            @Parameter(description = "앱 버전 체크 요청 파라미터")
            @ModelAttribute VersionCheckDto.Request request
    ) {
        VersionCheckDto.Response response = appVersionService.checkVersion(request);
        return ResponseEntity.ok(response);
    }
}