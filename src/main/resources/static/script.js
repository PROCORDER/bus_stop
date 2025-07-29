// --- 1. 전역 변수 선언 ---
let map;
let drawingManager;
let mapOverlays = [];
let currentlyEditing = { busId: null, routeDiv: null, originalStops: [], editedStops: [] };
let stopToAdd = null;
let lockedRoutes = new Map();


function initializeApp() {
    const mapContainer = document.getElementById('map');
    const mapOption = { center: new kakao.maps.LatLng(37.566826, 126.9786567), level: 8 };
    map = new kakao.maps.Map(mapContainer, mapOption);

    // 그리기 도구를 생성만 해둡니다. (활성화 X)
    initDrawingManager();

    // 초기 정류장 마커를 표시합니다.
    fetchAllStopsAndDisplay();

    // 모든 버튼들에 클릭 이벤트를 할당합니다.
    setupEventListeners();
}

/**
 * 카카오맵 SDK 스크립트가 완전히 로드되면 initializeApp 함수를 호출합니다.
 * 이것이 이 앱의 유일한 진입점입니다.
 */
kakao.maps.load(initializeApp);


// --- 3. 핵심 로직 함수 ---

/**
 * 페이지의 모든 버튼에 대한 클릭 이벤트를 설정합니다.
 */
