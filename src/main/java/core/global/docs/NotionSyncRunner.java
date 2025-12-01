package core.global.docs;

import core.global.enums.errorcode.*;
import core.global.exception.AppError;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class NotionSyncRunner {

    public static void main(String[] args) throws Exception {
        String token = System.getenv("NOTION_TOKEN");
        String dbId = System.getenv("NOTION_DB_ID");

        if (token == null || dbId == null) {
            log.info("Notion sync skipped: NOTION_TOKEN / NOTION_DB_ID not set.");
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
                        enumClass.getSimpleName(),
                        e.code(),
                        e.httpStatus().value(),
                        e.message()
                ))
                .toList();
    }
}
