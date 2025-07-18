package com.riansoft.bus_tsp.model;

public class VirtualStop {
    public final String originalId;
    public final String name;
    public final long demand;
    public final double lat;
    public final double lon;

    public VirtualStop(String originalId, String name, long demand, double lat, double lon) {
        this.originalId = originalId;
        this.name = name;
        this.demand = demand;
        this.lat = lat;
        this.lon = lon;
    }
}