package com.riansoft.bus_tsp.service;

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

@Service
public class StopDataService {


    public List<VirtualStop> getVirtualStops(int vehicleCapacity) {
        // 1. CSV 파일을 읽어 PhysicalStop 목록을 가져옵니다.
        List<PhysicalStop> physicalStops = createPhysicalStopsFromCsv();

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


    private List<PhysicalStop> createPhysicalStopsFromCsv() {
        List<PhysicalStop> stops = new ArrayList<>();
        Random random = new Random();

        // 차고지는 항상 고정으로 먼저 추가합니다.
        stops.add(new PhysicalStop("DEPOT_YJ", "쿠팡 광주 3센터", 0, 37.347, 127.1965));

        // resources/location.csv 파일을 읽어옵니다.
        try (InputStream inputStream = new ClassPathResource("location2.csv").getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            int counter = 1;
            while ((line = reader.readLine()) != null) {
                // 한 줄을 공백(탭 포함) 기준으로 자릅니다.
                String[] parts = line.trim().split(",");
                if (parts.length >= 3) {
                    try {
                        String name = parts[0];
                        double lat = Double.parseDouble(parts[1]);
                        double lon = Double.parseDouble(parts[2]);

                        // CSV에 ID가 없으므로, ST_1, ST_2 와 같이 고유 ID를 생성합니다.
                        String id = "ST_" + counter++;
                        // 수요량은 5~25 사이의 랜덤 값으로 생성합니다.
                        long demand = random.nextInt(5) + 2;

                        stops.add(new PhysicalStop(id, name, demand, lat, lon));

                    } catch (NumberFormatException e) {
                        System.err.println("숫자 변환 오류 발생 (해당 라인 건너뜀): " + line);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("location2.csv 파일 읽기 중 오류 발생");
            e.printStackTrace();
            // 파일 읽기 실패 시, 비상용 기본 데이터 반환 (선택 사항)
            return createDefaultStopsForEmergency();
        }

        System.out.println("location2.csv 파일로부터 총 " + (stops.size() -1) + "개의 정류장을 성공적으로 로드했습니다.");
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
}