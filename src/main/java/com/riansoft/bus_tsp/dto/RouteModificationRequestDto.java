package com.riansoft.bus_tsp.dto;

import java.util.List;
import java.util.Map;
// 수정된 모든 경로 목록을 한번에 담는 최상위 DTO
public class RouteModificationRequestDto {
    private List<ModifiedRouteDto> modifications;
    private Map<String, String> params;



    public Map<String, String> getParams() {
        return params;
    }

    public void setParams(Map<String, String> params) {
        this.params = params;
    }
    // Getters and Setters
    public List<ModifiedRouteDto> getModifications() { return modifications; }
    public void setModifications(List<ModifiedRouteDto> modifications) { this.modifications = modifications; }
}