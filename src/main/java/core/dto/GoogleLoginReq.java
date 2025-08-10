package core.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoogleLoginReq {
    private String code;
    private String codeVerifier;
    private String platform; // "android" 또는 "ios"

    //구글 로그인
}
