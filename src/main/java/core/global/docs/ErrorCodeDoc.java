package core.global.docs;

public record ErrorCodeDoc(
        String code,
        int httpStatus,
        String message
) {
}
