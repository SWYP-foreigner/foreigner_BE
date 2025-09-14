package core.global.dto;

import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public record ApplePublicKey(
        String kty, // Key Type (EC)
        String kid, // Key ID
        String use, // Usage (sig)
        String alg, // Algorithm (ES256)
        String crv, // Curve (P-256)
        String x,   // X Coordinate
        String y    // Y Coordinate
) {}