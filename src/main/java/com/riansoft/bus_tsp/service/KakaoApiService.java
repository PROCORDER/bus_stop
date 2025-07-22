package com.riansoft.bus_tsp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riansoft.bus_tsp.dto.LatLngDto;
import com.riansoft.bus_tsp.model.VirtualStop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KakaoApiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    @Value("${kakao.api.key}")
    private String KAKAO_API_KEY;
    private final File CACHE_FILE = new File("cache/timeMatrix_name_cache.json");

    public KakaoApiService(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
        this.objectMapper = new ObjectMapper();
        new File("cache").mkdirs();
    }

    /**
     * 정규화된 키를 사용한 캐싱 전략으로 시간 행렬을 생성합니다.
     */
    public long[][] createTimeMatrixFromApi(List<VirtualStop> stops) {
        System.out.println("[LOG] 시간 행렬 생성을 시작합니다 (이름 기반 캐싱 전략 적용)...");
        Map<String, Long> cachedDurations = loadDurationsFromCache();
        boolean isCacheUpdated = false;

        for (int i = 0; i < stops.size(); i++) {
            // 진행률 로그 추가
            if (i > 0 && i % 10 == 0) {
                System.out.printf("  [API LOG] 시간 행렬 생성 진행률: %.1f%% (%d / %d 출발지 처리 완료)%n",
                        ((double)i / stops.size() * 100), i, stops.size());
            }
            for (int j = i + 1; j < stops.size(); j++) {
                VirtualStop origin = stops.get(i);
                VirtualStop destination = stops.get(j);
                String canonicalKey = createCanonicalKeyByName(origin.name, destination.name);
                if (!cachedDurations.containsKey(canonicalKey)) {
                    System.out.printf("    [API LOG] 캐시 없음: '%s' <-> '%s' 경로의 이동 시간 API 호출...%n", origin.name, destination.name);
                    long duration = getDurationInMinutes(origin, destination);
                    cachedDurations.put(canonicalKey, duration);
                    isCacheUpdated = true;
                }
            }
        }

        if (isCacheUpdated) {
            saveDurationsToCache(cachedDurations);
        }
        return buildMatrixFromCache(stops, cachedDurations);
    }

    /**
     * 두 지점 간의 상세 경로 좌표 목록을 반환합니다.
     */
    public List<LatLngDto> getDetailedPath(VirtualStop origin, VirtualStop destination) {
        // 같은 위치의 가상 정류장 간에는 경로가 없으므로 빈 리스트 반환
        if(origin.name.equals(destination.name)) return new ArrayList<>();

        System.out.printf("    [API LOG] 상세 경로 조회 API 호출: '%s' -> '%s'... ", origin.name, destination.name);

        String url = String.format(
                "https://apis-navi.kakaomobility.com/v1/directions?origin=%s,%s&destination=%s,%s",
                origin.lon, origin.lat, destination.lon, destination.lat
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "KakaoAK " + KAKAO_API_KEY);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        List<LatLngDto> path = new ArrayList<>();

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("routes")) {
                List<Map<String, Object>> routes = (List<Map<String, Object>>) body.get("routes");
                if (!routes.isEmpty() && routes.get(0).containsKey("sections")) {
                    List<Map<String, Object>> sections = (List<Map<String, Object>>) routes.get(0).get("sections");
                    for (Map<String, Object> section : sections) {
                        if (section.containsKey("roads")) {
                            List<Map<String, Object>> roads = (List<Map<String, Object>>) section.get("roads");
                            for (Map<String, Object> road : roads) {
                                if (road.containsKey("vertexes")) {
                                    List<Double> vertexes = (List<Double>) road.get("vertexes");
                                    for (int i = 0; i < vertexes.size(); i += 2) {
                                        path.add(new LatLngDto(vertexes.get(i + 1), vertexes.get(i)));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            System.out.printf("결과: 성공 (좌표 %d개)%n", path.size());
        } catch (Exception e) {
            System.out.printf("결과: 실패%n");
            System.err.printf("상세 경로 조회 중 오류 발생: %s -> %s | 원인: %s%n", origin.name, destination.name, e.getMessage());
        }
        return path;
    }

    private String createCanonicalKeyByName(String name1, String name2) {
        if (name1.equals(name2)) return name1;
        String[] names = {name1, name2};
        Arrays.sort(names);
        return names[0] + "_" + names[1];
    }
    private Map<String, Long> loadDurationsFromCache() {
        if (CACHE_FILE.exists()) {
            try {
                System.out.println("[API LOG] 기존 캐시 파일(" + CACHE_FILE.getAbsolutePath() + ")을 불러옵니다.");
                return objectMapper.readValue(CACHE_FILE, new TypeReference<>() {});
            } catch (IOException e) { System.err.println("캐시 파일 로딩 오류: " + e.getMessage()); }
        }
        System.out.println("[API LOG] 캐시 파일이 존재하지 않습니다. 새로운 캐시를 생성합니다.");
        return new HashMap<>();
    }
    private void saveDurationsToCache(Map<String, Long> durationsToSave) {
        try {
            System.out.println("[API LOG] 업데이트된 시간 맵을 캐시 파일에 저장합니다...");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(CACHE_FILE, durationsToSave);
            System.out.println("[API LOG] 캐시 파일 저장 완료.");
        } catch (IOException e) { System.err.println("캐시 파일 저장 오류: " + e.getMessage()); }
    }
    private long[][] buildMatrixFromCache(List<VirtualStop> stops, Map<String, Long> cachedDurations) {
        int numStops = stops.size();
        long[][] finalMatrix = new long[numStops][numStops];
        for (int i = 0; i < numStops; i++) {
            for (int j = 0; j < numStops; j++) {
                if (i == j) continue;
                String key = createCanonicalKeyByName(stops.get(i).name, stops.get(j).name);
                finalMatrix[i][j] = cachedDurations.getOrDefault(key, 999L);
            }
        }
        return finalMatrix;
    }
    public long getDurationInMinutes(VirtualStop origin, VirtualStop destination) {
        String url = String.format("https://apis-navi.kakaomobility.com/v1/directions?origin=%s,%s&destination=%s,%s",
                origin.lon, origin.lat, destination.lon, destination.lat);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "KakaoAK " + KAKAO_API_KEY);
        HttpEntity<String> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("routes")) {
                List<Map<String, Object>> routes = (List<Map<String, Object>>) body.get("routes");
                if (!routes.isEmpty() && routes.get(0) != null && routes.get(0).containsKey("summary")) {
                    Map<String, Object> summary = (Map<String, Object>) routes.get(0).get("summary");
                    if (summary != null && summary.containsKey("duration")) {
                        return (long) Math.ceil((Integer) summary.get("duration") / 60.0);
                    }
                }
            }
        } catch (Exception e) {
            System.err.printf("카카오 API 호출 중 오류: %s -> %s | 원인: %s%n", origin.name, destination.name, e.getMessage());
            return 999;
        }
        return 999;
    }
}