package core.global.docs;

import core.global.enums.errorcode.AuthErrorCode;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.AppError;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NotionSyncRunner {

    public static void main(String[] args) throws Exception {
        String token = System.getenv("NOTION_TOKEN");
        String dbId  = System.getenv("NOTION_DB_ID");

        // 빌드 시 자동 실행할 거라, 설정 없으면 그냥 스킵하도록 처리하는 게 안전합니다.
        if (token == null || dbId == null) {
            System.out.println("Notion sync skipped: NOTION_TOKEN / NOTION_DB_ID not set.");
            return;
        }

        NotionClient client = new NotionClient(token, dbId);

        List<ErrorCodeDoc> docs = new ArrayList<>();
        docs.addAll(fromEnum(AuthErrorCode.class));
        docs.addAll(fromEnum(ChatErrorCode.class));
        docs.addAll(fromEnum(CommonErrorCode.class));
        docs.addAll(fromEnum(CommunityErrorCode.class));
        docs.addAll(fromEnum(ImageErrorCode.class));
        docs.addAll(fromEnum(UserErrorCode.class));

        client.syncErrorCodes(docs);
    }

    private static <E extends Enum<E> & AppError> List<ErrorCodeDoc> fromEnum(Class<E> enumClass) {
        return Arrays.stream(enumClass.getEnumConstants())
                .map(e -> new ErrorCodeDoc(
                        e.code(),
                        e.httpStatus().value(),
                        e.message()
                ))
                .toList();
    }
}
