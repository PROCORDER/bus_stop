package com.riansoft.bus_tsp.model;

import java.util.List;

public class DataModel {
    public final long[][] timeMatrix;
    public final List<VirtualStop> virtualStops;
    public final int numVehicles;
    public final long[] vehicleCapacities;
    public final int depotIndex = 0;

    public DataModel(long[][] timeMatrix, List<VirtualStop> virtualStops, int numVehicles, long[] vehicleCapacities) {
        this.timeMatrix = timeMatrix;
        this.virtualStops = virtualStops;
        this.numVehicles = numVehicles;
        this.vehicleCapacities = vehicleCapacities;
    }
}