package com.riansoft.bus_tsp.model;

public class PhysicalStop {
    public final String id;
    public final String name;
    public final long demand;
    public final double lat;
    public final double lon;

    public PhysicalStop(String id, String name, long demand, double lat, double lon) {
        this.id = id;
        this.name = name;
        this.demand = demand;
        this.lat = lat;
        this.lon = lon;
    }
}