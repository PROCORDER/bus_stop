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

    @Autowired
    public SolutionFormatterService(KakaoApiService kakaoApiService) {
        this.kakaoApiService = kakaoApiService;
    }

    /**
     * OR-Tools의 계산 결과를 바탕으로, 각 정류장별 도착 시간을 역산하고,
     * 상세 경로를 조회하여 최종 DTO로 변환합니다.
     */
    public RouteSolutionDto formatSolutionToDto(DataModel data, RoutingIndexManager manager, RoutingModel routing, Assignment solution) {
        List<BusRouteDto> busRoutes = new ArrayList<>();
        int usedVehiclesCount = 0;

        // 최종 도착 목표 시각 설정 (오전 9시 = 540분)
        final long finalArrivalTimeTarget = 9 * 60;

        RoutingDimension capacityDimension = routing.getDimensionOrDie("Capacity");

        int totalUsedVehicles = 0;
        for (int i = 0; i < data.numVehicles; ++i) {
            if (!routing.isEnd(solution.value(routing.nextVar(routing.start(i))))) {
                totalUsedVehicles++;
            }
        }
        List<String> colors = generateDistinctColors(totalUsedVehicles);

        //차량의 다음 행선지가 차고지라면 그것은 운행하지 않는 것 따라서 바로 제외
        for (int carindex = 0; carindex < data.numVehicles; ++carindex) {
            long busstop = routing.start(carindex);
            if (routing.isEnd(solution.value(routing.nextVar(busstop)))) {
                continue;
            }

            usedVehiclesCount++;
            List<StopDto> routePathForDto = new ArrayList<>();

            // 1. 먼저 '총 운행 시간' (차고지 출발 ~ 복귀)을 계산합니다.
            long totalRouteTime = 0;
            long tempIndex = routing.start(carindex);
            long previousIndex;
            while (!routing.isEnd(tempIndex)) {
                previousIndex = tempIndex;
                tempIndex = solution.value(routing.nextVar(tempIndex));
                totalRouteTime += routing.getArcCostForVehicle(previousIndex, tempIndex, carindex);
            }

            // 2. 이 버스가 차고지에서 출발해야 할 시각을 역산합니다.
            long departureTime = finalArrivalTimeTarget - totalRouteTime;

            // 3. 다시 경로를 순회하며, 각 정류장의 도착 시각을 계산하고 상세 경로를 조회합니다.
            busstop = routing.start(carindex);
            long accumulatedTime = departureTime;
            List<List<LatLngDto>> detailedPathSegments = new ArrayList<>();

            while (!routing.isEnd(busstop)) {
                int nodeIndex = manager.indexToNode(busstop);
                VirtualStop vStop = data.virtualStops.get(nodeIndex);
                long currentLoad = solution.value(capacityDimension.cumulVar(busstop));

                StopDto stopDto = new StopDto(vStop.originalId, vStop.name, vStop.demand, vStop.lat, vStop.lon);
                stopDto.setArrivalTime(accumulatedTime);
                stopDto.setCurrentLoad(currentLoad);
                routePathForDto.add(stopDto);

                previousIndex = busstop;
                busstop = solution.value(routing.nextVar(busstop));
                accumulatedTime += routing.getArcCostForVehicle(previousIndex, busstop, carindex);

                // 현재 구간의 상세 경로 조회
                if (!routing.isEnd(busstop)) {
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

        return new RouteSolutionDto(solution.objectiveValue(), usedVehiclesCount, busRoutes);
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
}

