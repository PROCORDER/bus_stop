package com.riansoft.bus_tsp.service;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.*;
import com.google.protobuf.Duration;
import com.riansoft.bus_tsp.dto.*;
import com.riansoft.bus_tsp.model.DataModel;
import com.riansoft.bus_tsp.model.VirtualStop;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RouteOptimizationService {

    private static final long PENALTY_FOR_EXCEEDING_TIME = 100000;
    private static final long SEARCH_TIME_LIMIT_SECONDS = 100;

    private final StopDataService stopDataService;
    private final KakaoApiService kakaoApiService;
    private final SolutionFormatterService solutionFormatterService;
    private final RouteValidationService validationService;

    @Autowired
    public RouteOptimizationService(StopDataService stopDataService, KakaoApiService kakaoApiService,
                                    SolutionFormatterService solutionFormatterService, RouteValidationService validationService) {
        this.stopDataService = stopDataService;
        this.kakaoApiService = kakaoApiService;
        this.solutionFormatterService = solutionFormatterService;
        this.validationService = validationService;
    }

    @PostConstruct
    public void init() {
        try {
            System.out.println("[LOG] Google OR-Tools 네이티브 라이브러리 로드를 시도합니다...");
            Loader.loadNativeLibraries();
            System.out.println("[LOG] 라이브러리 로드 성공!");
        } catch (Exception e) {
            System.err.println("!!! [FATAL] Google OR-Tools 라이브러리 로드 실패 !!!");
            e.printStackTrace();
        }
    }

    public RouteSolutionDto findOptimalRoutes(long timeLimit, int capacity, long serviceTime, String dbName,int numvehicles,long arrivalTime) {
        System.out.println("\n========= [1/5] 최초 최적 경로 계산 시작 ==========");
        List<VirtualStop> virtualStops = stopDataService.getVirtualStops(capacity, dbName);
        DataModel data = createDataModel(virtualStops, capacity,numvehicles);
        return runSolver(data, timeLimit, serviceTime, arrivalTime);
    }

    /**
     * 고정된 제약조건으로 경로를 재계산합니다.
     */
    public RouteSolutionDto reOptimizeWithConstraints(RouteModificationRequestDto request, long timeLimit, int capacity, long serviceTime, String dbName,int numvehicles,long arrivalTime) {
        System.out.println("\n========= [RE-OPTIMIZE] 재계산 시작 ==========");
        // 1. 데이터 준비 및 분리
        List<ModifiedRouteDto> lockedRoutesRequest = request.getModifications();
        List<VirtualStop> allVirtualStops = stopDataService.getVirtualStops(capacity, dbName);

        Set<String> lockedStopIds = new HashSet<>();
        for (ModifiedRouteDto lockedRoute : lockedRoutesRequest) {
            lockedRoute.getNewRoute().forEach(stop -> lockedStopIds.add(stop.getId()));
        }

        List<VirtualStop> remainingStops = allVirtualStops.stream()
                .filter(stop -> stop.originalId.equals("DEPOT_0") || !lockedStopIds.contains(stop.originalId))
                .collect(Collectors.toList());
        System.out.println("--- 디버깅 로그 ---");
        System.out.println("전체 정류장 수: " + allVirtualStops.size());
        System.out.println("고정된 경유지 ID 목록: " + lockedStopIds);
        System.out.println("재계산 대상 정류장 수: " + remainingStops.size());
        if (!remainingStops.isEmpty()) {
            System.out.println("재계산 목록의 첫 번째 정류장(차고지여야 함): " + remainingStops.get(0).name);
        }
        System.out.println("--------------------");
        // 2. 고정 경로 처리 -> BusRouteDto 리스트로 변환
        List<BusRouteDto> finalLockedBusRoutes = new ArrayList<>();
        for (ModifiedRouteDto lockedRoute : lockedRoutesRequest) {
            ValidatedRouteDto validated = validationService.validateAndCalculate(lockedRoute,capacity,dbName);
            List<StopDto> routeStops = lockedRoute.getNewRoute();

            List<List<LatLngDto>> detailedPath = new ArrayList<>();
            if (!routeStops.isEmpty()) {
                //  차고지->첫경유지 상세경로 API 호출 로직을 삭제 ---
                // VirtualStop depot = allVirtualStops.get(0);
                // detailedPath.add(kakaoApiService.getDetailedPath(depot, findVirtualStop(allVirtualStops, routeStops.get(0))));

                // '경유지 -> 경유지' 상세 경로만 추가
                for (int i = 0; i < routeStops.size() - 1; i++) {
                    detailedPath.add(kakaoApiService.getDetailedPath(findVirtualStop(allVirtualStops, routeStops.get(i)), findVirtualStop(allVirtualStops, routeStops.get(i + 1))));
                }
            }

            finalLockedBusRoutes.add(new BusRouteDto(lockedRoute.getBusId(), routeStops,
                    validated.getCalculatedRouteTime(), validated.getCalculatedFinalLoad(), "", detailedPath));
        }

        // 3. 나머지 경로 재최적화
        List<BusRouteDto> newlyOptimizedBusRoutes = new ArrayList<>();
        if (remainingStops.size() > 1) { // 차고지 외에 남은 정류장이 있을 경우
            System.out.println("[RE-OPTIMIZE] 남은 정류장 " + (remainingStops.size() -1) + "개에 대해 재최적화 실행...");
            DataModel remainingData = createDataModel(remainingStops, capacity,numvehicles);
            RouteSolutionDto newlyOptimizedSolution = runSolver(remainingData, timeLimit, serviceTime, arrivalTime);
            if (newlyOptimizedSolution != null) {
                newlyOptimizedBusRoutes = newlyOptimizedSolution.getBusRoutes();
            }
        }

        // 4. 최종 포맷팅 요청 및 반환
        return solutionFormatterService.formatMergedSolution(finalLockedBusRoutes, newlyOptimizedBusRoutes,capacity,dbName, arrivalTime);
    }

    /**
     * OR-Tools Solver를 실행하는 공통 로직
     */
    private RouteSolutionDto runSolver(DataModel data, long timeLimit, long serviceTime,long arrivalTime) {
        preCheckStops(data,serviceTime);

        System.out.println("========= [SOLVER] OR-Tools 모델 생성 및 제약조건 설정 시작 ==========");
        RoutingIndexManager manager = new RoutingIndexManager(data.timeMatrix.length, data.numVehicles, data.depotIndex);
        RoutingModel routing = new RoutingModel(manager);

        final int costCallbackIndex = routing.registerTransitCallback(
                (long fromIndex, long toIndex) -> {
                    int fromNode = manager.indexToNode(fromIndex);
                    int toNode = manager.indexToNode(toIndex);
                    if (fromNode == data.depotIndex) return 0;
                    long travelTime = data.timeMatrix[fromNode][toNode];
                    if (toNode != data.depotIndex && travelTime > timeLimit) {
                        return PENALTY_FOR_EXCEEDING_TIME;
                    }
                    return travelTime;
                });
        routing.setArcCostEvaluatorOfAllVehicles(costCallbackIndex);

        final int serviceTimeCallbackIndex = routing.registerTransitCallback(
                (long fromIndex, long toIndex) -> {
                    int fromNode = manager.indexToNode(fromIndex);
                    if (fromNode == data.depotIndex) return 0;
                    return data.timeMatrix[fromNode][manager.indexToNode(toIndex)];
                });
        routing.addDimension(serviceTimeCallbackIndex, 0, serviceTime, true, "ServiceTime");

        final int demandCallbackIndex = routing.registerUnaryTransitCallback(
                (fromIndex) -> data.virtualStops.get(manager.indexToNode(fromIndex)).demand);
        routing.addDimensionWithVehicleCapacity(demandCallbackIndex, 0, data.vehicleCapacities, true, "Capacity");

        System.out.printf("========= [SOLVER] 경로 최적화 계산 시작 (최대 %d초) ==========%n", SEARCH_TIME_LIMIT_SECONDS);
        RoutingSearchParameters searchParameters = main.defaultRoutingSearchParameters().toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setTimeLimit(Duration.newBuilder().setSeconds(SEARCH_TIME_LIMIT_SECONDS).build())
                .build();

        Assignment solution = routing.solveWithParameters(searchParameters);

        if (solution != null) {
            System.out.println("[SOLVER] 최적 경로 계산 성공!");
            return solutionFormatterService.formatSolutionToDto(data, manager, routing, solution,arrivalTime);
        } else {
            System.err.println("!!! [SOLVER] 최적 경로 계산 실패 !!!");
            System.err.println("!!! Solver Status: " + routing.status() + " !!!");
            return null;
        }
    }

    private void preCheckStops(DataModel data, long serviceTime) {
        if(data.timeMatrix.length <= 1) return;
        System.out.println("\n--- [사전 검사] 각 정류장의 최소 서비스 시간 확인 (제한: " + serviceTime + "분) ---");
        boolean hasImpossibleStop = false;
        for (int i = 1; i < data.virtualStops.size(); i++) {
            VirtualStop stop = data.virtualStops.get(i);
            long minServiceTime = data.timeMatrix[i][data.depotIndex];
            if (minServiceTime > serviceTime) {
                System.err.printf("  [WARNING] 정류장 '%s' (ID: %s)는 단독 운행만으로도 서비스 시간 제한을 초과합니다. (필요시간: %d분)%n",
                        stop.name, stop.originalId, minServiceTime);
                hasImpossibleStop = true;
            }
        }
        if (!hasImpossibleStop) {
            System.out.println("  > 모든 정류장이 최소 서비스 시간 조건을 만족합니다.");
        }
        System.out.println("----------------------------------------------------------------------\n");
    }

    private DataModel createDataModel(List<VirtualStop> virtualStops, int capacity, int numvehicles) {
        // 1. 카카오 API를 통해 기본 시간 행렬을 가져옵니다.
        long[][] timeMatrix = kakaoApiService.createTimeMatrixFromApi(virtualStops);

        // --- [추가] U턴 페널티 로직 ---
        final double U_TURN_PENALTY_FACTOR = 3.0; // U턴 의심 구간에 이동 시간을 3배로 증가
        final double U_TURN_THRESHOLD_METERS_PER_MINUTE = 60; // 분당 50m 미만 이동 시 U턴 의심

        System.out.println("[PENALTY LOG] U턴이 의심되는 경로에 페널티를 부과합니다...");
        for (int i = 0; i < virtualStops.size(); i++) {
            for (int j = 0; j < virtualStops.size(); j++) {
                if (i == j || timeMatrix[i][j] >= 999) continue; // 자기 자신 또는 이동 불가 경로는 제외

                VirtualStop origin = virtualStops.get(i);
                VirtualStop destination = virtualStops.get(j);

                double haversineDistance = calculateHaversineDistance(origin.lat, origin.lon, destination.lat, destination.lon);
                long travelTimeInMinutes = timeMatrix[i][j];

                if (travelTimeInMinutes > 0) {
                    double metersPerMinute = haversineDistance / travelTimeInMinutes;

                    // 직선 거리에 비해 이동 시간이 비정상적으로 길면 (즉, 속도가 매우 느리면)
                    if (metersPerMinute < U_TURN_THRESHOLD_METERS_PER_MINUTE) {
                        System.out.printf("  [PENALTY APPLIED] '%s' -> '%s' (%.1f m/min) 경로 페널티 적용!%n",
                                origin.name, destination.name, metersPerMinute);
                        timeMatrix[i][j] = (long) (travelTimeInMinutes * U_TURN_PENALTY_FACTOR);
                    }
                }
            }
        }
        // --- 페널티 로직 끝 ---

        long[] vehicleCapacities = new long[numvehicles];
        for (int i=0; i < numvehicles; i++) {
            vehicleCapacities[i] = capacity;
        }
        return new DataModel(timeMatrix, virtualStops, numvehicles, vehicleCapacities);
    }

    private VirtualStop findVirtualStop(List<VirtualStop> allStops, StopDto targetStop) {
        return allStops.stream()
                .filter(vs -> vs.originalId.equals(targetStop.getId()) && targetStop.getName().startsWith(vs.name.split("-")[0]))
                .findFirst()
                .orElse(null);
    }
    /**
     * 두 지점의 위도, 경도를 받아 직선 거리(미터 단위)를 계산하는 함수 (Haversine 공식)
     */
    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // 지구의 반지름 (킬로미터)
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c * 1000; // 미터 단위로 변환
    }
}