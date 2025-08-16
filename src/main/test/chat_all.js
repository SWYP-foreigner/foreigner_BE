import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// 커스텀 Metric (Trend로 변경)
const wsMessagesSent = new Trend('ws_messages_sent');
const getMessagesDuration = new Trend('get_messages_duration'); // 과거 메시지 조회 시간 Trend 추가

export const options = {
    stages: [
        { duration: '1m', target: 500 },
        { duration: '1m', target: 1000 },
        { duration: '2m', target: 2000 },
        { duration: '1m', target: 0 },
    ],
    thresholds: {
        'http_req_duration': ['p(95)<500'],
        'get_messages_duration': ['p(95)<500'], // 새로운 임계값
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

            // 1️⃣ Ping/heartbeat
            pingInterval = socket.setInterval(() => {
                socket.send(JSON.stringify({ type: 'ping', roomId }));
            }, 5000);

            // 2️⃣ 메시지 송신 반복 (전송 빈도 증가)
            msgInterval = socket.setInterval(() => {
                const message = {
                    roomId: roomId,
                    senderId: getRandomUserId(),
                    content: 'k6 안정화 테스트 메시지',
                };
                socket.send(JSON.stringify(message));
                wsMessagesSent.add(1); // Metric 기록
            }, 1000); // 3초 -> 1초로 변경
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

    // 2️⃣ 과거 메시지 조회 (새로운 시나리오 추가)
    // 쿼리 파라미터를 추가하여 메시지 개수를 500개로 지정
    const getMessagesRes = http.get(`http://localhost:8080/api/v1/chat/rooms/${roomId}/messages?count=500`);
    getMessagesDuration.add(getMessagesRes.timings.duration); // 측정값 기록

    check(getMessagesRes, { 'messages retrieved': (r) => r.status === 200 });

    // 3️⃣ WebSocket 연결
    connectWebSocket(roomId);

    // VU별 유지 시간
    sleep(60);
}