function setupEventListeners() {
 const startDrawingBtn = document.getElementById('start-drawing-btn');
    const cancelDrawingBtn = document.getElementById('cancel-drawing-btn');

    if (startDrawingBtn && cancelDrawingBtn) {
        startDrawingBtn.onclick = function() {
            if (drawingManager) {
                drawingManager.select(kakao.maps.drawing.OverlayType.POLYGON);
                startDrawingBtn.style.display = 'none';
                cancelDrawingBtn.style.display = 'inline-block';
            }
        };

        cancelDrawingBtn.onclick = function() {
            if (drawingManager) {
                drawingManager.cancel();
                startDrawingBtn.style.display = 'inline-block';
                cancelDrawingBtn.style.display = 'none';
            }
        };
    }


    const startBtn = document.getElementById('start-optimization-btn');
    startBtn.onclick = function() {
        document.getElementById('status').innerText = '경로 계산 중...';
        startBtn.disabled = true;
        clearMap();

        fetch('/api/optimize-route')
            .then(response => {
                if (!response.ok) throw new Error('서버 응답 오류: ' + response.status);
                return response.json();
            })
            .then(data => {
                if (!data || !data.busRoutes || data.busRoutes.length === 0) {
                    document.getElementById('status').innerText = '오류: 서버에서 해답을 찾지 못했습니다.';
                    startBtn.disabled = false;
                    fetchAllStopsAndDisplay(); // 실패 시 초기 마커 다시 표시
                    return;
                }
                startBtn.style.display = 'none';
                document.getElementById('status').innerHTML = `<h3>총 운행 정보</h3><p>필요 버스: ${data.usedBuses}대 | 전체 목표 비용: ${data.totalObjectiveTime}</p><hr>`;
                drawRoutesAndInfo(data.busRoutes);
            })
            .catch(error => {
                document.getElementById('status').innerText = '경로 계산 중 오류가 발생했습니다.';
                startBtn.disabled = false;
                fetchAllStopsAndDisplay(); // 실패 시 초기 마커 다시 표시
                console.error('Error:', error);
            });
    };

    const finalizeAllBtn = document.getElementById('finalize-all-btn');
    finalizeAllBtn.onclick = function() {
        if (lockedRoutes.size === 0) {
            alert('고정된 경로가 없습니다.');
            return;
        }
        const payload = { modifications: Array.from(lockedRoutes.entries()).map(([busId, stops]) => ({ busId, newRoute: stops })) };
        document.getElementById('status').innerText = '재계산 중...';
        finalizeAllBtn.disabled = true;
        clearMap();

        fetch('/api/re-optimize', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        })
        .then(response => { if (!response.ok) throw new Error('재계산 실패: ' + response.status); return response.json(); })
        .then(newSolution => {
            alert('재계산이 완료되었습니다. 새로운 경로를 표시합니다.');
            lockedRoutes.clear();
            finalizeAllBtn.style.display = 'none';
            finalizeAllBtn.disabled = false;
            document.getElementById('status').innerHTML = `<h3>총 운행 정보</h3><p>필요 버스: ${newSolution.usedBuses}대 | 전체 목표 비용: ${newSolution.totalObjectiveTime}</p><hr>`;
            drawRoutesAndInfo(newSolution.busRoutes);
        })
        .catch(error => {
            alert('오류: ' + error.message);
            document.getElementById('status').innerText = '재계산 중 오류 발생';
            finalizeAllBtn.disabled = false;
            fetchAllStopsAndDisplay(); // 실패 시 초기 마커 다시 표시
        });
    };

    const getDataBtn = document.getElementById('get-drawing-data-btn');
    if (getDataBtn) {
        getDataBtn.onclick = function() {
            if (!drawingManager) {
                alert('그리기 도구가 아직 활성화되지 않았습니다.');
                return;
            }
            const data = drawingManager.getData();
            const resultDiv = document.getElementById('result');
            let resultText = '';
            const polygons = data[kakao.maps.drawing.OverlayType.POLYGON];
            if (polygons.length === 0) {
                resultText = '지도에 그려진 폴리곤이 없습니다.';
            } else {
                polygons.forEach((polygon, index) => {
                    resultText += `[ 폴리곤 #${index + 1} ]\n`;
                    const points = polygon.getPoints()[0];
                    points.forEach((point, i) => {
                        resultText += `  - 꼭짓점 ${i + 1}: (위도: ${point.y}, 경도: ${point.x})\n`;
                    });
                    resultText += '\n';
                });
            }
            resultDiv.textContent = resultText;
        };
    }

    // 그리기 시작 및 취소 버튼 이벤트 핸들러


    if (startDrawingBtn && cancelDrawingBtn) {
        startDrawingBtn.onclick = function() {
            if (drawingManager) {
                drawingManager.select(kakao.maps.drawing.OverlayType.POLYGON);
                startDrawingBtn.style.display = 'none';
                cancelDrawingBtn.style.display = 'inline-block';
            }
        };

        cancelDrawingBtn.onclick = function() {
            if (drawingManager) {
                drawingManager.cancel();
                startDrawingBtn.style.display = 'inline-block';
                cancelDrawingBtn.style.display = 'none';
            }
        };
    }

    document.getElementById('confirm-add-btn').onclick = function() {
        const select = document.getElementById('insert-before-select');
        const insertBeforeId = select.value;
        const insertIndex = currentlyEditing.editedStops.findIndex(s => s.id === insertBeforeId);
        if (insertIndex !== -1 && stopToAdd) {
            currentlyEditing.editedStops.splice(insertIndex, 0, stopToAdd);
            redrawStopList(currentlyEditing.routeDiv.querySelector('.stop-list'), currentlyEditing.editedStops, true);
        }
        hideAddStopModal();
    };

    document.getElementById('cancel-add-btn').onclick = hideAddStopModal;
}


/**
 * 서버에서 모든 정류장 목록을 가져와 지도에 마커로 표시합니다.
 */
function fetchAllStopsAndDisplay() {
    fetch('/api/all-stops')
        .then(response => response.json())
        .then(stops => {
            console.log(stops.length + "개의 정류장 정보를 불러왔습니다.");
            const bounds = new kakao.maps.LatLngBounds();
            stops.forEach(stop => {
                const position = new kakao.maps.LatLng(stop.lat, stop.lon);
                const isDepot = stop.id.startsWith("DEPOT");
                const markerImageSrc = isDepot ?
                    'https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/marker_red.png' :
                    'https://t1.daumcdn.net/mapjsapi/images/marker.png';
                const imageSize = isDepot ? new kakao.maps.Size(33, 36) : new kakao.maps.Size(24, 35);
                const markerImage = new kakao.maps.MarkerImage(markerImageSrc, imageSize);
                const marker = new kakao.maps.Marker({ map: map, position: position, title: stop.name, image: markerImage });
                const infowindow = new kakao.maps.InfoWindow({ content: `<div style="padding:5px;font-size:12px;">${stop.name}</div>`, removable: true });

                kakao.maps.event.addListener(marker, 'click', function() { infowindow.open(map, marker); });

                mapOverlays.push(marker);
                bounds.extend(position);
            });
            if (stops.length > 0) {
                map.setBounds(bounds);
            }
        })
        .catch(error => console.error("정류장 표시 중 오류:", error));
}

