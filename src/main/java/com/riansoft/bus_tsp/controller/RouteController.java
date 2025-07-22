package com.riansoft.bus_tsp.controller;

import com.riansoft.bus_tsp.dto.ModifiedRouteDto;
import com.riansoft.bus_tsp.dto.RouteModificationRequestDto;
import com.riansoft.bus_tsp.dto.RouteSolutionDto;
import com.riansoft.bus_tsp.service.RouteOptimizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class RouteController {

    private final RouteOptimizationService routeService;

    @Autowired
    public RouteController(RouteOptimizationService routeService) {
        this.routeService = routeService;
    }

    @GetMapping("/optimize-route")
    public ResponseEntity<RouteSolutionDto> getOptimalRoutes() {
        RouteSolutionDto solution = routeService.findOptimalRoutes();
        if (solution == null) {
            return ResponseEntity.internalServerError().body(null);
        }
        return ResponseEntity.ok(solution);
    }

    @PostMapping("/apply-edits")
    public ResponseEntity<String> applyEdits(@RequestBody RouteModificationRequestDto request) {
        System.out.println("\n========= [CONTROLLER LOG] 수신된 경로 수정사항 분석 시작 (DTO 방식) ==========");
        if (request == null || request.getModifications() == null || request.getModifications().isEmpty()) {
            System.out.println("[LOG] 수신된 수정사항이 없습니다.");
        } else {
            for (ModifiedRouteDto modification : request.getModifications()) {
                System.out.printf("  [수정된 버스 ID]: %d%n", modification.getBusId());
                String newRoutePath = modification.getNewRoute().stream()
                        .map(stop -> stop.getName())
                        .collect(Collectors.joining(" -> "));
                System.out.printf("  [새로운 경로]: %s%n", newRoutePath);
                System.out.println("  ----------------------------------------------------");
            }
        }
        System.out.println("========= [CONTROLLER LOG] 경로 수정사항 분석 완료 ==========\n");

        return ResponseEntity.ok("수정 사항이 성공적으로 서버에 접수되었습니다.");
    }
}