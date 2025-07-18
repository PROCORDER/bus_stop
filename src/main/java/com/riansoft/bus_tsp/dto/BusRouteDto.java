package com.riansoft.bus_tsp.dto;

import java.util.List;

public class BusRouteDto {
    private int busId;
    private List<StopDto> route;
    private long routeTime;
    private long finalLoad;
    private String color;

    public BusRouteDto() {}

    public BusRouteDto(int busId, List<StopDto> route, long routeTime, long finalLoad, String color) {
        this.busId = busId;
        this.route = route;
        this.routeTime = routeTime;
        this.finalLoad = finalLoad;
        this.color = color;
    }

    // --- Getters and Setters ---
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
}