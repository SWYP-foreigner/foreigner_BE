package core.global.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public class NotionClient {

    private static final Logger log = LoggerFactory.getLogger(NotionClient.class);

    private static final String NOTION_API_BASE = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";

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

    public void syncErrorCodes(List<ErrorCodeDoc> docs) throws Exception {
        for (ErrorCodeDoc doc : docs) {
            String pageId = findPageIdByCode(doc.code());
            if (pageId == null) {
                createPage(doc);
            } else {
                updatePage(pageId, doc);
            }
        }
    }

    private String findPageIdByCode(String code) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("property", "code");

        ObjectNode title = objectMapper.createObjectNode();
        title.put("equals", code);
        filter.set("title", title);

        root.set("filter", filter);

        String body = objectMapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NOTION_API_BASE + "/databases/" + databaseId + "/query"))
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
            log.error("Notion query 실패. statusCode={}, body={}", statusCode, response.body());

            // 🔴 400이면 바로 멈추도록 예외 발생
            if (statusCode == 400) {
                throw new IllegalStateException(
                        "Notion query 400 (invalid_request_url). NOTION_DB_ID / URL 설정을 확인하세요."
                );
            }

            return null;
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode results = json.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return null;
        }

        return results.get(0).get("id").asText();
    }

    private void createPage(ErrorCodeDoc doc) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode parent = objectMapper.createObjectNode();
        parent.put("database_id", databaseId);
        root.set("parent", parent);

        ObjectNode properties = objectMapper.createObjectNode();

        // ✅ type (Select)
        ObjectNode typeProp = objectMapper.createObjectNode();
        ObjectNode typeSelect = objectMapper.createObjectNode();
        typeSelect.put("name", doc.type()); // 예: "AuthErrorCode"
        typeProp.set("select", typeSelect);
        properties.set("type", typeProp);


        // code (Title)
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
            log.error("Notion createPage 실패. statusCode={}, body={}", statusCode, response.body());

            if (statusCode == 400) {
                throw new IllegalStateException(
                        "Notion createPage 400 (invalid_request_url). DB 스키마/속성 이름을 확인하세요."
                );
            }
        }
    }

    private void updatePage(String pageId, ErrorCodeDoc doc) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode properties = objectMapper.createObjectNode();

        // ✅ type (Select)
        ObjectNode typeProp = objectMapper.createObjectNode();
        ObjectNode typeSelect = objectMapper.createObjectNode();
        typeSelect.put("name", doc.type());
        typeProp.set("select", typeSelect);
        properties.set("type", typeProp);

        // code (Title)
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
                .uri(URI.create(NOTION_API_BASE + "/pages/" + pageId))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", NOTION_VERSION)
                .header("Content-Type", "application/json; charset=utf-8")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            log.error("Notion updatePage 실패. statusCode={}, body={}", statusCode, response.body());

            if (statusCode == 400) {
                throw new IllegalStateException(
                        "Notion updatePage 400 (invalid_request_url). DB 스키마/속성 이름을 확인하세요."
                );
            }
        }
    }
}
