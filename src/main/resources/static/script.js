
 let map;
 let drawingManager;
 let mapOverlays = [];
 let currentlyEditing = { busId: null, routeDiv: null, originalStops: [], editedStops: [] };
 let stopToAdd = null;
 let lockedRoutes = new Map();
 const finalizeAllBtn = document.getElementById('finalize-all-btn');




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


 kakao.maps.load(initializeApp);


 function setupEventListeners() {

     const loadStopsBtn = document.getElementById('load-stops-btn');
     loadStopsBtn.onclick = function() {
         fetchAllStopsAndDisplay().then(() => {
             document.getElementById('start-optimization-btn').disabled = false;
             document.getElementById('start-drawing-btn').disabled = false;
             document.getElementById('clear-polygons-btn').disabled = false;
             document.getElementById('get-drawing-data-btn').disabled = false;
         }).catch(() => {
         });
     };

     const startBtn = document.getElementById('start-optimization-btn');
     startBtn.onclick = function() {
         const timeLimit = document.getElementById('time-limit').value;
         const capacity = document.getElementById('capacity').value;
         const serviceTime = document.getElementById('service-time').value;
         const dbName = document.getElementById('db-select').value;
         document.getElementById('status-container').innerHTML = '<h2>경로 계산 중...</h2>';
         startBtn.disabled = true;

         const startDrawingBtn = document.getElementById('start-drawing-btn');
         const cancelDrawingBtn = document.getElementById('cancel-drawing-btn');
         const clearPolygonsBtn = document.getElementById('clear-polygons-btn');
         if (drawingManager) drawingManager.cancel();
         if(startDrawingBtn) startDrawingBtn.style.display = 'none';
         if(cancelDrawingBtn) cancelDrawingBtn.style.display = 'none';
         if(clearPolygonsBtn) clearPolygonsBtn.style.display = 'none';

         clearMap();

         fetch(`/api/optimize-route?timeLimit=${timeLimit}&capacity=${capacity}&serviceTime=${serviceTime}&dbName=${dbName}`)
             .then(response => response.json())
             .then(data => {
                 if (!data || !data.busRoutes || data.busRoutes.length === 0) {
                     document.getElementById('status-container').innerHTML = '<h2>오류: 서버에서 해답을 찾지 못했습니다.</h2>';
                     startBtn.disabled = false;
                     fetchAllStopsAndDisplay();
                     return;
                 }
                 startBtn.style.display = 'none';
                 const statusContainer = document.getElementById('status-container');
                 statusContainer.innerHTML = `
                     <h3>총 운행 정보</h3>
                     <div class="status-line">
                         <p>필요 버스: ${data.usedBuses}대 </p>
                         <div class="master-toggle">
                             <input type="checkbox" id="toggle-all-routes" checked>
                             <label for="toggle-all-routes">모든 경로 표시</label>
                         </div>
                     </div>
                     `;
                 drawRoutesAndInfo(data.busRoutes);
                 document.getElementById('toggle-all-routes').onchange = function() {
                     const isVisible = this.checked;
                     document.querySelectorAll('.visibility-toggle-checkbox').forEach(checkbox => {
                         if (checkbox.checked !== isVisible) {
                             checkbox.checked = isVisible;
                             checkbox.dispatchEvent(new Event('change'));
                         }
                     });
                 };
             })
             .catch(error => {
                 document.getElementById('status-container').innerHTML = '<h2>경로 계산 중 오류가 발생했습니다.</h2>';
                 startBtn.disabled = false;
                 fetchAllStopsAndDisplay();
                 console.error('Error:', error);
             })
             .finally(() => {
                 if(startDrawingBtn) startDrawingBtn.style.display = 'inline-block';
                 if(cancelDrawingBtn) cancelDrawingBtn.style.display = 'none';
                 if(clearPolygonsBtn) clearPolygonsBtn.style.display = 'inline-block';
             });
     };

         const finalizeAllBtn = document.getElementById('finalize-all-btn');
         finalizeAllBtn.onclick = function() {
             if (lockedRoutes.size === 0) {
                 alert('고정된 경로가 없습니다.');
                 return;
             }

             // UI에서 현재 파라미터 값들을 다시 가져옵니다.
             const timeLimit = document.getElementById('time-limit').value;
             const capacity = document.getElementById('capacity').value;
             const serviceTime = document.getElementById('service-time').value;
             const dbName = document.getElementById('db-select').value;

             const payload = {
                 modifications: Array.from(lockedRoutes.entries()).map(([busId, stops]) => ({ busId, newRoute: stops })),
                 params: {
                     timeLimit: timeLimit,
                     capacity: capacity,
                     serviceTime: serviceTime,
                     dbName: dbName
                 }
             };

             document.getElementById('status-container').innerHTML = '<h2>재계산 중...</h2>';
             finalizeAllBtn.disabled = true;
             clearMap();

             fetch('/api/re-optimize', {
                 method: 'POST',
                 headers: { 'Content-Type': 'application/json' },
                 body: JSON.stringify(payload),
             })
             .then(response => response.json())
             .then(newSolution => {
                 alert('재계산이 완료되었습니다. 새로운 경로를 표시합니다.');
                 lockedRoutes.clear();
                 finalizeAllBtn.style.display = 'none';
                 finalizeAllBtn.disabled = false;

                 const statusContainer = document.getElementById('status-container');
                 statusContainer.innerHTML = `
                     <h3>총 운행 정보</h3>
                     <div class="status-line">
                         <p>필요 버스: ${newSolution.usedBuses}대 | 전체 목표 비용: ${newSolution.totalObjectiveTime}</p>
                         <div class="master-toggle">
                             <input type="checkbox" id="toggle-all-routes" checked>
                             <label for="toggle-all-routes">모든 경로 표시</label>
                         </div>
                     </div>
                     `;

                 drawRoutesAndInfo(newSolution.busRoutes);

                 document.getElementById('toggle-all-routes').onchange = function() {
                     const isVisible = this.checked;
                     document.querySelectorAll('.visibility-toggle-checkbox').forEach(checkbox => {
                         if (checkbox.checked !== isVisible) {
                             checkbox.checked = isVisible;
                             checkbox.dispatchEvent(new Event('change'));
                         }
                     });
                 };
             })
             .catch(error => {
                 alert('오류: ' + error.message);
                 document.getElementById('status-container').innerHTML = '<h2>재계산 중 오류 발생</h2>';
                 finalizeAllBtn.disabled = false;
                 fetchAllStopsAndDisplay();
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
                             const points = polygon.points;
                             points.forEach((point, i) => {
                                 resultText += `  - 꼭짓점 ${i + 1}: (위도: ${point.y}, 경도: ${point.x})\n`;
                             });
                             resultText += '\n';
                         });
                     }
                     resultDiv.textContent = resultText;
                 };
             }
 }

