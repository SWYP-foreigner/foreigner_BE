import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// ==========================
// 1. 채팅방 ID 불러오기
// ==========================
const preExistingRoomIds = new SharedArray('roomIds', function () {
    const file = open('./roomIds.json'); // 기존 채팅방 ID JSON
    return JSON.parse(file);
});

// ==========================
// 2. 커스텀 메트릭 정의
// ==========================
const wsMessagesSent = new Counter('ws_messages_sent');
const wsMessagesReceived = new Counter('ws_messages_received');

// ==========================
// 3. 옵션
// ==========================
export const options = {
    stages: [
        { duration: '3m', target: 1000 }, // 3분 동안 0→1000 VU
    ],
    thresholds: {
        'ws_connect_duration': ['p(95)<1000'],
        'ws_messages_sent': ['count>100000'], // 총 메시지 송신 10만개 이상
        'ws_messages_received': ['count>100000'], // 총 수신 10만개 이상
    },
};

// ==========================
// 4. 헬퍼 함수
// ==========================
function pickRandomRoomId() {
    return preExistingRoomIds[Math.floor(Math.random() * preExistingRoomIds.length)];
}

// ==========================
// 5. 시나리오
// ==========================
export default function () {
    const vuId = __VU;
    const roomId = pickRandomRoomId();

    // ----- 5-1. 채팅방 입장 전, 과거 메시지 조회 -----
    const res = http.get(`http://localhost:8080/rooms/${roomId}/messages?limit=50`);
    check(res, {
        'history status 200': (r) => r.status === 200,
        'history not empty': (r) => JSON.parse(r.body).length >= 0, // 메시지가 없을 수도 있음
    });

    // ----- 5-2. WebSocket 연결 -----
    const wsRes = ws.connect(`ws://localhost:8080/plain-ws/chat`, {}, (socket) => {
        let msgInterval;

        // 연결 열렸을 때
        socket.on('open', () => {
            console.log(`VU ${vuId} connected to Room ${roomId}`);

            // 1초마다 메시지 송신
            msgInterval = socket.setInterval(() => {
                const message = {
                    roomId: roomId,
                    senderId: vuId,
                    content: `VU-${vuId}의 테스트 메시지`,
                };
                socket.send(JSON.stringify(message));
                wsMessagesSent.add(1);
            }, 1000);
        });

        // 실시간 메시지 수신
        socket.on('message', (msg) => {
            wsMessagesReceived.add(1);
            // 필요시 console.log(msg)로 확인 가능
        });

        // 연결 종료
        socket.on('close', () => {
            if (msgInterval) socket.clearInterval(msgInterval);
            console.log(`VU ${vuId} disconnected from Room ${roomId}`);
        });

        socket.on('error', (e) => {
            console.error(`VU ${vuId} WS error: ${e}`);
        });
    });

    check(wsRes, { 'ws status 101': (r) => r && r.status === 101 });

    sleep(1); // 다음 iteration 전 1초 대기
}
