package core.global.version;

import core.global.docs.annotations.VersionErrorCodeDocs;
import core.global.enums.errorcode.VersionErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
    })
    @GetMapping("/version")
    @VersionErrorCodeDocs({VersionErrorCode.VERSION_INFO_NOT_FOUND, VersionErrorCode.INVALID_PLATFORM})
    public ResponseEntity<VersionCheckDto.Response> checkVersion(
            @Parameter(description = "앱 버전 체크 요청 파라미터")
            @ModelAttribute VersionCheckDto.Request request
    ) {
        VersionCheckDto.Response response = appVersionService.checkVersion(request);
        return ResponseEntity.ok(response);
    }
}