function fetchAllStopsAndDisplay() {
    // Promise를 반환하여 비동기 작업의 완료 시점을 알려줍니다.
    return new Promise((resolve, reject) => {
        // 1. 사용자가 선택한 DB 파일명을 가져옵니다.
        const selectedDbName = document.getElementById('db-select').value;
        if (!selectedDbName) {
            alert('데이터베이스를 선택해주세요.');
            reject(new Error("DB not selected"));
            return;
        }

        console.log(`'${selectedDbName}' 데이터 로딩을 시작합니다...`);
        // 마커를 새로 그리기 전에 이전 마커들을 모두 지웁니다.
        clearMap();

        // 2. fetch 요청 URL에 쿼리 파라미터로 dbName을 추가합니다.
        fetch(`/api/all-stops?dbName=${selectedDbName}`)
            .then(response => {
                if (!response.ok) throw new Error('모든 정류장 정보 로딩 실패');
                return response.json();
            })
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

                    const marker = new kakao.maps.Marker({
                        map: map,
                        position: position,
                        title: stop.name,
                        image: markerImage
                    });

                    const infowindow = new kakao.maps.InfoWindow({
                        content: `<div style="padding:5px;font-size:12px;">${stop.name}</div>`,
                        removable: true
                    });

                    kakao.maps.event.addListener(marker, 'click', function() {
                        infowindow.open(map, marker);
                    });

                    mapOverlays.push(marker);
                    bounds.extend(position);
                });

                if (stops.length > 0) {
                    map.setBounds(bounds);
                }

                // 3. 모든 작업이 성공적으로 끝나면 resolve()를 호출하여 성공을 알립니다.
                resolve();
            })
            .catch(error => {
                console.error("정류장 표시 중 오류:", error);
                // 4. 작업 중 오류가 발생하면 reject()를 호출하여 실패를 알립니다.
                reject(error);
            });
    });
}

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
         polygonOptions: {
             draggable: true,
             removable: true,
             strokeWeight: 2,
             strokeColor: '#008000',
             fillColor: '#008000',
             fillOpacity: 0.3
         }
     };
     drawingManager = new kakao.maps.drawing.DrawingManager(drawingOptions);

     drawingManager.addListener('drawend', function(data) {
         console.log('사용자가 그리기를 완료했습니다. 데이터:', data);
         updateStopsInPolygonList();
         document.getElementById('start-drawing-btn').style.display = 'inline-block';
         document.getElementById('cancel-drawing-btn').style.display = 'none';
     });
     drawingManager.addListener('remove', function() {
         console.log('도형이 제거되었습니다.');
         updateStopsInPolygonList();
     });
 }

