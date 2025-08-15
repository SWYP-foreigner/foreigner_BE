import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '30s', target: 200 },
        { duration: '30s', target: 500 },
        { duration: '1m', target: 1000 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        'http_req_duration': ['p(95)<500'],
        'ws_connect_duration': ['p(95)<500'],
    },
};

function getRandomUserId() {
    return Math.floor(Math.random() * 1000) + 1;
}

// WebSocket 연결 + ping + reconnect
function connectWebSocket(url, roomId) {
    const res = ws.connect(url, {}, (socket) => {
        socket.on('open', () => {
            console.log(`WS connected: Room ${roomId}`);

            // ping + 메시지 루프
            while (true) {
                if (socket && socket.readyState === 1) {
                    // ping
                    socket.send(JSON.stringify({ type: 'ping' }));
                    // 테스트 메시지
                    const message = {
                        roomId: roomId,
                        senderId: getRandomUserId(),
                        content: 'k6 테스트 메시지',
                    };
                    socket.send(JSON.stringify(message));
                }
                sleep(5); // 5초마다 ping
            }
        });

        socket.on('message', (msg) => {
            // 메시지 수신 처리
            // console.log(`Received: ${msg}`);
        });

        socket.on('close', () => console.log(`WS closed: Room ${roomId}`));
        socket.on('error', (e) => console.error(`WS error: ${e}`));
    });

    return res;
}

export default function () {
    // 1️⃣ REST API: 채팅방 생성
    const payload = JSON.stringify([
        { id: getRandomUserId() },
        { id: getRandomUserId() },
    ]);
    const res = http.post('http://localhost:8080/api/v1/chat/rooms?isGroup=false', payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    check(res, { 'room created': (r) => r.status === 200 });
    const roomId = res.json('data.id');

    // 2️⃣ Plain WebSocket 연결
    const url = `ws://localhost:8080/plain-ws/chat`;
    connectWebSocket(url, roomId);
}
