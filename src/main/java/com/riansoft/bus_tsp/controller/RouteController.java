package com.riansoft.bus_tsp.controller;

import com.riansoft.bus_tsp.dto.*;
import com.riansoft.bus_tsp.service.RouteOptimizationService;
import com.riansoft.bus_tsp.service.RouteValidationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.riansoft.bus_tsp.service.StopDataService;


import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")

public class RouteController {

    private final RouteOptimizationService routeService;
    private final RouteValidationService validationService;
    private final StopDataService stopDataService;

    @Autowired
    public RouteController(RouteOptimizationService routeService, RouteValidationService validationService,StopDataService stopDataService) {
        this.routeService = routeService;
        this.validationService = validationService;
        this.stopDataService = stopDataService;
    }

    /**
     * 최초의 최적 경로를 계산하여 반환합니다.
     */
    @GetMapping("/optimize-route")
    public ResponseEntity<RouteSolutionDto> getOptimalRoutes(
            // 쿼리 파라미터를 받도록 어노테이션 추가
            @RequestParam long timeLimit,
            @RequestParam int capacity,
            @RequestParam long serviceTime,
            @RequestParam String dbName) {
        // 서비스 호출 시 파라미터 전달
        RouteSolutionDto solution = routeService.findOptimalRoutes(timeLimit, capacity, serviceTime, dbName);
        if (solution == null) {
            return ResponseEntity.internalServerError().body(null);
        }
        return ResponseEntity.ok(solution);
    }

    /**
     * 프론트엔드에서 수정된 경로 목록을 받아 검증하고 '로그만 출력'합니다.
     */
    @PostMapping("/apply-edits")
    public ResponseEntity<String> applyEdits(@RequestBody RouteModificationRequestDto request) {
        System.out.println("\n========= [CONTROLLER LOG] 수신된 경로 수정사항 검증 및 계산 시작 ==========");

        // 요청으로부터 파라미터를 추출합니다.
        Map<String, String> params = request.getParams();
        int capacity = 45; // 기본값 설정
        String dbName = "INC4.csv"; // 기본값 설정

        if (params != null) {
            if (params.containsKey("capacity")) {
                capacity = Integer.parseInt(params.get("capacity"));
            }
            if (params.containsKey("dbName")) {
                dbName = params.get("dbName"); // dbName 추출
            }
        }

        if (request == null || request.getModifications() == null || request.getModifications().isEmpty()) {
            System.out.println("[LOG] 수신된 수정사항이 없습니다.");
        } else {
            for (ModifiedRouteDto modification : request.getModifications()) {
                // validateAndCalculate 호출 시 dbName 인수를 함께 전달합니다.
                ValidatedRouteDto result = validationService.validateAndCalculate(modification, capacity, dbName);

                System.out.printf("  [수정된 버스 ID]: %d%n", result.getBusId());
                String newRoutePath = result.getNewRoute().stream().map(StopDto::getName).collect(Collectors.joining(" -> "));
                System.out.printf("  [새로운 경로]: %s%n", newRoutePath);
                System.out.printf("  [계산된 서비스 시간]: %d분%n", result.getCalculatedRouteTime());
                System.out.printf("  [계산된 총 탑승인원]: %d명%n", result.getCalculatedFinalLoad());
                System.out.printf("  [정원 초과 여부]: %s (%s)%n", !result.isCapacityValid(), result.getValidationMessage());
                System.out.println("  ----------------------------------------------------");
            }
        }
        System.out.println("========= [CONTROLLER LOG] 경로 수정사항 검증 및 계산 완료 ==========\n");
        return ResponseEntity.ok("수정 사항이 성공적으로 서버에서 처리(로그 출력)되었습니다.");
    }

    /**
     * [핵심 추가] 프론트엔드에서 고정된 경로를 받아 '재계산'하고, '새로운 RouteSolutionDto를 반환'합니다.
     */
    @PostMapping("/re-optimize")
    public ResponseEntity<RouteSolutionDto> reOptimizeRoutes(@RequestBody RouteModificationRequestDto request) {
        // DTO에서 파라미터 추출
        Map<String, String> params = request.getParams();
        long timeLimit = Long.parseLong(params.get("timeLimit"));
        int capacity = Integer.parseInt(params.get("capacity"));
        long serviceTime = Long.parseLong(params.get("serviceTime"));
        String dbName = params.get("dbName");

        // 서비스 호출 시 파라미터 전달
        RouteSolutionDto newSolution = routeService.reOptimizeWithConstraints(request, timeLimit, capacity, serviceTime, dbName);
        if (newSolution == null) {
            return ResponseEntity.internalServerError().body(null);
        }
        return ResponseEntity.ok(newSolution);
    }

    @GetMapping("/all-stops")
    public ResponseEntity<List<StopDto>> getAllStops(@RequestParam String dbName) {
        System.out.printf("연결이 들어왔습니다.");
        List<StopDto> allStops = stopDataService.getAllStopsAsDto(dbName);
        return ResponseEntity.ok(allStops);
    }
}