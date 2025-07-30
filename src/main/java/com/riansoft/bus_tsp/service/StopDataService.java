package com.riansoft.bus_tsp.service;

import com.riansoft.bus_tsp.dto.StopDto;
import com.riansoft.bus_tsp.model.PhysicalStop;
import com.riansoft.bus_tsp.model.VirtualStop;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors; // 이 import 구문이 필요합니다.
import java.util.stream.Stream;

@Service
public class StopDataService {




    public List<VirtualStop> getVirtualStops(int vehicleCapacity, String dbName) {
        // 1. CSV 파일을 읽어 PhysicalStop 목록을 가져옵니다.
        List<PhysicalStop> physicalStops = createPhysicalStopsFromCsv(dbName);

        // 2. 수요 분할 로직을 통해 가상 정류장 목록을 생성합니다.
        List<VirtualStop> virtualStops = new ArrayList<>();

        for (PhysicalStop pStop : physicalStops) {
            if (pStop.demand <= vehicleCapacity) {
                virtualStops.add(new VirtualStop(pStop.id, pStop.name, pStop.demand, pStop.lat, pStop.lon));
            } else {
                long remainingDemand = pStop.demand;
                int splitCount = 1;
                while (remainingDemand > 0) {
                    long currentDemand = Math.min(remainingDemand, vehicleCapacity);
                    virtualStops.add(new VirtualStop(
                            pStop.id, pStop.name + "-" + splitCount, currentDemand, pStop.lat, pStop.lon));
                    remainingDemand -= currentDemand;
                    splitCount++;
                }
            }
        }
        return virtualStops;
    }


    private List<PhysicalStop> createPhysicalStopsFromCsv(String dbName) {
        List<PhysicalStop> stops = new ArrayList<>();

        // resources 폴더에서 dbName에 해당하는 파일을 읽어옵니다.
        try (InputStream inputStream = new ClassPathResource(dbName).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            // 1. CSV의 첫 줄을 읽어 도착지(차고지)로 설정합니다.
            String depotLine = reader.readLine();
            if (depotLine != null && !depotLine.trim().isEmpty()) {
                String[] parts = depotLine.trim().split(",");
                if (parts.length >= 3) {
                    String name = parts[0];
                    double lat = Double.parseDouble(parts[1]);
                    double lon = Double.parseDouble(parts[2]);
                    // 차고지의 demand(수요)는 항상 0입니다. ID는 고유하게 설정합니다.
                    stops.add(new PhysicalStop("DEPOT_0", name, 0, lat, lon));
                }
            }

            // 2. 나머지 줄들을 읽어 경유지로 설정합니다.
            String line;
            int counter = 1;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(",");
                // 이름, 위도, 경도, 탑승인원 형식에 맞게 수정 (4개 이상의 필드 확인)
                if (parts.length >= 4) {
                    try {
                        String name = parts[0];
                        double lat = Double.parseDouble(parts[1]);
                        double lon = Double.parseDouble(parts[2]);
                        // [변경] CSV 파일의 네 번째 값을 탑승인원(demand)으로 읽어옵니다.
                        long demand = Math.round(Double.parseDouble(parts[3].trim()));

                        String id = "ST_" + counter++;

                        stops.add(new PhysicalStop(id, name, demand, lat, lon));

                    } catch (NumberFormatException e) {
                        System.err.println("숫자 변환 오류 발생 (해당 라인 건너뜀): " + line);
                    }
                }
            }
        } catch (Exception e) {
            // [변경] 로그 메시지에 동적 파일명(dbName)을 사용합니다.
            System.err.println(dbName + " 파일 읽기 중 오류 발생");
            e.printStackTrace();
            return createDefaultStopsForEmergency();
        }

        // [변경] 로그 메시지에 동적 파일명(dbName)을 사용합니다.
        System.out.println(dbName + " 파일로부터 총 " + (stops.size() - 1) + "개의 정류장을 성공적으로 로드했습니다.");
        return stops;
    }

    /**
     * 파일 읽기 실패 시 사용할 비상용 정류장 데이터입니다.
     */
    private List<PhysicalStop> createDefaultStopsForEmergency() {
        List<PhysicalStop> stops = new ArrayList<>();
        stops.add(new PhysicalStop("DEPOT_YJ", "쿠팡 광주 3센터", 0, 37.347, 127.1965));
        return stops;
    }

    /**
     * 모든 물리적 정류장(차고지 포함) 목록을 DTO 리스트로 변환하여 반환합니다.
     * 지도에 초기 정류장들을 표시하기 위해 사용됩니다.
     */
    public List<StopDto> getAllStopsAsDto(String dbName) {
        System.out.println("[DATA LOG] 모든 정류장 정보 DTO 변환을 시작합니다.");
        List<PhysicalStop> physicalStops = createPhysicalStopsFromCsv(dbName);
        System.out.println(physicalStops);

        // PhysicalStop 목록을 StopDto 목록으로 변환합니다.
        return physicalStops.stream()
                .map(pStop -> new StopDto(pStop.id, pStop.name, pStop.demand, pStop.lat, pStop.lon))
                .collect(Collectors.toList());
    }
}