/**
 * 그리기 관리자를 생성하고 지도에 도구 상자를 표시합니다.
 */
function initDrawingManager() {
    if (!map) { console.error("지도 객체가 없습니다."); return; }
    const drawingOptions = {
        map: map,
        drawingMode: [
            kakao.maps.drawing.OverlayType.MARKER,
            kakao.maps.drawing.OverlayType.POLYLINE,
            kakao.maps.drawing.OverlayType.RECTANGLE,
            kakao.maps.drawing.OverlayType.CIRCLE,
            kakao.maps.drawing.OverlayType.POLYGON
        ],
        guideTooltip: ['draw', 'drag', 'edit'],
        markerOptions: { draggable: true, removable: true },
        polylineOptions: { draggable: true, removable: true, strokeColor: '#ff0000', strokeWeight: 3, strokeOpacity: 0.8 },
        polygonOptions: { // [수정된 부분] 폴리곤 스타일 옵션 추가
            draggable: true,
            removable: true,
            strokeWeight: 2,
            strokeColor: '#008000', // 테두리 색상 (녹색)
            fillColor: '#008000',   // 채우기 색상 (녹색)
            fillOpacity: 0.3       // 채우기 투명도 (30%)
        }
    };
    drawingManager = new kakao.maps.drawing.DrawingManager(drawingOptions);

    drawingManager.addListener('drawend', function(data) {
        console.log('사용자가 그리기를 완료했습니다. 데이터:', data);
        document.getElementById('start-drawing-btn').style.display = 'inline-block';
        document.getElementById('cancel-drawing-btn').style.display = 'none';
    });
}


/**
 * 서버에서 받은 최적 경로 결과를 지도와 정보 패널에 그립니다.
 */
