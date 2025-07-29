const mapContainer = document.getElementById('map');
const mapOption = { center: new kakao.maps.LatLng(37.566826, 126.9786567), level: 8 };
const map = new kakao.maps.Map(mapContainer, mapOption);

// 지도 위 요소를 관리할 전역 배열 ---
let mapOverlays = [];

let currentlyEditing = { busId: null, routeDiv: null, originalStops: [], editedStops: [] };
let stopToAdd = null;
let lockedRoutes = new Map();

const finalizeAllBtn = document.getElementById('finalize-all-btn');

finalizeAllBtn.onclick = function() {
    if (lockedRoutes.size === 0) { alert('고정된 경로가 없습니다.'); return; }
    const payload = { modifications: Array.from(lockedRoutes.entries()).map(([busId, stops]) => ({ busId, newRoute: stops })) };
    document.getElementById('status').innerText = '재계산 중...';
    finalizeAllBtn.disabled = true;

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
    });
};
/*
window.onload = function() {
    fetch('/api/optimize-route')
        .then(response => { if (!response.ok) throw new Error('서버 응답 오류: ' + response.status); return response.json(); })
        .then(data => {
            if(!data || !data.busRoutes || data.busRoutes.length === 0){
                 document.getElementById('status').innerText = '오류: 서버에서 해답을 찾지 못했습니다.'; return;
            }
            document.getElementById('status').innerHTML = `<h3>총 운행 정보</h3><p>필요 버스: ${data.usedBuses}대 | 전체 목표 비용: ${data.totalObjectiveTime}</p><hr>`;
            drawRoutesAndInfo(data.busRoutes);
        })
        .catch(error => { document.getElementById('status').innerText = '경로 계산 중 오류가 발생했습니다.'; console.error('Error:', error); });
};*/
const startBtn = document.getElementById('start-optimization-btn');

startBtn.onclick = function() {
    // UI를 '계산 중' 상태로 변경하고 버튼을 비활성화합니다.
    document.getElementById('status').innerText = '경로 계산 계산 중';
    startBtn.disabled = true;

    fetch('/api/optimize-route')
        .then(response => {
            if (!response.ok) throw new Error('서버 응답 오류: ' + response.status);
            return response.json();
        })
        .then(data => {
            if (!data || !data.busRoutes || data.busRoutes.length === 0) {
                document.getElementById('status').innerText = '오류: 서버에서 해답을 찾지 못했습니다.';
                startBtn.disabled = false; // 실패 시 버튼 다시 활성화
                return;
            }

            // 계산 성공 시, 시작 버튼은 숨깁니다.
            startBtn.style.display = 'none';

            document.getElementById('status').innerHTML = `<h3>총 운행 정보</h3><p>필요 버스: ${data.usedBuses}대 | 전체 목표 비용: ${data.totalObjectiveTime}</p><hr>`;
            drawRoutesAndInfo(data.busRoutes);
        })
        .catch(error => {
            document.getElementById('status').innerText = '경로 계산 중 오류가 발생했습니다.';
            startBtn.disabled = false; // 오류 발생 시 버튼 다시 활성화
            console.error('Error:', error);
        });
};
function formatMinutesToTime(totalMinutes) {
    if (typeof totalMinutes !== 'number' || isNaN(totalMinutes)) return 'N/A';
    const h = Math.floor(totalMinutes / 60).toString().padStart(2, '0');
    const m = (totalMinutes % 60).toString().padStart(2, '0');
    return `${h}:${m}`;
}

// 지도 위 모든 요소를 지우는 함수 ---
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

function drawRoutesAndInfo(busRoutes) {
clearMap();

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

        // --- [핵심 수정] 마커/오버레이 생성 부분 ---
        if (isFirstStop) {
            const contentDiv = document.createElement('div');
            contentDiv.className = 'custom-marker';
            contentDiv.innerHTML = busRoute.busId;
            const overlay = new kakao.maps.CustomOverlay({ position: latlng, content: contentDiv, yAnchor: 1 });
            overlay.setMap(map);
            mapOverlays.push(overlay);

            // 오버레이 전용 클릭 핸들러
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
        } else { // 일반 마커 (중간 경유지, 최종 도착지)
            let markerImage;
            if (isIntermediateStop) {
                const circle = new kakao.maps.Circle({ center: latlng, radius: 50, fillColor: routeColor, fillOpacity: 0.8, strokeOpacity: 0 });
                circle.setMap(map);
                mapOverlays.push(circle);
                markerImage = new kakao.maps.MarkerImage('https://t1.daumcdn.net/mapjsapi/images/dot.png', new kakao.maps.Size(10, 10), { offset: new kakao.maps.Point(5, 5) });
            } else if (isFinalDepot) {
                 markerImage = new kakao.maps.MarkerImage('https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/marker_red.png', new kakao.maps.Size(33, 36), { offset: new kakao.maps.Point(16, 34) });
            }

            if (markerImage) {
                const marker = new kakao.maps.Marker({ position: latlng, map, image: markerImage, title: stop.name });
                mapOverlays.push(marker);

                // 마커 전용 클릭 핸들러
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