package core.global.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // 1. Slf4j 임포트
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j // 2. 롬복 로그 어노테이션 추가
@Service
@RequiredArgsConstructor
public class GeoService {

    @Value("${google.maps.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public String getCountryByLatLng(double lat, double lng) {
        log.info("[GeoService] 좌표로 국가 조회 요청 - lat: {}, lng: {}", lat, lng);

        String url = String.format(
                "https://maps.googleapis.com/maps/api/geocode/json?latlng=%f,%f&key=%s",
                lat, lng, apiKey
        );

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            log.info("[GeoService] Google Maps API Response Status: {}", response.getStatusCode());

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("[GeoService] API 호출 실패. Status: {}", response.getStatusCode());
                return null;
            }

            Map body = response.getBody();
            if (body == null || !"OK".equals(body.get("status"))) {
                log.warn("[GeoService] 유효하지 않은 응답 Body 또는 Status. Body: {}", body);
                return null;
            }

            List<Map> results = (List<Map>) body.get("results");
            if (results == null || results.isEmpty()) {
                log.warn("[GeoService] 검색 결과가 비어있습니다 (ZERO_RESULTS).");
                return null;
            }

            List<Map> components = (List<Map>) results.get(0).get("address_components");

            for (Map comp : components) {
                List<String> types = (List<String>) comp.get("types");
                if (types.contains("country")) {
                    String countryName = (String) comp.get("long_name");
                    log.info("[GeoService] 국가 찾기 성공: {}", countryName);
                    return countryName;
                }
            }

            log.info("[GeoService] 결과는 있었으나 'country' 타입을 찾지 못함.");
            return null;

        } catch (Exception e) {
            // [로그] 예외 발생 시 스택트레이스 출력
            log.error("[GeoService] 좌표 변환 중 예외 발생", e);
            return null;
        }
    }
}