package com.riansoft.bus_tsp.service;

import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.riansoft.bus_tsp.dto.BusRouteDto;
import com.riansoft.bus_tsp.dto.LatLngDto;
import com.riansoft.bus_tsp.dto.RouteSolutionDto;
import com.riansoft.bus_tsp.dto.StopDto;
import com.riansoft.bus_tsp.model.DataModel;
import com.riansoft.bus_tsp.model.VirtualStop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class SolutionFormatterService {

    private final KakaoApiService kakaoApiService;
    private final StopDataService stopDataService;

    @Autowired
    public SolutionFormatterService(KakaoApiService kakaoApiService, StopDataService stopDataService) {
        this.kakaoApiService = kakaoApiService;
        this.stopDataService = stopDataService;
    }

    /**
     * OR-Tools의 계산 결과를 바탕으로, 각 정류장별 도착 시간을 역산하고,
     * 상세 경로를 조회하여 최종 DTO로 변환합니다.
     */
    public RouteSolutionDto formatSolutionToDto(DataModel data, RoutingIndexManager manager, RoutingModel routing, Assignment solution,long arrivalTime) {
        List<BusRouteDto> busRoutes = new ArrayList<>();
        int usedVehiclesCount = 0;

        final long finalArrivalTimeTarget =arrivalTime;
        RoutingDimension capacityDimension = routing.getDimensionOrDie("Capacity");

        int totalUsedVehicles = 0;
        for (int i = 0; i < data.numVehicles; ++i) {
            if (!routing.isEnd(solution.value(routing.nextVar(routing.start(i))))) {
                totalUsedVehicles++;
            }
        }
        List<String> colors = generateDistinctColors(totalUsedVehicles);

        for (int carindex = 0; carindex < data.numVehicles; ++carindex) {
            long busstop = routing.start(carindex);
            if (routing.isEnd(solution.value(routing.nextVar(busstop)))) {
                continue;
            }

            usedVehiclesCount++;
            List<StopDto> routePathForDto = new ArrayList<>();

            long totalRouteTime = 0;
            long tempIndex = routing.start(carindex);
            long previousbusstop;
            while (!routing.isEnd(tempIndex)) {
                previousbusstop = tempIndex;
                tempIndex = solution.value(routing.nextVar(tempIndex));
                totalRouteTime += routing.getArcCostForVehicle(previousbusstop, tempIndex, carindex);
            }

            long departureTime = finalArrivalTimeTarget - totalRouteTime;
            busstop = routing.start(carindex);
            long accumulatedTime = departureTime;
            List<List<LatLngDto>> detailedPathSegments = new ArrayList<>();

            while (!routing.isEnd(busstop)) {
                int nodeIndex = manager.indexToNode(busstop);
                VirtualStop vStop = data.virtualStops.get(nodeIndex);
                long currentLoad = solution.value(capacityDimension.cumulVar(busstop));

                if (nodeIndex != data.depotIndex) {
                    StopDto stopDto = new StopDto(vStop.originalId, vStop.name, vStop.demand, vStop.lat, vStop.lon);
                    stopDto.setArrivalTime(accumulatedTime);
                    stopDto.setCurrentLoad(currentLoad);
                    routePathForDto.add(stopDto);
                }

                previousbusstop = busstop;
                busstop = solution.value(routing.nextVar(busstop));
                accumulatedTime += routing.getArcCostForVehicle(previousbusstop, busstop, carindex);

                if (nodeIndex != data.depotIndex) {
                    int nextNodeIndex = manager.indexToNode(busstop);
                    VirtualStop nextVStop = data.virtualStops.get(nextNodeIndex);
                    detailedPathSegments.add(kakaoApiService.getDetailedPath(vStop, nextVStop));
                }
            }

            VirtualStop lastStop = data.virtualStops.get(manager.indexToNode(busstop));
            StopDto lastStopDto = new StopDto(lastStop.originalId, lastStop.name, lastStop.demand, lastStop.lat, lastStop.lon);
            lastStopDto.setArrivalTime(finalArrivalTimeTarget);
            routePathForDto.add(lastStopDto);

            long finalLoad = solution.value(capacityDimension.cumulVar(routing.end(carindex)));
            String routeColor = colors.get(usedVehiclesCount - 1);
            busRoutes.add(new BusRouteDto(usedVehiclesCount, routePathForDto, totalRouteTime, finalLoad, routeColor, detailedPathSegments));
        }

        return new RouteSolutionDto( usedVehiclesCount, busRoutes);
    }

    /**
     * 고정 경로와 신규 경로를 병합하고, 최종 포맷팅하여 반환합니다.
     */
    public RouteSolutionDto formatMergedSolution(List<BusRouteDto> lockedRoutes, List<BusRouteDto> newlyOptimizedRoutes, int capacity, String dbName,long arrivalTime) {
        List<BusRouteDto> finalBusRoutes = new ArrayList<>();
        finalBusRoutes.addAll(lockedRoutes);
        finalBusRoutes.addAll(newlyOptimizedRoutes);

        List<String> colors = generateDistinctColors(finalBusRoutes.size());
        final long finalArrivalTimeTarget = arrivalTime;

        List<VirtualStop> allStops = stopDataService.getVirtualStops(capacity,dbName);

        for (int i = 0; i < finalBusRoutes.size(); i++) {
            BusRouteDto route = finalBusRoutes.get(i);
            route.setBusId(i + 1);
            route.setColor(colors.get(i));

            List<StopDto> stops = route.getRoute();
            if (stops.isEmpty()) continue;

            // 1. '첫 경유지 도착 시간'을 먼저 계산합니다.
            long totalRouteTime = route.getRouteTime();
            long firstStopArrivalTime = finalArrivalTimeTarget - totalRouteTime;
            long accumulatedTime = firstStopArrivalTime;

            // 2. 첫 경유지부터 마지막 '경유지'까지만 순회하며 도착 시간을 설정합니다.
            for (int j = 0; j < stops.size() - 1; j++) {
                StopDto currentStop = stops.get(j);
                currentStop.setArrivalTime(accumulatedTime);

                StopDto nextStop = stops.get(j + 1);
                VirtualStop origin = findVirtualStop(allStops, currentStop);
                VirtualStop destination = findVirtualStop(allStops, nextStop);
                if (origin != null && destination != null) {
                    accumulatedTime += kakaoApiService.getDurationFromCache(origin, destination);
                }
            }

            // 3. 마지막 정류장(차고지)의 도착 시간은 항상 목표 시간(9시)으로 강제 설정합니다.
            StopDto lastStop = stops.get(stops.size() - 1);
            lastStop.setArrivalTime(finalArrivalTimeTarget);
        }


        int usedBuses = finalBusRoutes.size();

        return new RouteSolutionDto( usedBuses, finalBusRoutes);
    }

    private List<String> generateDistinctColors(int count) {
        List<String> colors = new ArrayList<>();
        if (count <= 0) return colors;
        Random random = new Random(0);
        float currentHue = random.nextFloat();
        final float GOLDEN_RATIO_CONJUGATE = 0.61803398875f;
        for (int i = 0; i < count; i++) {
            float saturation = 0.85f;
            float brightness = 0.9f;
            Color color = Color.getHSBColor(currentHue, saturation, brightness);
            String hexColor = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
            colors.add(hexColor);
            currentHue = (currentHue + GOLDEN_RATIO_CONJUGATE) % 1.0f;
        }
        return colors;
    }

    private VirtualStop findVirtualStop(List<VirtualStop> allStops, StopDto targetStop) {
        return allStops.stream()
                .filter(vs -> vs.originalId.equals(targetStop.getId()) && targetStop.getName().startsWith(vs.name.split("-")[0]))
                .findFirst()
                .orElse(null);
    }
}
