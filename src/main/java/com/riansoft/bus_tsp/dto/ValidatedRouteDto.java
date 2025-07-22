package com.riansoft.bus_tsp.dto;

import java.util.List;

// 계산 및 검증이 완료된 경로의 상세 정보를 담는 DTO
public class ValidatedRouteDto {
    private int busId;
    private List<StopDto> newRoute;
    private long calculatedRouteTime;
    private long calculatedFinalLoad;
    private boolean capacityValid;
    private String validationMessage;

    // Getters and Setters
    public int getBusId() { return busId; }
    public void setBusId(int busId) { this.busId = busId; }
    public List<StopDto> getNewRoute() { return newRoute; }
    public void setNewRoute(List<StopDto> newRoute) { this.newRoute = newRoute; }
    public long getCalculatedRouteTime() { return calculatedRouteTime; }
    public void setCalculatedRouteTime(long calculatedRouteTime) { this.calculatedRouteTime = calculatedRouteTime; }
    public long getCalculatedFinalLoad() { return calculatedFinalLoad; }
    public void setCalculatedFinalLoad(long calculatedFinalLoad) { this.calculatedFinalLoad = calculatedFinalLoad; }
    public boolean isCapacityValid() { return capacityValid; }
    public void setCapacityValid(boolean capacityValid) { this.capacityValid = capacityValid; }
    public String getValidationMessage() { return validationMessage; }
    public void setValidationMessage(String validationMessage) { this.validationMessage = validationMessage; }
}