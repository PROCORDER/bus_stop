package com.riansoft.bus_tsp.controller;

import com.riansoft.bus_tsp.dto.RouteSolutionDto;
import com.riansoft.bus_tsp.service.RouteOptimizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping; // PostMapping -> GetMapping
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}