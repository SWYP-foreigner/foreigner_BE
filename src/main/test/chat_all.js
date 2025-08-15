import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '1m', target: 200 },
        { duration: '1m', target: 500 },
        { duration: '2m', target: 1000 },
        { duration: '1m', target: 0 },
    ],
    thresholds: {
        'http_req_duration': ['p(95)<500'],
        'ws_connect_duration': ['p(95)<500'],
        'ws_msgs_sent': ['p(95)<100'],
    },
};

function getRandomUserId() {
    return Math.floor(Math.random() * 1000) + 1;
}

function connectWebSocket(roomId) {
    const url = `ws://localhost:8080/plain-ws/chat`;
    const res = ws.connect(url, {}, (socket) => {
        let pingInterval;
        socket.on('open', () => {
            console.log(`WS connected: Room ${roomId}`);

            // Ping/heartbeat
            pingInterval = setInterval(() => {
                socket.send(JSON.stringify({ type: 'ping', roomId }));
            }, 5000);

            // 메시지 송신 반복
            socket.setInterval(() => {
                const message = {
                    roomId: roomId,
                    senderId: getRandomUserId(),
                    content: 'k6 안정화 테스트 메시지',
                };
                socket.send(JSON.stringify(message));
            }, 3000);
        });

        socket.on('message', (msg) => {
            // 메시지 수신 통계 기록 가능
        });

        socket.on('close', () => {
            console.log(`WS closed: Room ${roomId}`);
            if (pingInterval) clearInterval(pingInterval);
            // 재접속
            sleep(1);
            connectWebSocket(roomId);
        });

        socket.on('error', (e) => {
            console.error(`WS error: ${e}`);
            if (pingInterval) clearInterval(pingInterval);
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
    const roomId = resJson && resJson.data && resJson.data.id;
    if (!roomId) {
        console.error('roomId가 undefined입니다!');
        return;
    }

    // 2️⃣ WebSocket 연결
    connectWebSocket(roomId);

    // VU별 유지 시간
    sleep(60);
}
