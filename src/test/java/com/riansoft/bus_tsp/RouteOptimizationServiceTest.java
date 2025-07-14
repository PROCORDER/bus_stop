package com.riansoft.bus_tsp;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.*;
import com.google.protobuf.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RouteOptimizationServiceTest {

    @BeforeAll
    static void loadNativeLibrary() {
        Loader.loadNativeLibraries();
    }

    // 데이터 구조 정의 클래스들 (이전과 동일)
    static class PhysicalStop {
        public final String id;
        public final String name;
        public final long demand;
        public final double lat;
        public final double lon;
        public PhysicalStop(String id, String name, long demand, double lat, double lon) { this.id = id; this.name = name; this.demand = demand; this.lat = lat; this.lon = lon; }
    }
    static class VirtualStop {
        public final String originalId;
        public final String name;
        public final long demand;
        public final double lat;
        public final double lon;
        public VirtualStop(String originalId, String name, long demand, double lat, double lon) { this.originalId = originalId; this.name = name; this.demand = demand; this.lat = lat; this.lon = lon; }
    }
    static class DataModel {
        public final long[][] timeMatrix;
        public final List<VirtualStop> virtualStops;
        public final int numVehicles;
        public final long[] vehicleCapacities;
        public final int depotIndex = 0;
        public DataModel(long[][] timeMatrix, List<VirtualStop> virtualStops, int numVehicles, long[] vehicleCapacities) { this.timeMatrix = timeMatrix; this.virtualStops = virtualStops; this.numVehicles = numVehicles; this.vehicleCapacities = vehicleCapacities; }
    }

    @Test
    @DisplayName("100개 정류장 데이터로 최적 경로를 계산한다")
    void when100StopsGiven_thenCalculatesOptimalRoutes() {
        System.out.println("====== [1/4] 테스트 데이터 생성 시작 ======");
        final DataModel data = createTestDataWithSplitDemand();
        System.out.println("====== [1/4] 테스트 데이터 생성 완료 ======\n");

        System.out.println("====== [2/4] OR-Tools 모델 및 제약조건 설정 시작 ======");
        RoutingIndexManager manager = new RoutingIndexManager(data.timeMatrix.length, data.numVehicles, data.depotIndex);
        RoutingModel routing = new RoutingModel(manager);

        final int transitCallbackIndex = routing.registerTransitCallback(
                (long fromIndex, long toIndex) -> data.timeMatrix[manager.indexToNode(fromIndex)][manager.indexToNode(toIndex)]);
        routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);

        final int demandCallbackIndex = routing.registerUnaryTransitCallback(
                (long fromIndex) -> data.virtualStops.get(manager.indexToNode(fromIndex)).demand);
        routing.addDimensionWithVehicleCapacity(
                demandCallbackIndex, 0, data.vehicleCapacities, true, "Capacity");
        System.out.println("====== [2/4] OR-Tools 모델 및 제약조건 설정 완료 ======\n");

        System.out.println("====== [3/4] 경로 최적화 계산 시작 (최대 5초) ======");
        RoutingSearchParameters searchParameters = main.defaultRoutingSearchParameters().toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setTimeLimit(Duration.newBuilder().setSeconds(20).build())
                .build();

        Assignment solution = routing.solveWithParameters(searchParameters);
        System.out.println("====== [3/4] 경로 최적화 계산 완료 ======\n");

        System.out.println("====== [4/4] 결과 검증 및 출력 시작 ======");
        assertNotNull(solution, "해답(solution)은 null이 아니어야 합니다. 계산에 실패했습니다.");
        printSolution(data, manager, routing, solution);
        System.out.println("====== [4/4] 결과 검증 및 출력 완료 ======");
    }

    /**
     * **[세분화된 로그 추가]** 100개의 정류장 데이터를 동적으로 생성합니다.
     */
    private DataModel createTestDataWithSplitDemand() {
        List<PhysicalStop> physicalStops = new ArrayList<>();
        Random random = new Random();

        // 0번: 차고지 추가
        physicalStops.add(new PhysicalStop("DEPOT_YJ", "양주차고지", 0, 37.7836, 127.0456));

        // 1번 ~ 99번: 랜덤 정류장 99개 추가
        for (int i = 1; i < 100; i++) {
            String stopId = "ST_" + String.format("%03d", i);
            String stopName = "랜덤정류장-" + i;
            long demand = random.nextInt(66) + 5;
            double lat = 37.4 + (37.8 - 37.4) * random.nextDouble();
            double lon = 126.8 + (127.2 - 126.8) * random.nextDouble();
            physicalStops.add(new PhysicalStop(stopId, stopName, demand, lat, lon));
        }
        System.out.println("  > 물리적 정류장(100개) 생성 완료.");

        int vehicleCapacity = 25;

        // 가상 정류장 생성
        List<VirtualStop> virtualStops = new ArrayList<>();
        System.out.println("  > 가상 정류장 분할 작업 시작...");
        for (PhysicalStop pStop : physicalStops) {
            if (pStop.demand <= vehicleCapacity) {
                virtualStops.add(new VirtualStop(pStop.id, pStop.name, pStop.demand, pStop.lat, pStop.lon));
            } else {
                long remainingDemand = pStop.demand;
                int splitCount = 1;
                while (remainingDemand > 0) {
                    long currentDemand = Math.min(remainingDemand, vehicleCapacity);
                    virtualStops.add(new VirtualStop(
                            pStop.id, pStop.name + "-" + splitCount, currentDemand, pStop.lat, pStop.lon));
                    remainingDemand -= currentDemand;
                    splitCount++;
                }
            }
        }
        System.out.println("  > 가상 정류장 분할 작업 완료. (총 " + virtualStops.size() + "개의 가상 정류장 생성)");

        // 가상 정류장 기반으로 시간 행렬 생성
        long[][] timeMatrix = createTimeMatrix(virtualStops);

        int maxVehicles = 1000;
        long[] vehicleCapacities = new long[maxVehicles];
        for (int i = 0; i < maxVehicles; i++) {
            vehicleCapacities[i] = vehicleCapacity;
        }

        return new DataModel(timeMatrix, virtualStops, maxVehicles, vehicleCapacities);
    }

    /**
     * **[세분화된 로그 추가]** 가상 정류장 목록으로 시간 행렬을 생성합니다.
     */
    private long[][] createTimeMatrix(List<VirtualStop> stops) {
        int numStops = stops.size();
        long[][] matrix = new long[numStops][numStops];

        System.out.println("  > 시간 행렬 생성 시작 (총 " + numStops + "x" + numStops + " = " + (numStops * numStops) + "개의 경로 계산 필요)");

        for (int i = 0; i < numStops; i++) {
            // 10번마다 한 번씩 현재 진행 상황을 출력
            if (i > 0 && i % 10 == 0) {
                System.out.printf("    ...진행률: %.1f%% (%d / %d 완료)%n", ((double)i / numStops * 100), i, numStops);
            }
            for (int j = 0; j < numStops; j++) {
                if (i == j) continue;
                if (stops.get(i).originalId.equals(stops.get(j).originalId)) {
                    matrix[i][j] = 0;
                } else {
                    matrix[i][j] = callApiSimulation(stops.get(i), stops.get(j));
                }
            }
        }
        System.out.printf("    ...진행률: 100.0%% (%d / %d 완료)%n", numStops, numStops);
        System.out.println("  > 시간 행렬 생성 완료.");
        return matrix;
    }

    // (시뮬레이션) API 호출
    private long callApiSimulation(VirtualStop origin, VirtualStop destination) {
        final double R = 6371.0;
        double lat1 = Math.toRadians(origin.lat), lon1 = Math.toRadians(origin.lon);
        double lat2 = Math.toRadians(destination.lat), lon2 = Math.toRadians(destination.lon);
        double dlon = lon2 - lon1, dlat = lat2 - lat1;
        double a = Math.pow(Math.sin(dlat / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dlon / 2), 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (long) ((R * c / 35.0) * 60 + 5);
    }

    // 결과 출력 메소드
    private void printSolution(final DataModel data, final RoutingIndexManager manager,
                               final RoutingModel routing, final Assignment solution) {
        System.out.println("목표: 총 이동 시간 최소화 -> " + solution.objectiveValue() + "분");
        RoutingDimension capacityDimension = routing.getDimensionOrDie("Capacity");
        int usedVehiclesCount = 0;
        for (int i = 0; i < data.numVehicles; ++i) {
            long index = routing.start(i);
            if (routing.isEnd(solution.value(routing.nextVar(index)))) continue;
            usedVehiclesCount++;
            System.out.printf("%n버스 #%d의 운행 경로:%n", usedVehiclesCount);
            String route = "";
            while (!routing.isEnd(index)) {
                int nodeIndex = manager.indexToNode(index);
                VirtualStop currentStop = data.virtualStops.get(nodeIndex);
                long routeLoad = solution.value(capacityDimension.cumulVar(index));
                if (nodeIndex == data.depotIndex) {
                    route += String.format("  %s(출발)", currentStop.name);
                } else {
                    route += String.format(" → %s (ID: %s / 누적 %d명)", currentStop.name, currentStop.originalId, routeLoad);
                }
                index = solution.value(routing.nextVar(index));
            }
            VirtualStop depotStop = data.virtualStops.get(manager.indexToNode(index));
            route += String.format(" → %s(복귀)", depotStop.name);
            long finalLoad = solution.value(capacityDimension.cumulVar(routing.end(i)));
            route += String.format("%n  → 최종 탑승 인원: %d명 (버스 정원: %d명)", finalLoad, data.vehicleCapacities[i]);
            System.out.println(route);
        }
        System.out.println("\n=====================================================");
        System.out.println("결론: 최적 버스 수는 " + usedVehiclesCount + "대 입니다.");
    }
}