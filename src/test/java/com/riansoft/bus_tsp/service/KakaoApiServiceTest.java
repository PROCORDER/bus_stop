package com.riansoft.bus_tsp.service;

import com.riansoft.bus_tsp.model.VirtualStop; // RouteOptimizationService의 내부 클래스를 가져옴
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 Kakao API와의 연동을 테스트하는 클래스입니다.
 * 이 테스트를 실행하려면 application.properties에 유효한 kakao.api.key가 설정되어 있어야 합니다.
 */
@SpringBootTest
public class KakaoApiServiceTest {

    // 테스트 대상인 KakaoApiService를 스프링 컨테이너로부터 주입받습니다.
    @Autowired
    private KakaoApiService kakaoApiService;

    @Test
    @DisplayName("좌표 목록으로 실제 카카오 API를 호출하여 유효한 시간 행렬을 생성한다")
    void whenGivenCoordinates_thenCreatesTimeMatrixFromApi() {
        // --- [수정된 부분] ---
        // 1. 서울시 전체에서 랜덤으로 추출한 30개의 정류장 목록을 준비합니다.
        List<VirtualStop> testStops = createRandomSeoulStops();

        // 2. 서비스 메소드 호출
        // KakaoApiService의 핵심 기능을 직접 실행합니다.
        long[][] timeMatrix = kakaoApiService.createTimeMatrixFromApi(testStops);

        // 3. 결과 검증 (Assertions)
        // 계산 결과가 유효한지 코드로 직접 확인합니다.

        // 검증 1: 결과 행렬이 null이 아니어야 한다.
        assertNotNull(timeMatrix, "시간 행렬은 null이 될 수 없습니다.");

        // 검증 2: 행렬의 크기가 (정류장 개수 x 정류장 개수)와 일치해야 한다.
        assertEquals(testStops.size(), timeMatrix.length, "행렬의 행 개수가 정류장 수와 일치해야 합니다.");
        assertEquals(testStops.size(), timeMatrix[0].length, "행렬의 열 개수가 정류장 수와 일치해야 합니다.");

        // 검증 3: 대각선 요소(자기 자신에게 가는 시간)는 0이어야 한다.
        for(int i = 0; i < timeMatrix.length; i++){
            assertEquals(0, timeMatrix[i][i], "자기 자신에게 가는 시간은 0이어야 합니다.");
        }

        // 검증 4: 차고지(0)에서 첫 번째 랜덤 정류장(1)까지의 이동 시간은 0보다 커야 한다 (경로가 존재해야 함).
        assertTrue(timeMatrix[0][1] > 0, "차고지에서 첫 번째 정류장까지의 이동 시간은 0보다 커야 합니다.");

        // (선택 사항) 생성된 행렬을 눈으로 직접 확인
        System.out.println("--- API 호출을 통해 생성된 시간 행렬 ---");
        System.out.println("      (총 " + testStops.size() + "x" + testStops.size() + " 크기)");
        for (long[] row : timeMatrix) {
            for (long val : row) {
                System.out.printf("%4d", val);
            }
            System.out.println();
        }
    }

    /**
     * 서울시 버스정류장 데이터에서 랜덤으로 30개를 추출하여 테스트용 목록을 생성합니다.
     * @return VirtualStop 객체 리스트
     */
    private List<VirtualStop> createRandomSeoulStops() {
        List<VirtualStop> stops = new ArrayList<>();
        Random random = new Random();

        // 차고지 정보는 고정으로 추가
        stops.add(new VirtualStop("DEPOT", "쿠팡 물류 1센터", 0,  37.488587 , 127.128932 ));

        // 서울시 전체에서 완전히 랜덤으로 선택된 30개의 버스 정류장 정보
        stops.add(new VirtualStop("22339", "사당역", random.nextInt(21) + 5, 37.476549, 126.981685));
        stops.add(new VirtualStop("12111", "마포구청.마포장애인종합복지관", random.nextInt(21) + 5, 37.56345, 126.90842));
        stops.add(new VirtualStop("03189", "서울지방병무청", random.nextInt(21) + 5, 37.52594, 126.96542));
        stops.add(new VirtualStop("17270", "구로디지털단지역", random.nextInt(21) + 5, 37.485303, 126.901597));
        stops.add(new VirtualStop("07117", "강북구청", random.nextInt(21) + 5, 37.639233, 127.025497));
        stops.add(new VirtualStop("11363", "월드컵파크3단지.난지천공원", random.nextInt(21) + 5, 37.573519, 126.89886));
        stops.add(new VirtualStop("14115", "신촌오거리.2호선신촌역", random.nextInt(21) + 5, 37.55523, 126.93699));
        stops.add(new VirtualStop("23204", "잠실역.롯데월드", random.nextInt(21) + 5, 37.51139, 127.09812));
        stops.add(new VirtualStop("01140", "경복궁역", random.nextInt(21) + 5, 37.576023, 126.973423));
        stops.add(new VirtualStop("19195", "노량진역", random.nextInt(21) + 5, 37.513512, 126.942441));
        stops.add(new VirtualStop("10165", "창동역서측", random.nextInt(21) + 5, 37.653457, 127.047558));
        stops.add(new VirtualStop("25239", "강동구청역", random.nextInt(21) + 5, 37.530368, 127.123354));
        stops.add(new VirtualStop("18105", "영등포역", random.nextInt(21) + 5, 37.51613, 126.90748));
        stops.add(new VirtualStop("09117", "수유(강북구청)역", random.nextInt(21) + 5, 37.638799, 127.025719));
//        stops.add(new VirtualStop("06169", "청량리역환승센터", random.nextInt(21) + 5, 37.58022, 127.04828));
//        stops.add(new VirtualStop("24131", "삼성역", random.nextInt(21) + 5, 37.50882, 127.06283));
//        stops.add(new VirtualStop("20131", "관악구청", random.nextInt(21) + 5, 37.47648, 126.95191));
//        stops.add(new VirtualStop("05167", "마장역", random.nextInt(21) + 5, 37.569947, 127.0398));
//        stops.add(new VirtualStop("13111", "연세대학교앞", random.nextInt(21) + 5, 37.563209, 126.936411));
//        stops.add(new VirtualStop("16103", "가산디지털단지역", random.nextInt(21) + 5, 37.48154, 126.88295));
//        stops.add(new VirtualStop("08112", "상봉역.중랑우체국", random.nextInt(21) + 5, 37.596489, 127.085188));
//        stops.add(new VirtualStop("15101", "구로역.AK플라자", random.nextInt(21) + 5, 37.50294, 126.88358));
//        stops.add(new VirtualStop("21132", "보라매병원", random.nextInt(21) + 5, 37.49363, 126.92446));
//        stops.add(new VirtualStop("04122", "충무로역8번출구.대한극장앞", random.nextInt(21) + 5, 37.56133, 126.9942));
//        stops.add(new VirtualStop("03117", "용산역", random.nextInt(21) + 5, 37.52985, 126.96422));
//        stops.add(new VirtualStop("10103", "도봉산역광역환승센터", random.nextInt(21) + 5, 37.689625, 127.04423));
//        stops.add(new VirtualStop("11111", "월계역", random.nextInt(21) + 5, 37.625798, 127.06455));
//        stops.add(new VirtualStop("23297", "강남역", random.nextInt(21) + 5, 37.49793, 127.02749));
//        stops.add(new VirtualStop("19141", "여의도역", random.nextInt(21) + 5, 37.52125, 126.92423));

        return stops;
    }
}