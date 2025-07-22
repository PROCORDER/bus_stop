package com.riansoft.bus_tsp.dto;

import java.util.List;

// 버스 한 대의 수정된 경로 정보를 담는 DTO
public class ModifiedRouteDto {
    private int busId;
    private List<StopDto> newRoute;

    // Getters and Setters
    public int getBusId() { return busId; }
    public void setBusId(int busId) { this.busId = busId; }
    public List<StopDto> getNewRoute() { return newRoute; }
    public void setNewRoute(List<StopDto> newRoute) { this.newRoute = newRoute; }
}