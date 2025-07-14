package com.riansoft.bus_tsp;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.*;
import com.google.protobuf.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
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
        // 1. 테스트용 데이터 준비
        final DataModel data = createTestDataWithSplitDemand();

        // 2. 라우팅 모델 생성 및 제약조건 설정
        RoutingIndexManager manager = new RoutingIndexManager(data.timeMatrix.length, data.numVehicles, data.depotIndex);
        RoutingModel routing = new RoutingModel(manager);

        final int transitCallbackIndex = routing.registerTransitCallback(
                (long fromIndex, long toIndex) -> data.timeMatrix[manager.indexToNode(fromIndex)][manager.indexToNode(toIndex)]);
        routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);

        final int demandCallbackIndex = routing.registerUnaryTransitCallback(
                (long fromIndex) -> data.virtualStops.get(manager.indexToNode(fromIndex)).demand);
        routing.addDimensionWithVehicleCapacity(
                demandCallbackIndex, 0, data.vehicleCapacities, true, "Capacity");

        // 3. 문제 해결
        RoutingSearchParameters searchParameters = main.defaultRoutingSearchParameters().toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setTimeLimit(Duration.newBuilder().setSeconds(5).build())
                .build();

        Assignment solution = routing.solveWithParameters(searchParameters);

        // 4. 결과 검증 및 출력
        assertNotNull(solution, "해답(solution)은 null이 아니어야 합니다. 계산에 실패했습니다.");
        printSolution(data, manager, routing, solution);
    }

    private DataModel createTestDataWithSplitDemand() {
        // ... (이전과 동일)
        List<PhysicalStop> physicalStops = new ArrayList<>();
        Random random = new Random();
        physicalStops.add(new PhysicalStop("DEPOT_YJ", "양주차고지", 0, 37.7836, 127.0456));
        for (int i = 1; i < 100; i++) {
            String stopId = "ST_" + String.format("%03d", i);
            String stopName = "랜덤정류장-" + i;
            long demand = random.nextInt(41) + 5; // 수요량을 5~45로 조정
            double lat = 37.4 + (37.8 - 37.4) * random.nextDouble();
            double lon = 126.8 + (127.2 - 126.8) * random.nextDouble();
            physicalStops.add(new PhysicalStop(stopId, stopName, demand, lat, lon));
        }
        int vehicleCapacity = 45;
        List<VirtualStop> virtualStops = new ArrayList<>();
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
        long[][] timeMatrix = createTimeMatrix(virtualStops);
        int maxVehicles = 1000;
        long[] vehicleCapacities = new long[maxVehicles];
        for (int i = 0; i < maxVehicles; i++) {
            vehicleCapacities[i] = vehicleCapacity;
        }
        return new DataModel(timeMatrix, virtualStops, maxVehicles, vehicleCapacities);
    }

    private long[][] createTimeMatrix(List<VirtualStop> stops) {
        // ... (이전과 동일)
        int numStops = stops.size();
        long[][] matrix = new long[numStops][numStops];
        for (int i = 0; i < numStops; i++) {
            for (int j = 0; j < numStops; j++) {
                if (i == j) continue;
                if (stops.get(i).originalId.equals(stops.get(j).originalId)) {
                    matrix[i][j] = 0;
                } else {
                    matrix[i][j] = callApiSimulation(stops.get(i), stops.get(j));
                }
            }
        }
        return matrix;
    }

    private long callApiSimulation(VirtualStop origin, VirtualStop destination) {
        // ... (이전과 동일)
        final double R = 6371.0;
        double lat1 = Math.toRadians(origin.lat), lon1 = Math.toRadians(origin.lon);
        double lat2 = Math.toRadians(destination.lat), lon2 = Math.toRadians(destination.lon);
        double dlon = lon2 - lon1, dlat = lat2 - lat1;
        double a = Math.pow(Math.sin(dlat / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dlon / 2), 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (long) ((R * c / 35.0) * 60 + 5);
    }

    /**
     * **[수정된 부분]** 계산된 최적 경로, 각 버스의 상세 경로 및 시간 통계를 콘솔에 출력합니다.
     */
    private void printSolution(final DataModel data, final RoutingIndexManager manager,
                               final RoutingModel routing, final Assignment solution) {

        System.out.println("--- 최종 최적 경로 결과 ---");
        System.out.println("전체 목표 (총 이동 시간): " + solution.objectiveValue() + "분");

        RoutingDimension capacityDimension = routing.getDimensionOrDie("Capacity");
        List<Long> routeTimes = new ArrayList<>(); // 각 버스의 이동 시간을 저장할 리스트

        for (int i = 0; i < data.numVehicles; ++i) {
            long index = routing.start(i);
            // 실제로 운행하는 버스만 계산
            if (routing.isEnd(solution.value(routing.nextVar(index)))) {
                continue;
            }

            System.out.printf("%n===== 버스 #%d의 운행 계획 =====%n", routeTimes.size() + 1);

            String routePath = "";
            long routeTime = 0;
            long previousIndex = index;

            while (!routing.isEnd(index)) {
                int nodeIndex = manager.indexToNode(index);
                VirtualStop currentStop = data.virtualStops.get(nodeIndex);
                long routeLoad = solution.value(capacityDimension.cumulVar(index));

                if (nodeIndex == data.depotIndex) {
                    routePath += String.format("  %s(출발)", currentStop.name);
                } else {
                    routePath += String.format(" → %s (ID: %s / 누적 %d명)", currentStop.name, currentStop.originalId, routeLoad);
                }

                // 현재 구간의 이동 시간을 누적
                previousIndex = index;
                index = solution.value(routing.nextVar(index));
                routeTime += routing.getArcCostForVehicle(previousIndex, index, i);
            }

            VirtualStop depotStop = data.virtualStops.get(manager.indexToNode(index));
            routePath += String.format(" → %s(복귀)", depotStop.name);

            long finalLoad = solution.value(capacityDimension.cumulVar(routing.end(i)));
            routePath += String.format("%n  → 최종 탑승 인원: %d명 (버스 정원: %d명)", finalLoad, data.vehicleCapacities[i]);
            routePath += String.format("%n  → 이 버스의 총 이동 시간: %d분", routeTime);

            System.out.println(routePath);
            routeTimes.add(routeTime); // 계산된 시간을 리스트에 추가
        }

        // --- 최종 통계 출력 ---
        System.out.println("\n\n==================== 최종 운행 통계 ====================");
        if (routeTimes.isEmpty()) {
            System.out.println("운행에 필요한 버스가 없습니다.");
        } else {
            // 스트림을 사용하여 평균, 최대, 최소값 계산
            double averageTime = routeTimes.stream().mapToLong(val -> val).average().orElse(0.0);
            long maxTime = Collections.max(routeTimes);
            long minTime = Collections.min(routeTimes);

            System.out.println("총 운행 버스 수: " + routeTimes.size() + "대");
            System.out.printf("평균 버스 이동 시간: %.2f분%n", averageTime);
            System.out.println("최대 버스 이동 시간: " + maxTime + "분");
            System.out.println("최소 버스 이동 시간: " + minTime + "분");
        }
        System.out.println("======================================================");
    }
}