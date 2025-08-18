package core.global.dto;


import core.global.enums.DeviceType;
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
    private DeviceType platform;

}
