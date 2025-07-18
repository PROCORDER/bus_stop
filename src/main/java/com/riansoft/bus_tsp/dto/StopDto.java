package com.riansoft.bus_tsp.dto;

import java.util.Objects;

public class StopDto {
    private String id;
    private String name;
    private long demand;
    private double lat;
    private double lon;
    private long arrivalTime;
    private long currentLoad;

    // 1. 기본 생성자
    public StopDto() {}

    // 2. SolutionFormatterService에서 경로 데이터를 담을 때 사용하는 생성자
    public StopDto(String id, String name, long demand, double lat, double lon) {
        this.id = id;
        this.name = name;
        this.demand = demand;
        this.lat = lat;
        this.lon = lon;
    }

    // 3. SolutionFormatterService에서 지도 마커 중복 제거용으로 잠시 사용하는 생성자
    public StopDto(String id, String name, double lat, double lon) {
        this.id = id;
        this.name = name;
        this.lat = lat;
        this.lon = lon;
    }

    // --- Getters and Setters ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getDemand() { return demand; }
    public void setDemand(long demand) { this.demand = demand; }
    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }
    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }
    public long getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(long arrivalTime) { this.arrivalTime = arrivalTime; }
    public long getCurrentLoad() { return currentLoad; }
    public void setCurrentLoad(long currentLoad) { this.currentLoad = currentLoad; }

    // distinct()를 위한 equals() and hashCode() 재정의
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StopDto stopDto = (StopDto) o;
        return Double.compare(stopDto.lat, lat) == 0 && Double.compare(stopDto.lon, lon) == 0 && Objects.equals(id, stopDto.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, lat, lon);
    }
}