package com.riansoft.bus_tsp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riansoft.bus_tsp.model.VirtualStop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KakaoApiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper; // JSON 처리를 위한 객체

    @Value("${kakao.api.key}")
    private String KAKAO_API_KEY;

    // 캐시 파일을 저장할 경로를 명확하게 지정합니다.
    private static final String CACHE_DIR = "cache";
    private final File CACHE_FILE = new File(CACHE_DIR, "timeMatrix_name_cache.json");

    public KakaoApiService(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
        this.objectMapper = new ObjectMapper();

        // 애플리케이션 시작 시 cache 폴더가 없으면 자동으로 생성합니다.
        new File(CACHE_DIR).mkdirs();
    }

    /**
     * 정규화된 키를 사용한 캐싱 전략으로 시간 행렬을 생성합니다.
     * @param stops 가상 정류장 목록
     * @return 계산된 시간 행렬
     */
    public long[][] createTimeMatrixFromApi(List<VirtualStop> stops) {
        System.out.println("--- 시간 행렬 생성 시작 (이름 기반 캐싱 전략 적용) ---");
        Map<String, Long> cachedDurations = loadDurationsFromCache();
        boolean isCacheUpdated = false;

        for (int i = 0; i < stops.size(); i++) {
            // 진행률 로그
            if (i > 0 && (i % 10 == 0)) {
                System.out.printf("  ...진행률: %.1f%% (%d / %d 출발지 처리 완료)%n",
                        ((double)i / stops.size() * 100), i, stops.size());
            }

            for (int j = i + 1; j < stops.size(); j++) {
                VirtualStop origin = stops.get(i);
                VirtualStop destination = stops.get(j);

                // 두 정류장의 이름을 정렬하여 정규화된 키 생성
                String canonicalKey = createCanonicalKeyByName(origin.name, destination.name);

                // 캐시에 해당 키가 없는 경우에만 API를 호출합니다.
                if (!cachedDurations.containsKey(canonicalKey)) {
                    System.out.printf("    캐시 없음: '%s' <-> '%s' 경로의 API 호출...%n", origin.name, destination.name);

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
     * 두 정류장 이름을 사전순으로 정렬하여 일관된 키를 만듭니다.
     * @param name1 첫 번째 정류장 이름
     * @param name2 두 번째 정류장 이름
     * @return 정렬 및 조합된 키 문자열
     */
    private String createCanonicalKeyByName(String name1, String name2) {
        String[] names = {name1, name2};
        Arrays.sort(names);
        return names[0] + "_" + names[1];
    }

    /**
     * 파일에서 이동 시간 캐시를 불러옵니다.
     * @return 캐시된 이동 시간 맵
     */
    private Map<String, Long> loadDurationsFromCache() {
        if (CACHE_FILE.exists()) {
            try {
                System.out.println("기존 캐시 파일(" + CACHE_FILE.getAbsolutePath() + ")을 불러옵니다.");
                return objectMapper.readValue(CACHE_FILE, new TypeReference<>() {});
            } catch (IOException e) {
                System.err.println("캐시 파일 로딩 중 오류 발생: " + e.getMessage());
            }
        }
        System.out.println("캐시 파일이 존재하지 않습니다. 새로운 캐시를 생성합니다.");
        return new HashMap<>();
    }

    /**
     * 업데이트된 이동 시간 맵을 JSON 파일로 저장합니다.
     * @param durationsToSave 저장할 맵 데이터
     */
    private void saveDurationsToCache(Map<String, Long> durationsToSave) {
        try {
            System.out.println("업데이트된 시간 맵을 캐시 파일(" + CACHE_FILE.getAbsolutePath() + ")에 저장합니다...");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(CACHE_FILE, durationsToSave);
            System.out.println("캐시 파일 저장 완료.");
        } catch (IOException e) {
            System.err.println("캐시 파일 저장 중 오류 발생: " + e.getMessage());
        }
    }

    /**
     * 캐시 맵 데이터를 기반으로, 최종 2차원 배열을 만듭니다.
     * @param stops 가상 정류장 목록
     * @param cachedDurations 캐시된 이동 시간 맵
     * @return 최종 2차원 시간 행렬
     */
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
        System.out.println("--- 최종 시간 행렬 구성 완료 ---");
        return finalMatrix;
    }

    /**
     * 카카오 길찾기 API를 호출하여 두 지점 간의 소요 시간을 반환합니다.
     */
    private long getDurationInMinutes(VirtualStop origin, VirtualStop destination) {
        String url = String.format(
                "https://apis-navi.kakaomobility.com/v1/directions?origin=%s,%s&destination=%s,%s",
                origin.lon, origin.lat, destination.lon, destination.lat
        );
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
            System.err.printf("카카오 API 호출 중 오류 발생: %s -> %s | 원인: %s%n", origin.name, destination.name, e.getMessage());
            return 999;
        }
        return 999; // 경로를 찾지 못한 경우
    }



}