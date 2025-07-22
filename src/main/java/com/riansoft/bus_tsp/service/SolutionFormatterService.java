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

    public RouteSolutionDto formatSolutionToDto(DataModel data, RoutingIndexManager manager, RoutingModel routing, Assignment solution) {
        List<BusRouteDto> busRoutes = new ArrayList<>();
        int usedVehiclesCount = 0;

        final long finalArrivalTimeTarget = 9 * 60;
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

                // --- [핵심 수정] ---
                // 현재 정류장이 차고지가 아니기만 하면, 다음 목적지(차고지 포함)까지의 상세 경로를 요청합니다.
                if (nodeIndex != data.depotIndex) {
                    int nextNodeIndex = manager.indexToNode(busstop);
                    VirtualStop nextVStop = data.virtualStops.get(nextNodeIndex);
                    detailedPathSegments.add(kakaoApiService.getDetailedPath(vStop, nextVStop));
                }
                // --- 수정 끝 ---
            }

            VirtualStop lastStop = data.virtualStops.get(manager.indexToNode(busstop));
            StopDto lastStopDto = new StopDto(lastStop.originalId, lastStop.name, lastStop.demand, lastStop.lat, lastStop.lon);
            lastStopDto.setArrivalTime(finalArrivalTimeTarget);
            routePathForDto.add(lastStopDto);

            long finalLoad = solution.value(capacityDimension.cumulVar(routing.end(carindex)));
            String routeColor = colors.get(usedVehiclesCount - 1);
            busRoutes.add(new BusRouteDto(usedVehiclesCount, routePathForDto, totalRouteTime, finalLoad, routeColor, detailedPathSegments));
        }

        return new RouteSolutionDto(solution.objectiveValue(), usedVehiclesCount, busRoutes);
    }

    public RouteSolutionDto formatMergedSolution(List<BusRouteDto> lockedRoutes, List<BusRouteDto> newlyOptimizedRoutes) {
        List<BusRouteDto> finalBusRoutes = new ArrayList<>();
        finalBusRoutes.addAll(lockedRoutes);
        finalBusRoutes.addAll(newlyOptimizedRoutes);

        List<String> colors = generateDistinctColors(finalBusRoutes.size());
        final long finalArrivalTimeTarget = 9 * 60;

        List<VirtualStop> allStops = stopDataService.getVirtualStops(RouteOptimizationService.VEHICLE_CAPACITY);
        VirtualStop depot = allStops.get(0);

        for (int i = 0; i < finalBusRoutes.size(); i++) {
            BusRouteDto route = finalBusRoutes.get(i);
            route.setBusId(i + 1);
            route.setColor(colors.get(i));

            long totalRouteTime = route.getRouteTime();
            long departureTime = finalArrivalTimeTarget - totalRouteTime;
            long accumulatedTime = departureTime;

            List<StopDto> stops = route.getRoute();

            if (!stops.isEmpty() && !stops.get(0).getId().startsWith("DEPOT")) {
                VirtualStop firstStop = findVirtualStop(allStops, stops.get(0));
                accumulatedTime += kakaoApiService.getDurationInMinutes(depot, firstStop);
            }

            for (int j = 0; j < stops.size(); j++) {
                StopDto currentStop = stops.get(j);
                currentStop.setArrivalTime(accumulatedTime);

                if (j < stops.size() - 1) {
                    StopDto nextStop = stops.get(j + 1);
                    VirtualStop origin = findVirtualStop(allStops, currentStop);
                    VirtualStop destination = findVirtualStop(allStops, nextStop);
                    accumulatedTime += kakaoApiService.getDurationInMinutes(origin, destination);
                }
            }
        }

        long totalObjectiveTime = finalBusRoutes.stream().mapToLong(BusRouteDto::getRouteTime).sum();
        int usedBuses = finalBusRoutes.size();

        return new RouteSolutionDto(totalObjectiveTime, usedBuses, finalBusRoutes);
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
