import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// 커스텀 Metric (Trend로 변경)
const wsMessagesSent = new Trend('ws_messages_sent');

export const options = {
    // 5분 동안 0명에서 1000명까지 서서히 증가
    // 다음 단계에서 1만명까지 증가
    stages: [
        { duration: '5m', target: 1000 },
        { duration: '10m', target: 10000 }, // 10분 동안 1만명 유지
        { duration: '5m', target: 0 },
    ],
    // 기존의 임계치들은 유지
    thresholds: {
        'http_req_duration': ['p(95)<500'],
        'ws_connect_duration': ['p(95)<500'],
        'ws_messages_sent': ['p(95)<2000'],
    },
};

function getRandomUserId() {
    return Math.floor(Math.random() * 1000) + 1;
}

function connectWebSocket(roomId) {
    const url = `ws://localhost:8080/plain-ws/chat`;
    const res = ws.connect(url, {}, (socket) => {
        let pingInterval, msgInterval;

        socket.on('open', () => {
            console.log(`WS connected: Room ${roomId}`);

            // Ping/heartbeat
            pingInterval = socket.setInterval(() => {
                socket.send(JSON.stringify({ type: 'ping', roomId }));
            }, 5000);

            // 메시지 송신 반복
            msgInterval = socket.setInterval(() => {
                const message = {
                    roomId: roomId,
                    senderId: getRandomUserId(),
                    content: 'k6 안정화 테스트 메시지',
                };
                socket.send(JSON.stringify(message));
                wsMessagesSent.add(1); // Metric 기록
            }, 3000);
        });

        socket.on('message', (msg) => {
            // 메시지 수신 로그/통계 기록 가능
        });

        socket.on('close', () => {
            console.log(`WS closed: Room ${roomId}`);
            if (pingInterval) socket.clearInterval(pingInterval);
            if (msgInterval) socket.clearInterval(msgInterval);
            sleep(1); // 재접속 대기
            connectWebSocket(roomId); // 재접속
        });

        socket.on('error', (e) => {
            console.error(`WS error: ${e}`);
            if (pingInterval) socket.clearInterval(pingInterval);
            if (msgInterval) socket.clearInterval(msgInterval);
        });
    });

    check(res, { 'ws status 101': (r) => r && r.status === 101 });
}

export default function () {
    // 1️⃣ 채팅방 생성
    const payload = JSON.stringify([
        { id: getRandomUserId() },
        { id: getRandomUserId() },
    ]);
    const res = http.post('http://localhost:8080/api/v1/chat/rooms?isGroup=false', payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    check(res, { 'room created': (r) => r.status === 200 });

    const resJson = res.json();
    const roomId = resJson.data && resJson.data.id;
    if (!roomId) {
        console.error('roomId가 undefined입니다!');
        return;
    }

    // 2️⃣ WebSocket 연결
    connectWebSocket(roomId);

    // VU별 유지 시간
    sleep(60);
}
