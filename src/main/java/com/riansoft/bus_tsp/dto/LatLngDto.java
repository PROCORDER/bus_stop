package com.riansoft.bus_tsp.dto;

public class LatLngDto {
    private double lat;
    private double lng; // 카카오맵 JavaScript API는 경도를 lng로 사용하므로 통일

    public LatLngDto() {}

    public LatLngDto(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
    }

    // --- Getters and Setters ---
    public double getLat() {
        return lat;
    }
    public void setLat(double lat) {
        this.lat = lat;
    }
    public double getLng() {
        return lng;
    }
    public void setLng(double lng) {
        this.lng = lng;
    }
}