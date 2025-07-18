package com.riansoft.bus_tsp.service;

import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.riansoft.bus_tsp.dto.BusRouteDto;
import com.riansoft.bus_tsp.dto.RouteSolutionDto;
import com.riansoft.bus_tsp.dto.StopDto;
import com.riansoft.bus_tsp.model.VirtualStop;
import com.riansoft.bus_tsp.model.DataModel;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

@Service
public class SolutionFormatterService {

    /**
     * OR-Tools의 계산 결과를 바탕으로, 각 정류장별 도착 시간을 역산하고,
     * 각 버스의 순수 서비스 시간(첫 경유지~도착지)을 계산하여 DTO로 변환합니다.
     */
    public RouteSolutionDto formatSolutionToDto(DataModel data, RoutingIndexManager manager, RoutingModel routing, Assignment solution) {
        List<BusRouteDto> busRoutes = new ArrayList<>();
        int usedVehiclesCount = 0;

        // 최종 도착 목표 시각 설정 (오전 9시 = 540분)
        final long finalArrivalTimeTarget = 9 * 60;

        RoutingDimension capacityDimension = routing.getDimensionOrDie("Capacity");

        // 사용될 버스의 수만큼 동적으로 색상 목록을 생성합니다.
        int totalUsedVehicles = 0;
        for (int i = 0; i < data.numVehicles; ++i) {
            if (!routing.isEnd(solution.value(routing.nextVar(routing.start(i))))) {
                totalUsedVehicles++;
            }
        }
        List<String> colors = generateDistinctColors(totalUsedVehicles);

        for (int i = 0; i < data.numVehicles; ++i) {
            long index = routing.start(i);
            if (routing.isEnd(solution.value(routing.nextVar(index)))) {
                continue;
            }

            usedVehiclesCount++;
            List<StopDto> routePathForDto = new ArrayList<>();


            // 1. 먼저 '총 운행 시간' (차고지 출발 ~ 복귀)을 계산합니다.
            long totalRouteTime = 0;
            long tempIndex = routing.start(i);
            long previousIndex;
            while (!routing.isEnd(tempIndex)) {
                previousIndex = tempIndex;
                tempIndex = solution.value(routing.nextVar(tempIndex));
                totalRouteTime += routing.getArcCostForVehicle(previousIndex, tempIndex, i);
            }

            // 2. '차고지 -> 첫 경유지'까지의 이동 시간을 별도로 계산합니다.
            long timeFromDepotToFirstStop = 0;
            long startIndex = routing.start(i);
            // 만약 이 버스가 경유지를 하나 이상 방문한다면,
            if (!routing.isEnd(solution.value(routing.nextVar(startIndex)))) {
                // 첫 번째 경유지의 인덱스를 가져옵니다.
                long firstStopNodeIndex = solution.value(routing.nextVar(startIndex));
                // 차고지에서 첫 경유지까지의 이동 시간을 구합니다.
                timeFromDepotToFirstStop = routing.getArcCostForVehicle(startIndex, firstStopNodeIndex, i);
            }

            // 3. '총 운행 시간'에서 '차고지 -> 첫 경유지' 시간을 빼서, 최종 '서비스 시간'을 계산합니다.
            long serviceTime = totalRouteTime - timeFromDepotToFirstStop;

            // -----------------------------------------------------------

            // 도착 시간 역산을 위한 출발 시간 계산 (전체 운행 시간 기준)
            long departureTime = finalArrivalTimeTarget - totalRouteTime;

            // 다시 경로를 순회하며, 각 정류장의 도착 시각을 계산합니다.
            index = routing.start(i);
            long accumulatedTime = departureTime;

            while (!routing.isEnd(index)) {
                int nodeIndex = manager.indexToNode(index);
                VirtualStop vStop = data.virtualStops.get(nodeIndex);
                long currentLoad = solution.value(capacityDimension.cumulVar(index));

                StopDto stopDto = new StopDto(vStop.originalId, vStop.name, vStop.demand, vStop.lat, vStop.lon);
                stopDto.setArrivalTime(accumulatedTime);
                stopDto.setCurrentLoad(currentLoad);
                routePathForDto.add(stopDto);

                previousIndex = index;
                index = solution.value(routing.nextVar(index));
                accumulatedTime += routing.getArcCostForVehicle(previousIndex, index, i);
            }

            VirtualStop lastStop = data.virtualStops.get(manager.indexToNode(index));
            StopDto lastStopDto = new StopDto(lastStop.originalId, lastStop.name, lastStop.demand, lastStop.lat, lastStop.lon);
            lastStopDto.setArrivalTime(finalArrivalTimeTarget);
            routePathForDto.add(lastStopDto);

            long finalLoad = solution.value(capacityDimension.cumulVar(routing.end(i)));
            String routeColor = colors.get(usedVehiclesCount - 1);

            // DTO에는 총 운행 시간이 아닌, 계산된 '서비스 시간'을 담습니다.
            busRoutes.add(new BusRouteDto(usedVehiclesCount, routePathForDto, serviceTime, finalLoad, routeColor));
        }

        return new RouteSolutionDto(solution.objectiveValue(), usedVehiclesCount, busRoutes);
    }


    private List<String> generateDistinctColors(int count) {
        List<String> colors = new ArrayList<>();
        if (count <= 0) return colors;
        for (int i = 0; i < count; i++) {
            float hue = (float) i / count;
            Color color = Color.getHSBColor(hue, 0.9f, 0.8f);
            String hexColor = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
            colors.add(hexColor);
        }
        return colors;
    }
}