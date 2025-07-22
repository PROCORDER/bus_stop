package com.riansoft.bus_tsp.controller;

import com.riansoft.bus_tsp.dto.ModifiedRouteDto;
import com.riansoft.bus_tsp.dto.RouteModificationRequestDto;
import com.riansoft.bus_tsp.dto.RouteSolutionDto;
import com.riansoft.bus_tsp.dto.ValidatedRouteDto;
import com.riansoft.bus_tsp.service.RouteOptimizationService;
import com.riansoft.bus_tsp.service.RouteValidationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class RouteController {

    private final RouteOptimizationService routeService;
    private final RouteValidationService validationService;

    @Autowired
    public RouteController(RouteOptimizationService routeService, RouteValidationService validationService) {
        this.routeService = routeService;
        this.validationService = validationService;
    }

    /**
     * 최초의 최적 경로를 계산하여 반환합니다.
     */
    @GetMapping("/optimize-route")
    public ResponseEntity<RouteSolutionDto> getOptimalRoutes() {
        // 이 코드는 삭제되지 않고 그대로 존재합니다.
        RouteSolutionDto solution = routeService.findOptimalRoutes();
        if (solution == null) {
            return ResponseEntity.internalServerError().body(null);
        }
        return ResponseEntity.ok(solution);
    }

    /**
     * 프론트엔드에서 수정된 경로 목록을 받아 검증하고 로그를 출력합니다.
     */
    @PostMapping("/apply-edits")
    public ResponseEntity<String> applyEdits(@RequestBody RouteModificationRequestDto request) {
        System.out.println("\n========= [CONTROLLER LOG] 수신된 경로 수정사항 검증 및 계산 시작 ==========");
        if (request == null || request.getModifications() == null || request.getModifications().isEmpty()) {
            System.out.println("[LOG] 수신된 수정사항이 없습니다.");
        } else {
            for (ModifiedRouteDto modification : request.getModifications()) {
                ValidatedRouteDto result = validationService.validateAndCalculate(modification);

                System.out.printf("  [수정된 버스 ID]: %d%n", result.getBusId());
                String newRoutePath = result.getNewRoute().stream()
                        .map(stop -> stop.getName())
                        .collect(Collectors.joining(" -> "));
                System.out.printf("  [새로운 경로]: %s%n", newRoutePath);
                System.out.printf("  [계산된 서비스 시간]: %d분%n", result.getCalculatedRouteTime());
                System.out.printf("  [계산된 총 탑승인원]: %d명%n", result.getCalculatedFinalLoad());
                System.out.printf("  [정원 초과 여부]: %s (%s)%n", !result.isCapacityValid(), result.getValidationMessage());
                System.out.println("  ----------------------------------------------------");
            }
        }
        System.out.println("========= [CONTROLLER LOG] 경로 수정사항 검증 및 계산 완료 ==========\n");

        return ResponseEntity.ok("수정 사항이 성공적으로 서버에서 처리되었습니다.");
    }
}