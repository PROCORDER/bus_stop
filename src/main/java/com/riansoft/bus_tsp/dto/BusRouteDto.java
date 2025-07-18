package com.riansoft.bus_tsp.dto;

import java.util.List;

public class BusRouteDto {
    private int busId;
    private List<StopDto> route;
    private long routeTime;
    private long finalLoad;
    private String color;
    private List<List<LatLngDto>> detailedPath; // [핵심 추가] 상세 경로 좌표 목록

    public BusRouteDto() {}

    public BusRouteDto(int busId, List<StopDto> route, long routeTime, long finalLoad, String color, List<List<LatLngDto>> detailedPath) {
        this.busId = busId;
        this.route = route;
        this.routeTime = routeTime;
        this.finalLoad = finalLoad;
        this.color = color;
        this.detailedPath = detailedPath;
    }

    // --- Getters and Setters (detailedPath 추가) ---
    public int getBusId() { return busId; }
    public void setBusId(int busId) { this.busId = busId; }
    public List<StopDto> getRoute() { return route; }
    public void setRoute(List<StopDto> route) { this.route = route; }
    public long getRouteTime() { return routeTime; }
    public void setRouteTime(long routeTime) { this.routeTime = routeTime; }
    public long getFinalLoad() { return finalLoad; }
    public void setFinalLoad(long finalLoad) { this.finalLoad = finalLoad; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public List<List<LatLngDto>> getDetailedPath() { return detailedPath; }
    public void setDetailedPath(List<List<LatLngDto>> detailedPath) { this.detailedPath = detailedPath; }
}