package com.riansoft.bus_tsp.service;

import com.riansoft.bus_tsp.dto.ModifiedRouteDto;
import com.riansoft.bus_tsp.dto.StopDto;
import com.riansoft.bus_tsp.dto.ValidatedRouteDto;
import com.riansoft.bus_tsp.model.VirtualStop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class RouteValidationService {

    private final StopDataService stopDataService;
    private final KakaoApiService kakaoApiService;

    // 스프링이 자동으로 두 서비스를 주입해주는 생성자
    @Autowired
    public RouteValidationService(StopDataService stopDataService, KakaoApiService kakaoApiService) {
        this.stopDataService = stopDataService;
        // [수정] kakaoApiService를 올바르게 할당합니다.
        this.kakaoApiService = kakaoApiService;
    }

    public ValidatedRouteDto validateAndCalculate(ModifiedRouteDto modifiedRoute, int capacity, String dbName) {
        List<StopDto> newRouteStops = modifiedRoute.getNewRoute();
        ValidatedRouteDto result = new ValidatedRouteDto();
        result.setBusId(modifiedRoute.getBusId());
        result.setNewRoute(newRouteStops);

        // --- 1. 총 탑승 인원 계산 및 정원 유효성 검사 ---
        long finalLoad = 0;
        long cumulativeLoad = 0;
        boolean capacityOk = true;
        for (StopDto stop : newRouteStops) {
            // 차고지는 수요 계산에서 제외
            if (stop.getId().startsWith("DEPOT")) continue;

            cumulativeLoad += stop.getDemand();
            finalLoad += stop.getDemand();
            if (cumulativeLoad > capacity) {
                capacityOk = false;
            }
        }
        result.setCalculatedFinalLoad(finalLoad);
        result.setCapacityValid(capacityOk);

        // --- 2. 총 서비스 시간 계산 ---
        // (주의: 이 로직은 API를 매번 호출하므로, 실제 운영 환경에서는 캐싱된 timeMatrix를 활용하는 것이 효율적입니다)
        long totalTime = 0;
        // 전체 정류장 목록은 한 번만 불러옵니다.
        List<VirtualStop> allStops = stopDataService.getVirtualStops(capacity,dbName);
        VirtualStop depot = allStops.get(0); // 차고지는 항상 0번

        if (!newRouteStops.isEmpty()) {
            // 첫 경유지가 차고지인지 확인하고 아니라면 차고지->첫경유지 시간 계산
            StopDto firstStopDto = newRouteStops.get(0);

            // 경유지 -> 경유지 시간 계산
            for (int i = 0; i < newRouteStops.size() - 1; i++) {
                VirtualStop origin = findVirtualStop(allStops, newRouteStops.get(i));
                VirtualStop destination = findVirtualStop(allStops, newRouteStops.get(i + 1));
                if (origin != null && destination != null) {
                    totalTime += kakaoApiService.getDurationInMinutes(origin, destination);
                }
            }

            // 마지막 경유지가 차고지인지 확인하고 아니라면 마지막 경유지->차고지 시간 계산
            StopDto lastStopDto = newRouteStops.get(newRouteStops.size() - 1);
            if (!lastStopDto.getId().startsWith("DEPOT")) {
                VirtualStop lastStop = findVirtualStop(allStops, lastStopDto);
                if (lastStop != null) {
                    totalTime += kakaoApiService.getDurationInMinutes(lastStop, depot);
                }
            }
        }
        result.setCalculatedRouteTime(totalTime);

        result.setValidationMessage(capacityOk ? "성공" : "실패: 최대 탑승 정원(" + capacity + "명)을 초과합니다.");

        return result;
    }

    // StopDto에 해당하는 VirtualStop을 전체 목록에서 찾는 헬퍼 메서드
    private VirtualStop findVirtualStop(List<VirtualStop> allStops, StopDto targetStop) {
        // ID와 이름이 모두 일치하는 경우를 찾음 (이름이 분할되어 '-1', '-2' 등이 붙을 수 있으므로 startsWith 사용)
        return allStops.stream()
                .filter(vs -> vs.originalId.equals(targetStop.getId()) && targetStop.getName().startsWith(vs.name.split("-")[0]))
                .findFirst()
                .orElse(null);
    }
}