function drawRoutesAndInfo(busRoutes) {
    const bounds = new kakao.maps.LatLngBounds();
    const infoPanel = document.getElementById('info-panel');
    infoPanel.querySelectorAll('.route-info').forEach(r => r.remove());

    busRoutes.forEach(busRoute => {
        const routeColor = busRoute.color;
        const fullRoute = busRoute.route;
        if (fullRoute.length === 0) return;

        const routeDiv = document.createElement('div');
        routeDiv.className = 'route-info';
        const header = document.createElement('div');
        header.className = 'route-header';

        const departureTime = fullRoute.length > 1 ? (fullRoute[0].arrivalTime - (busRoute.routeTime - (fullRoute[fullRoute.length - 1].arrivalTime - fullRoute[0].arrivalTime))) : 0;

        const headerContent = document.createElement('div');
        headerContent.className = 'header-main-content';
        headerContent.innerHTML = `<span style="background-color:${routeColor}; border: 1px solid #555;"></span>버스 #${busRoute.busId} (서비스 시간: ${busRoute.routeTime}분, 탑승인원: ${busRoute.finalLoad}명)
                            <div class="departure-time">예상 출발(차고지): ${formatMinutesToTime(departureTime)}</div>`;
        header.appendChild(headerContent);

        const buttonContainer = document.createElement('div');
        buttonContainer.style.marginLeft = '10px';
        const editButton = document.createElement('button');
        editButton.innerText = '경로 수정';
        editButton.style.fontSize = '0.8em';
        buttonContainer.appendChild(editButton);
        header.appendChild(buttonContainer);

        const lockContainer = document.createElement('div');
        lockContainer.className = 'lock-container';
        const lockCheckbox = document.createElement('input');
        lockCheckbox.type = 'checkbox';
        lockCheckbox.id = `lock-bus-${busRoute.busId}`;
        const lockLabel = document.createElement('label');
        lockLabel.htmlFor = lockCheckbox.id;
        lockLabel.innerText = '경로 고정';
        lockCheckbox.onchange = function() {
            const routeToLock = lockedRoutes.get(busRoute.busId) || busRoute.route;
            if (this.checked) {
                lockedRoutes.set(busRoute.busId, routeToLock);
                header.classList.add('locked');
            } else {
                lockedRoutes.delete(busRoute.busId);
                header.classList.remove('locked');
            }
            finalizeAllBtn.style.display = lockedRoutes.size > 0 ? 'block' : 'none';
        };
        lockContainer.append(lockCheckbox, lockLabel);
        header.appendChild(lockContainer);
        routeDiv.appendChild(header);

        const stopUl = document.createElement('ul');
        stopUl.className = 'stop-list';
        redrawStopList(stopUl, fullRoute, false);
        routeDiv.appendChild(stopUl);
        infoPanel.appendChild(routeDiv);

        editButton.onclick = function(event) {
            event.stopPropagation();
            if (currentlyEditing.busId) { alert('다른 경로를 수정 중입니다. 먼저 완료하거나 취소해주세요.'); return; }

            currentlyEditing = { busId: busRoute.busId, routeDiv, originalStops: [...fullRoute], editedStops: [...fullRoute] };
            routeDiv.classList.add('editing');
            lockCheckbox.disabled = true;
            redrawStopList(stopUl, currentlyEditing.editedStops, true);

            editButton.style.display = 'none';
            const saveButton = document.createElement('button');
            saveButton.innerText = '수정 완료';
            saveButton.style.cssText = 'font-size: 0.8em; background-color: #28a745; color: white;';
            const cancelButton = document.createElement('button');
            cancelButton.innerText = '취소';
            cancelButton.style.cssText = 'font-size: 0.8em; margin-left: 5px;';
            buttonContainer.append(saveButton, cancelButton);

            saveButton.onclick = function() {
                lockedRoutes.set(busRoute.busId, currentlyEditing.editedStops);
                lockCheckbox.checked = true;
                header.classList.add('locked');
                finalizeAllBtn.style.display = 'block';
                alert(`버스 #${busRoute.busId}의 수정사항이 임시 저장(고정)되었습니다.`);
                redrawStopList(stopUl, currentlyEditing.editedStops, false);
                editButton.style.display = 'inline-block';
                lockCheckbox.disabled = false;
                buttonContainer.removeChild(saveButton);
                buttonContainer.removeChild(cancelButton);
                exitEditMode();
            };

            cancelButton.onclick = function() {
                redrawStopList(stopUl, currentlyEditing.originalStops, false);
                editButton.style.display = 'inline-block';
                lockCheckbox.disabled = false;
                buttonContainer.removeChild(saveButton);
                buttonContainer.removeChild(cancelButton);
                exitEditMode();
            };
        };

        fullRoute.forEach((stop, index) => {
            const latlng = new kakao.maps.LatLng(stop.lat, stop.lon);
            bounds.extend(latlng);
            const isDepot = stop.id.startsWith("DEPOT");
            const isFirstStop = index === 0 && !isDepot;
            const isFinalDepot = index === fullRoute.length - 1 && isDepot;
            const isIntermediateStop = !isFirstStop && !isFinalDepot && !isDepot;

            if (isFirstStop) {
                const contentDiv = document.createElement('div');
                contentDiv.className = 'custom-marker';
                contentDiv.innerHTML = busRoute.busId;
                const overlay = new kakao.maps.CustomOverlay({ position: latlng, content: contentDiv, yAnchor: 1 });
                overlay.setMap(map);
                mapOverlays.push(overlay);

                contentDiv.onclick = () => {
                    if (currentlyEditing.busId && !isDepot) {
                        showAddStopModal(stop);
                    } else {
                        const infowindow = new kakao.maps.InfoWindow({
                            content: `<div style="padding:5px;font-size:12px;"><b>${stop.name}</b><br>도착예정: ${formatMinutesToTime(stop.arrivalTime)}</div>`,
                            removable: true
                        });
                        infowindow.setPosition(latlng);
                        infowindow.open(map);
                    }
                };
            } else {
                let markerImage;
                if (isIntermediateStop) {
                    const circle = new kakao.maps.Circle({ center: latlng, radius: 50, fillColor: routeColor, fillOpacity: 0.8, strokeOpacity: 0 });
                    circle.setMap(map);
                    mapOverlays.push(circle);
                } else if (isFinalDepot) {
                     markerImage = new kakao.maps.MarkerImage('https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/marker_red.png', new kakao.maps.Size(33, 36), { offset: new kakao.maps.Point(16, 34) });
                }

                if (markerImage) {
                    const marker = new kakao.maps.Marker({ position: latlng, map, image: markerImage, title: stop.name });
                    mapOverlays.push(marker);

                    kakao.maps.event.addListener(marker, 'click', () => {
                        if (currentlyEditing.busId && !isDepot) {
                            showAddStopModal(stop);
                        } else {
                            const infowindow = new kakao.maps.InfoWindow({
                                content: `<div style="padding:5px;font-size:12px;"><b>${stop.name}</b><br>도착예정: ${formatMinutesToTime(stop.arrivalTime)}</div>`,
                                removable: true
                            });
                            infowindow.open(map, marker);
                        }
                    });
                }
            }
        });

        if (busRoute.detailedPath) {
            busRoute.detailedPath.forEach(segmentPath => {
                if (segmentPath && segmentPath.length > 0) {
                    const pathCoordinates = segmentPath.map(coord => new kakao.maps.LatLng(coord.lat, coord.lng));
                    const polyline = new kakao.maps.Polyline({ path: pathCoordinates, strokeWeight: 3, strokeColor: routeColor, strokeOpacity: 0.6, strokeStyle: 'solid' });
                    polyline.setMap(map);
                    mapOverlays.push(polyline);
                }
            });
        }
    });

    if (!bounds.isEmpty()) map.setBounds(bounds);
}


