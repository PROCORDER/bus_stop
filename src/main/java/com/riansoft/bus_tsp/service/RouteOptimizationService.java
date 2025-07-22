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

    public static final int VEHICLE_CAPACITY = 45;
    private static final int MAX_VEHICLES = 10000;
    private static final long STOP_TO_STOP_TIME_LIMIT = 20;
    private static final long PENALTY_FOR_EXCEEDING_TIME = 100000;
    private static final long MAX_SERVICE_TIME = 100;
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

    public RouteSolutionDto findOptimalRoutes() {
        System.out.println("\n========= [1/5] 최초 최적 경로 계산 시작 ==========");
        List<VirtualStop> virtualStops = stopDataService.getVirtualStops(VEHICLE_CAPACITY);
        DataModel data = createDataModel(virtualStops);
        return runSolver(data);
    }

    /**
     * [새로운 메서드] 고정된 제약조건으로 경로를 재계산합니다.
     */
    public RouteSolutionDto reOptimizeWithConstraints(RouteModificationRequestDto request) {
        System.out.println("\n========= [RE-OPTIMIZE] 재계산 시작 ==========");
        // 1. 데이터 준비 및 분리
        List<ModifiedRouteDto> lockedRoutesRequest = request.getModifications();
        List<VirtualStop> allVirtualStops = stopDataService.getVirtualStops(VEHICLE_CAPACITY);

        Set<String> lockedStopIds = new HashSet<>();
        for (ModifiedRouteDto lockedRoute : lockedRoutesRequest) {
            lockedRoute.getNewRoute().forEach(stop -> lockedStopIds.add(stop.getId()));
        }

        List<VirtualStop> remainingStops = allVirtualStops.stream()
                .filter(stop -> stop.originalId.equals("DEPOT_YJ") || !lockedStopIds.contains(stop.originalId))
                .collect(Collectors.toList());

        // 2. 고정 경로 처리 -> BusRouteDto 리스트로 변환
        List<BusRouteDto> finalLockedBusRoutes = new ArrayList<>();
        for (ModifiedRouteDto lockedRoute : lockedRoutesRequest) {
            ValidatedRouteDto validated = validationService.validateAndCalculate(lockedRoute);
            List<StopDto> routeStops = lockedRoute.getNewRoute();

            List<List<LatLngDto>> detailedPath = new ArrayList<>();
            // (차고지->첫경유지 상세경로) 와 (경유지->경유지 상세경로) 추가
            if (!routeStops.isEmpty()) {
                VirtualStop depot = allVirtualStops.get(0);
                detailedPath.add(kakaoApiService.getDetailedPath(depot, findVirtualStop(allVirtualStops, routeStops.get(0))));
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
            DataModel remainingData = createDataModel(remainingStops);
            RouteSolutionDto newlyOptimizedSolution = runSolver(remainingData);
            if (newlyOptimizedSolution != null) {
                newlyOptimizedBusRoutes = newlyOptimizedSolution.getBusRoutes();
            }
        }

        // 4. 최종 포맷팅 요청 및 반환
        return solutionFormatterService.formatMergedSolution(finalLockedBusRoutes, newlyOptimizedBusRoutes);
    }

    /**
     * [리팩토링] OR-Tools Solver를 실행하는 공통 로직
     */
    private RouteSolutionDto runSolver(DataModel data) {
        preCheckStops(data);

        System.out.println("========= [SOLVER] OR-Tools 모델 생성 및 제약조건 설정 시작 ==========");
        RoutingIndexManager manager = new RoutingIndexManager(data.timeMatrix.length, data.numVehicles, data.depotIndex);
        RoutingModel routing = new RoutingModel(manager);

        final int costCallbackIndex = routing.registerTransitCallback(
                (long fromIndex, long toIndex) -> {
                    int fromNode = manager.indexToNode(fromIndex);
                    int toNode = manager.indexToNode(toIndex);
                    if (fromNode == data.depotIndex) return 0;
                    long travelTime = data.timeMatrix[fromNode][toNode];
                    if (toNode != data.depotIndex && travelTime > STOP_TO_STOP_TIME_LIMIT) {
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
        routing.addDimension(serviceTimeCallbackIndex, 0, MAX_SERVICE_TIME, true, "ServiceTime");

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

    private void preCheckStops(DataModel data) {
        if(data.timeMatrix.length <= 1) return;
        System.out.println("\n--- [사전 검사] 각 정류장의 최소 서비스 시간 확인 (제한: " + MAX_SERVICE_TIME + "분) ---");
        boolean hasImpossibleStop = false;
        for (int i = 1; i < data.virtualStops.size(); i++) {
            VirtualStop stop = data.virtualStops.get(i);
            long minServiceTime = data.timeMatrix[i][data.depotIndex];
            if (minServiceTime > MAX_SERVICE_TIME) {
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

    // DataModel 생성을 위한 헬퍼 메서드 (timeMatrix 생성을 내부로 포함)
    private DataModel createDataModel(List<VirtualStop> virtualStops) {
        long[][] timeMatrix = kakaoApiService.createTimeMatrixFromApi(virtualStops);
        long[] vehicleCapacities = new long[MAX_VEHICLES];
        for (int i=0; i < MAX_VEHICLES; i++) {
            vehicleCapacities[i] = VEHICLE_CAPACITY;
        }
        return new DataModel(timeMatrix, virtualStops, MAX_VEHICLES, vehicleCapacities);
    }

    // findVirtualStop 헬퍼 메서드 추가
    private VirtualStop findVirtualStop(List<VirtualStop> allStops, StopDto targetStop) {
        return allStops.stream()
                .filter(vs -> vs.originalId.equals(targetStop.getId()) && targetStop.getName().startsWith(vs.name.split("-")[0]))
                .findFirst()
                .orElse(null);
    }
}