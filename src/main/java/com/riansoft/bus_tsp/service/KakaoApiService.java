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
        new File("cache").mkdirs(); // 캐시 디렉토리 생성
    }


    public long[][] createTimeMatrixFromApi(List<VirtualStop> stops) {
        System.out.println("\n========= [2/5] 카카오 API를 통한 시간 행렬 생성 및 캐싱 시작 (비대칭) ==========");
        Map<String, Long> cachedDurations = loadDurationsFromCache();
        boolean isCacheUpdated = false;

        int totalPairs = stops.size() * (stops.size() -1); // N * (N-1)
        int processedPairs = 0;

        for (int i = 0; i < stops.size(); i++) {
            for (int j = 0; j < stops.size(); j++) {
                if (i == j) {
                    // 같은 정류장 간 이동 시간은 0, API 호출 불필요
                    continue;
                }

                VirtualStop origin = stops.get(i);
                VirtualStop destination = stops.get(j);
                // 변경: 이제 이름을 정렬하지 않고 직접 키를 생성하여 A->B와 B->A를 구분합니다.
                String canonicalKey = createNonSymmetricKeyByName(origin.name, destination.name);

                if (!cachedDurations.containsKey(canonicalKey)) {
                    System.out.printf("    [API CALL] 캐시 없음: '%s' -> '%s' 경로의 이동 시간 API 호출...%n", origin.name, destination.name);
                    long duration = getDurationInMinutes(origin, destination);
                    cachedDurations.put(canonicalKey, duration);
                    isCacheUpdated = true;
                } else {
                }

                processedPairs++;
                if (totalPairs > 0 && processedPairs % (totalPairs / 10 + 1) == 0) {
                    System.out.printf("  [API LOG] 시간 행렬 생성 진행률: %.1f%% (%d / %d 쌍 처리 완료)%n",
                            ((double)processedPairs / totalPairs * 100), processedPairs, totalPairs);
                }
            }
        }

        if (isCacheUpdated) {
            saveDurationsToCache(cachedDurations); // 변경된 내용 파일에 저장
        }
        System.out.println("========= [2/5] 시간 행렬 생성 및 캐싱 완료 (비대칭) ==========\n");

        return buildMatrixFromCache(stops, cachedDurations);
    }

    /**
     * 두 지점 간의 상세 경로 좌표 목록을 반환합니다.
     * 이 메서드는 이전과 동일하게 동작하며, 별도의 파일 캐싱은 없습니다.
     */
    public List<LatLngDto> getDetailedPath(VirtualStop origin, VirtualStop destination) {
        // 같은 위치의 가상 정류장 간에는 경로가 없으므로 빈 리스트 반환
        if (origin.name.equals(destination.name)) return new ArrayList<>();

        // 상세 경로에 대한 파일 캐싱은 이전에 없었으며, 요청에 따라 그대로 유지됩니다.
        // 따라서 API 호출이 매번 발생할 수 있습니다.
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

    /**
     * 변경: 두 이름을 정렬하지 않고 그대로 붙여서 키를 생성합니다.
     * 이로써 'A_B'와 'B_A'는 서로 다른 키가 됩니다.
     */
    private String createNonSymmetricKeyByName(String name1, String name2) {
        // 같은 정류장 간의 키는 필요 없지만, 일관성을 위해 추가
        if (name1.equals(name2)) {
            return name1 + "_SELF"; // 자기 자신과의 경로는 0이므로 실제 API 호출되지 않음
        }
        return name1 + "_" + name2; // 정렬하지 않음
    }

    // 파일에서 캐시된 이동 시간을 로드하는 기존 전략 유지
    private Map<String, Long> loadDurationsFromCache() {
        if (CACHE_FILE.exists()) {
            try {
                System.out.println("[API LOG] 기존 캐시 파일(" + CACHE_FILE.getAbsolutePath() + ")을 불러옵니다.");
                return objectMapper.readValue(CACHE_FILE, new TypeReference<>() {});
            } catch (IOException e) {
                System.err.println("캐시 파일 로딩 오류: " + e.getMessage());
            }
        }
        System.out.println("[API LOG] 캐시 파일이 존재하지 않거나 로딩에 실패했습니다. 새로운 캐시를 생성합니다.");
        return new HashMap<>();
    }

    // 캐시된 이동 시간을 파일에 저장하는 기존 전략 유지
    private void saveDurationsToCache(Map<String, Long> durationsToSave) {
        try {
            System.out.println("[API LOG] 업데이트된 시간 맵을 캐시 파일에 저장합니다...");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(CACHE_FILE, durationsToSave);
            System.out.println("[API LOG] 캐시 파일 저장 완료.");
        } catch (IOException e) {
            System.err.println("캐시 파일 저장 오류: " + e.getMessage());
        }
    }

    /**
     * 변경: 캐시된 Map을 사용하여 최종 이차원 행렬을 구축합니다.
     * 이때, 각 (i, j) 쌍에 대해 비대칭 키를 사용하여 시간을 가져옵니다.
     */
    private long[][] buildMatrixFromCache(List<VirtualStop> stops, Map<String, Long> cachedDurations) {
        int numStops = stops.size();
        long[][] finalMatrix = new long[numStops][numStops];
        for (int i = 0; i < numStops; i++) {
            for (int j = 0; j < numStops; j++) {
                if (i == j) {
                    finalMatrix[i][j] = 0;
                    continue;
                }
                String key = createNonSymmetricKeyByName(stops.get(i).name, stops.get(j).name);
                finalMatrix[i][j] = cachedDurations.getOrDefault(key, 999L);
            }
        }
        return finalMatrix;
    }

    // 카카오 API를 통해 이동 시간을 조회하는 기존 메서드 유지
    public long getDurationInMinutes(VirtualStop origin, VirtualStop destination) {
        // 같은 정류장이라면 0분 반환 (API 호출 불필요)
        if (origin.name.equals(destination.name)) {
            return 0;
        }

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
                        // 초 단위를 분 단위로 올림 (최소 1분)
                        return (long) Math.max(1, Math.ceil((Integer) summary.get("duration") / 60.0));
                    }
                }
            }
        } catch (Exception e) {
            System.err.printf("카카오 API 호출 중 오류: %s -> %s | 원인: %s%n", origin.name, destination.name, e.getMessage());
            // 오류 발생 시 높은 값 반환하여 해당 경로를 피하도록 유도
            return 999L;
        }
        return 999L; // API 응답에서 duration을 찾지 못한 경우
    }
}