// --- 4. 유틸리티 및 기타 함수들 ---

function formatMinutesToTime(totalMinutes) {
    if (typeof totalMinutes !== 'number' || isNaN(totalMinutes)) return 'N/A';
    const h = Math.floor(totalMinutes / 60).toString().padStart(2, '0');
    const m = (totalMinutes % 60).toString().padStart(2, '0');
    return `${h}:${m}`;
}

function clearMap() {
    for (let i = 0; i < mapOverlays.length; i++) {
        mapOverlays[i].setMap(null);
    }
    mapOverlays = [];
}

function exitEditMode() {
    if (currentlyEditing.routeDiv) currentlyEditing.routeDiv.classList.remove('editing');
    currentlyEditing = { busId: null, routeDiv: null, originalStops: [], editedStops: [] };
}

function redrawStopList(ulElement, stops, isEditing) {
    ulElement.innerHTML = '';
    stops.forEach((stop, index) => {
        const stopLi = document.createElement('li');
        let stopText = `<span>${stop.name}`;
        if (stop.demand > 0) stopText += ` (${stop.demand}명)`;
        stopText += `</span>`;

        if (isEditing && !stop.id.startsWith("DEPOT")) {
            const deleteBtn = document.createElement('button');
            deleteBtn.innerText = '삭제';
            deleteBtn.className = 'delete-btn';
            deleteBtn.onclick = () => {
                currentlyEditing.editedStops.splice(index, 1);
                redrawStopList(ulElement, currentlyEditing.editedStops, true);
            };
            stopLi.innerHTML = stopText;
            stopLi.appendChild(deleteBtn);
        } else {
             const timeText = `<span class="time">${formatMinutesToTime(stop.arrivalTime)} 도착</span>`;
            stopLi.innerHTML = stopText + timeText;
        }
        ulElement.appendChild(stopLi);
    });
}

function showAddStopModal(newStop) {
    stopToAdd = newStop;
    document.getElementById('new-stop-name').innerText = newStop.name;
    const select = document.getElementById('insert-before-select');
    select.innerHTML = '';
    currentlyEditing.editedStops.forEach(stop => {
        const option = document.createElement('option');
        option.value = stop.id;
        option.innerText = stop.name;
        select.appendChild(option);
    });
    document.getElementById('modal-backdrop').style.display = 'block';
    document.getElementById('add-stop-modal').style.display = 'block';
}

function hideAddStopModal() {
    document.getElementById('modal-backdrop').style.display = 'none';
    document.getElementById('add-stop-modal').style.display = 'none';
    stopToAdd = null;
}