function drawRoutesAndInfo(busRoutes) {
    const bounds = new kakao.maps.LatLngBounds();
    const infoPanel = document.getElementById('info-panel');
    const statusContainer = document.getElementById('status-container');

    // Remove old route-info divs but keep the status container
    infoPanel.querySelectorAll('.route-info').forEach(r => r.remove());

    busRoutes.forEach((busRoute, routeIndex) => {
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
        headerContent.innerHTML = `<span style="background-color:${routeColor}; border: 1px solid #555;"></span>버스 #${busRoute.busId} (서비스 시간: ${busRoute.routeTime}분, 탑승인원: ${busRoute.finalLoad}명)`;
        header.appendChild(headerContent);

        const buttonContainer = document.createElement('div');
        buttonContainer.style.marginLeft = '10px';
        const editButton = document.createElement('button');
        editButton.innerText = '경로 수정';
        editButton.style.fontSize = '0.8em';
        buttonContainer.appendChild(editButton);
        header.appendChild(buttonContainer);

        const visibilityContainer = document.createElement('div');
        visibilityContainer.className = 'lock-container';
        const visibilityCheckbox = document.createElement('input');
        visibilityCheckbox.type = 'checkbox';
        visibilityCheckbox.checked = true;
        visibilityCheckbox.id = `visibility-bus-${busRoute.busId}`;
        visibilityCheckbox.className = 'visibility-toggle-checkbox';
        const visibilityLabel = document.createElement('label');
        visibilityLabel.htmlFor = visibilityCheckbox.id;
        visibilityLabel.innerText = '경로 표시';
        visibilityLabel.style.marginRight = '10px';

        const routePolylines = [];

        visibilityCheckbox.onchange = function() {
            const isVisible = this.checked;
            routePolylines.forEach(polyline => {
                polyline.setMap(isVisible ? map : null);
            });
        };
        visibilityContainer.append(visibilityCheckbox, visibilityLabel);
        header.appendChild(visibilityContainer);

        const lockContainer = document.createElement('div');
        lockContainer.className = 'lock-container';
        const lockCheckbox = document.createElement('input');
        lockCheckbox.type = 'checkbox';
        lockCheckbox.id = `lock-bus-${busRoute.busId}`;
        const lockLabel = document.createElement('label');
        lockLabel.htmlFor = lockCheckbox.id;
        lockLabel.innerText = '경로 고정';

        // --- [복구된 로직 1] 경로 고정 체크박스 이벤트 핸들러 ---
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
        // ----------------------------------------------------

        lockContainer.append(lockCheckbox, lockLabel);
        header.appendChild(lockContainer);
        routeDiv.appendChild(header);

        const stopUl = document.createElement('ul');
        stopUl.className = 'stop-list';
        redrawStopList(stopUl, fullRoute, false);
        routeDiv.appendChild(stopUl);
        infoPanel.appendChild(routeDiv);

        // --- [복구된 로직 2] 경로 수정 버튼 이벤트 핸들러 ---
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
                console.log("save버튼 클릭 됨")
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
        // ----------------------------------------------------

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
                    markerImage = new kakao.maps.MarkerImage('https://t1.daumcdn.net/mapjsapi/images/marker.png', new kakao.maps.Size(10, 10), { offset: new kakao.maps.Point(5, 5) });
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
                    const polyline = new kakao.maps.Polyline({
                        path: pathCoordinates,
                        strokeWeight: 4,
                        strokeColor: routeColor,
                        strokeOpacity: 0.8,
                        strokeStyle: 'solid',
                        zIndex: routeIndex
                    });
                    polyline.setMap(map);
                    mapOverlays.push(polyline);
                    routePolylines.push(polyline);
                }
            });
        }
    });

    if (!bounds.isEmpty()) map.setBounds(bounds);
}


 // --- 4. 유틸리티 및 기타 함수들 ---

 function isPointInPolygon(point, polygonPath) {
     let intersections = 0;
     for (let i = 0; i < polygonPath.length; i++) {
         const p1 = polygonPath[i];
         const p2 = polygonPath[(i + 1) % polygonPath.length];
         if ((p1.getLat() > point.getLat()) !== (p2.getLat() > point.getLat())) {
             const atX = (p2.getLng() - p1.getLng()) * (point.getLat() - p1.getLat()) / (p2.getLat() - p1.getLat()) + p1.getLng();
             if (point.getLng() < atX) {
                 intersections++;
             }
         }
     }
     return intersections % 2 > 0;
 }

 function updateStopsInPolygonList() {
     if (!drawingManager) return;
     const listElement = document.getElementById('polygon-stops-list');
     listElement.innerHTML = '';
     const data = drawingManager.getData();
     const polygons = data[kakao.maps.drawing.OverlayType.POLYGON];
     if (polygons.length === 0) {
         listElement.innerHTML = '<li>폴리곤을 그려주세요.</li>';
         return;
     }
     const targetPolygonData = polygons[0];
     const polygonPath = targetPolygonData.points.map(p => new kakao.maps.LatLng(p.y, p.x));
     let stopsInside = [];
     mapOverlays.forEach(overlay => {
         if (overlay instanceof kakao.maps.Marker) {
             if (isPointInPolygon(overlay.getPosition(), polygonPath)) {
                 stopsInside.push(overlay.getTitle());
             }
         }
     });
     if (stopsInside.length > 0) {
         stopsInside.forEach(stopName => {
             const li = document.createElement('li');
             li.textContent = stopName;
             listElement.appendChild(li);
         });
     } else {
         listElement.innerHTML = '<li>포함된 경유지가 없습니다.</li>';
     }
 }

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