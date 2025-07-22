package com.riansoft.bus_tsp.service;

import com.riansoft.bus_tsp.model.DataModel;
import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import com.google.protobuf.Duration;
import com.riansoft.bus_tsp.dto.RouteSolutionDto;
import com.riansoft.bus_tsp.model.VirtualStop;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class RouteOptimizationService {

    // --- 주요 파라미터 상수 정의 ---
    public static final int VEHICLE_CAPACITY = 45;
    private static final int MAX_VEHICLES = 10000;
    private static final long STOP_TO_STOP_TIME_LIMIT = 20;
    private static final long PENALTY_FOR_EXCEEDING_TIME = 100000;
    private static final long MAX_SERVICE_TIME = 100; // 첫 경유지부터 마지막 경유지까지 최대 시간
    private static final long SEARCH_TIME_LIMIT_SECONDS = 30;

    private final StopDataService stopDataService;
    private final KakaoApiService kakaoApiService;
    private final SolutionFormatterService solutionFormatterService;

    @Autowired
    public RouteOptimizationService(StopDataService stopDataService, KakaoApiService kakaoApiService, SolutionFormatterService solutionFormatterService) {
        this.stopDataService = stopDataService;
        this.kakaoApiService = kakaoApiService;
        this.solutionFormatterService = solutionFormatterService;
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
        System.out.println("\n========= [1/5] 데이터 준비 단계 시작 ==========");
        List<VirtualStop> virtualStops = stopDataService.getVirtualStops(VEHICLE_CAPACITY);
        long[][] timeMatrix = kakaoApiService.createTimeMatrixFromApi(virtualStops);
        DataModel data = createDataModel(timeMatrix, virtualStops);
        System.out.println("========= [1/5] 데이터 준비 단계 완료 ==========\n");

        // --- [핵심 추가 부분] ---
        // 최적화 시작 전, 규칙을 위반하는 정류장이 있는지 미리 확인합니다.
        preCheckStops(data);
        // -----------------------

        System.out.println("========= [2/5] OR-Tools 모델 생성 및 제약조건 설정 시작 ==========");
        RoutingIndexManager manager = new RoutingIndexManager(data.timeMatrix.length, data.numVehicles, data.depotIndex);
        RoutingModel routing = new RoutingModel(manager);
        System.out.println("[LOG] Routing Model 생성 완료.");

        // 제약조건 1: "비용" 규칙 정의
        final int costCallbackIndex = routing.registerTransitCallback(
                (long fromIndex, long toIndex) -> {
                    int fromNode = manager.indexToNode(fromIndex);
                    int toNode = manager.indexToNode(toIndex);
                    // 출발지에서 첫 경유지 가는 비용은 0으로 계산
                    if (fromNode == data.depotIndex) return 0;
                    long travelTime = data.timeMatrix[fromNode][toNode];
                    //경유지 시간이 일정 시간을 넘겼을 때는 벌점을 부과하여 경유지 시간이 20분 이내
                    if (toNode != data.depotIndex && travelTime > STOP_TO_STOP_TIME_LIMIT) {
                        return PENALTY_FOR_EXCEEDING_TIME;
                    }
                    return travelTime;
                });
        routing.setArcCostEvaluatorOfAllVehicles(costCallbackIndex);
        System.out.printf("[LOG] 제약조건 1: '경유지 간 %d분 초과 시 페널티' 및 '출발 시간 무시' 비용 규칙 설정 완료.%n", STOP_TO_STOP_TIME_LIMIT);

        // 제약조건 2: "서비스 시간" 규칙 정의 (첫 경유지 ~ 차고지 복귀)
        final int serviceTimeCallbackIndex = routing.registerTransitCallback(
                (long fromIndex, long toIndex) -> {
                    int fromNode = manager.indexToNode(fromIndex);
                    if (fromNode == data.depotIndex) return 0;
                    return data.timeMatrix[fromNode][manager.indexToNode(toIndex)];
                }
        );
        routing.addDimension(
                serviceTimeCallbackIndex,
                0,
                MAX_SERVICE_TIME,
                true,
                "ServiceTime"
        );
        System.out.printf("[LOG] 제약조건 2: '첫 경유지부터 복귀까지 %d분' 규칙 설정 완료.%n", MAX_SERVICE_TIME);

        // 제약조건 3: "탑승 인원(용량)" 규칙
        final int demandCallbackIndex = routing.registerUnaryTransitCallback(
                (fromIndex) -> data.virtualStops.get(manager.indexToNode(fromIndex)).demand);
        routing.addDimensionWithVehicleCapacity(demandCallbackIndex, 0, data.vehicleCapacities, true, "Capacity");
        System.out.printf("[LOG] 제약조건 3: '버스 최대 정원 %d명' 규칙 설정 완료.%n", VEHICLE_CAPACITY);
        System.out.println("========= [2/5] OR-Tools 모델 생성 및 제약조건 설정 완료 ==========\n");

        System.out.printf("========= [3/5] 경로 최적화 계산 시작 (최대 %d초) ==========%n", SEARCH_TIME_LIMIT_SECONDS);
        RoutingSearchParameters searchParameters = main.defaultRoutingSearchParameters().toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setTimeLimit(Duration.newBuilder().setSeconds(SEARCH_TIME_LIMIT_SECONDS).build())
                .build();

        Assignment solution = routing.solveWithParameters(searchParameters);
        System.out.println("========= [3/5] 경로 최적화 계산 완료 ==========\n");

        System.out.println("========= [4/5] 결과 분석 및 반환 시작 ==========");
        if (solution != null) {
            System.out.println("[SUCCESS] 최적 경로 계산에 성공했습니다!");
            return solutionFormatterService.formatSolutionToDto(data, manager, routing, solution);
        } else {
            System.err.println("!!! [FAILURE] 최적 경로 계산에 실패했습니다 !!!");
            System.err.println("!!! Solver Status: " + routing.status() + " !!!");
            System.err.println("!!! 원인: 설정된 모든 제약조건을 만족하는 해답을 찾을 수 없습니다.");
            System.err.println("!!! 팁: 사전 검사 로그를 확인하여 불가능한 정류장이 있는지 확인하고, MAX_SERVICE_TIME 등의 제약 조건을 완화해보세요.");
            return null;
        }
    }


    private void preCheckStops(DataModel data) {
        System.out.println("\n--- [사전 검사] 각 정류장의 최소 서비스 시간 확인 (제한: " + MAX_SERVICE_TIME + "분) ---");
        boolean hasImpossibleStop = false;

        // 1번부터 마지막 정류장까지 순회 (0번은 차고지이므로 제외)
        for (int i = 1; i < data.virtualStops.size(); i++) {
            VirtualStop stop = data.virtualStops.get(i);

            // 이 정류장 하나만 방문하고 복귀하는 데 걸리는 시간 (첫 경유지 -> 차고지)
            // 우리 'ServiceTime' 규칙에 따르면, 이 시간이 최소 서비스 시간입니다.
            long minServiceTime = data.timeMatrix[i][data.depotIndex];

            if (minServiceTime > MAX_SERVICE_TIME) {
                System.err.printf(
                        "  [WARNING] 정류장 '%s' (ID: %s)는 단독 운행만으로도 서비스 시간 제한을 초과합니다. (필요시간: %d분)%n",
                        stop.name, stop.originalId, minServiceTime
                );
                hasImpossibleStop = true;
            }
        }

        if (!hasImpossibleStop) {
            System.out.println("  > 모든 정류장이 최소 서비스 시간 조건을 만족합니다.");
        }
        System.out.println("----------------------------------------------------------------------\n");
    }



    private DataModel createDataModel(long[][] timeMatrix, List<VirtualStop> virtualStops) {
        long[] vehicleCapacities = new long[MAX_VEHICLES];
        for (int i=0; i < MAX_VEHICLES; i++) {
            vehicleCapacities[i] = VEHICLE_CAPACITY;
        }
        return new DataModel(timeMatrix, virtualStops, MAX_VEHICLES, vehicleCapacities);
    }
}