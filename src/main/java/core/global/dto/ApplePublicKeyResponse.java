package core.global.dto;

import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;

import java.util.List;

/**
 *  헤더 부분에 alg 와 kid (key id)로 맞는 공개키 가져오기
 */

public record ApplePublicKeyResponse(
        List<ApplePublicKey> keys
) {}