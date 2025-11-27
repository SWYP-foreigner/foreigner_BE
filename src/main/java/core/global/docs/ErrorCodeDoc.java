package core.global.docs;

public record ErrorCodeDoc(
        String type,
        String code,
        int httpStatus,
        String message
) {
}
