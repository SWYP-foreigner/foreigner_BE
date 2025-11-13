package core.global.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public class NotionClient {

    private static final String NOTION_API_BASE = "https://api.notion.com/v1";
    // 최신 버전 (2025-11 기준)
    private static final String NOTION_VERSION = "2025-09-03";

    private final String token;
    private final String databaseId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NotionClient(String token, String databaseId) {
        this.token = token;
        this.databaseId = databaseId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 지금 예시는 단순히 "새 페이지만 계속 추가"하는 버전입니다.
     * (upsert 필요하면 나중에 query + update 로 확장 가능)
     */
    public void syncErrorCodes(List<ErrorCodeDoc> docs) throws Exception {
        for (ErrorCodeDoc doc : docs) {
            createPage(doc);
        }
    }

    private void createPage(ErrorCodeDoc doc) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();

        // parent: 단일 데이터 소스 DB라면 database_id 그대로 사용해도 동작합니다.
        ObjectNode parent = objectMapper.createObjectNode();
        parent.put("database_id", databaseId);
        root.set("parent", parent);

        ObjectNode properties = objectMapper.createObjectNode();

        // Name (Title) = code
        ObjectNode nameProp = objectMapper.createObjectNode();
        ArrayNode titleArray = objectMapper.createArrayNode();
        ObjectNode titleText = objectMapper.createObjectNode();
        ObjectNode textObj = objectMapper.createObjectNode();
        textObj.put("content", doc.code());
        titleText.set("text", textObj);
        titleArray.add(titleText);
        nameProp.set("title", titleArray);
        properties.set("code", nameProp);

        // httpStatus (Number)
        ObjectNode statusProp = objectMapper.createObjectNode();
        statusProp.put("number", doc.httpStatus());
        properties.set("httpStatus", statusProp);

        // message (Rich text)
        ObjectNode msgProp = objectMapper.createObjectNode();
        ArrayNode richTextArray = objectMapper.createArrayNode();
        ObjectNode msgText = objectMapper.createObjectNode();
        ObjectNode msgTextContent = objectMapper.createObjectNode();
        msgTextContent.put("content", doc.message());
        msgText.set("text", msgTextContent);
        richTextArray.add(msgText);
        msgProp.set("rich_text", richTextArray);
        properties.set("message", msgProp);

        root.set("properties", properties);

        String body = objectMapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NOTION_API_BASE + "/pages"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", NOTION_VERSION)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            System.err.println("Notion createPage 실패: " + statusCode);
            System.err.println(response.body());
        }
    }
}
