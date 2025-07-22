package com.riansoft.bus_tsp.dto;

import java.util.List;

// 수정된 모든 경로 목록을 한번에 담는 최상위 DTO
public class RouteModificationRequestDto {
    private List<ModifiedRouteDto> modifications;

    // Getters and Setters
    public List<ModifiedRouteDto> getModifications() { return modifications; }
    public void setModifications(List<ModifiedRouteDto> modifications) { this.modifications = modifications; }
}