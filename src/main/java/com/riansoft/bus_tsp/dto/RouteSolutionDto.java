package com.riansoft.bus_tsp.dto;

import java.util.List;

public class RouteSolutionDto {

    private int usedBuses;
    private List<BusRouteDto> busRoutes;

    // 1. 기본 생성자
    public RouteSolutionDto() {}

    // 2. SolutionFormatterService에서 최종 결과를 담을 때 사용하는 생성자
    public RouteSolutionDto( int usedBuses, List<BusRouteDto> busRoutes) {

        this.usedBuses = usedBuses;
        this.busRoutes = busRoutes;
    }
    // --- Getters and Setters ---



    public int getUsedBuses() {
        return usedBuses;
    }

    public void setUsedBuses(int usedBuses) {
        this.usedBuses = usedBuses;
    }

    public List<BusRouteDto> getBusRoutes() {
        return busRoutes;
    }

    public void setBusRoutes(List<BusRouteDto> busRoutes) {
        this.busRoutes = busRoutes;
    }
}