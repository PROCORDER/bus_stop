package com.riansoft.bus_tsp.service;

import com.google.ortools.constraintsolver.*;
import com.google.protobuf.Duration;
import com.riansoft.bus_tsp.dto.*;
import com.riansoft.bus_tsp.model.DataModel;
import com.riansoft.bus_tsp.model.VirtualStop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RouteOptimizationService {

    private static final int MAX_VEHICLES = 10000;
    private static final long PENALTY_FOR_EXCEEDING_TIME = 100000;
    private static final long SEARCH_TIME_LIMIT_SECONDS = 30;

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



    public RouteSolutionDto findOptimalRoutes(long timeLimit, int capacity, long serviceTime, String dbName) {
        System.out.println("\n========= [1/5] 최초 최적 경로 계산 시작 ==========");
        List<VirtualStop> virtualStops = stopDataService.getVirtualStops(capacity, dbName);
        DataModel data = createDataModel(virtualStops, capacity);
        return runSolver(data, timeLimit, serviceTime);
    }

    /**
     * 고정된 제약조건으로 경로를 재계산합니다.
     */
    public RouteSolutionDto reOptimizeWithConstraints(RouteModificationRequestDto request, long timeLimit, int capacity, long serviceTime, String dbName) {
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
            DataModel remainingData = createDataModel(remainingStops, capacity);
            RouteSolutionDto newlyOptimizedSolution = runSolver(remainingData, timeLimit, serviceTime);
            if (newlyOptimizedSolution != null) {
                newlyOptimizedBusRoutes = newlyOptimizedSolution.getBusRoutes();
            }
        }

        // 4. 최종 포맷팅 요청 및 반환
        return solutionFormatterService.formatMergedSolution(finalLockedBusRoutes, newlyOptimizedBusRoutes,capacity,dbName);
    }

    /**
     * OR-Tools Solver를 실행하는 공통 로직
     */
    private RouteSolutionDto runSolver(DataModel data, long timeLimit, long serviceTime) {
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
            return solutionFormatterService.formatSolutionToDto(data, manager, routing, solution);
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

    private DataModel createDataModel(List<VirtualStop> virtualStops, int capacity) {
        long[][] timeMatrix = kakaoApiService.createTimeMatrixFromApi(virtualStops);
        long[] vehicleCapacities = new long[MAX_VEHICLES];
        for (int i=0; i < MAX_VEHICLES; i++) {
            vehicleCapacities[i] = capacity;
        }
        return new DataModel(timeMatrix, virtualStops, MAX_VEHICLES, vehicleCapacities);
    }

    private VirtualStop findVirtualStop(List<VirtualStop> allStops, StopDto targetStop) {
        return allStops.stream()
                .filter(vs -> vs.originalId.equals(targetStop.getId()) && targetStop.getName().startsWith(vs.name.split("-")[0]))
                .findFirst()
                .orElse(null);
    }
}