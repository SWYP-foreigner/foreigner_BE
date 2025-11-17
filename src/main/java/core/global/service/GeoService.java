package core.global.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GeoService {

    @Value("${google.maps.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public String getCountryByLatLng(double lat, double lng) {

        String url = String.format(
                "https://maps.googleapis.com/maps/api/geocode/json?latlng=%f,%f&key=%s",
                lat, lng, apiKey
        );

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            return null;
        }

        Map body = response.getBody();
        if (body == null || !"OK".equals(body.get("status"))) {
            return null;
        }

        List<Map> results = (List<Map>) body.get("results");
        if (results == null || results.isEmpty()) {
            return null;
        }

        // 첫 번째 결과에서 address_components 파싱
        List<Map> components = (List<Map>) results.get(0).get("address_components");

        for (Map comp : components) {
            List<String> types = (List<String>) comp.get("types");
            if (types.contains("country")) {
                return (String) comp.get("long_name");
            }
        }

        return null;
    }
}
