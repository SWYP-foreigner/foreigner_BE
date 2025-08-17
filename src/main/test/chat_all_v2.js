import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// 커스텀 Metric (Trend)
const wsMessagesSent = new Counter('ws_messages_sent');
const getMessagesDuration = new Trend('get_messages_duration');
const typingDuration = new Trend('typing_duration');
const readMessagesDuration = new Trend('read_messages_duration');

export const options = {
    // 3분 동안 0명에서 1000명까지 부하를 주는 단일 스테이지
    stages: [
        { duration: '3m', target: 1000 },
    ],
    thresholds: {
        'http_req_duration': ['p(95)<1000'],
        'get_messages_duration': ['p(95)<2000'],
        'ws_connect_duration': ['p(95)<1000'],
        'http_req_failed': ['rate<0.05'],
        'ws_messages_sent': ['count>1000'],
    },
};

function getRandomUserId() {
    return Math.floor(Math.random() * 1000) + 1;
}

export default function () {
    const vuId = __VU;

    // 1️⃣ 채팅방 생성 (VU별로 하나씩 생성)
    const payload = JSON.stringify([
        { id: vuId },
        { id: getRandomUserId() },
    ]);
    const res = http.post('http://localhost:8080/api/v1/chat/rooms?isGroup=false', payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    const roomCreationSuccess = check(res, {
        'room created and ID exists': (r) => r.status === 200 && r.json() && r.json().data && r.json().data.id
    });

    if (!roomCreationSuccess) {
        return;
    }

    const resJson = res.json();
    const roomId = resJson.data.id;

    // 2️⃣ 과거 메시지 조회
    const getMessagesRes = http.get(`http://localhost:8080/api/v1/chat/rooms/${roomId}/messages?count=500`);
    getMessagesDuration.add(getMessagesRes.timings.duration);
    check(getMessagesRes, { 'messages retrieved': (r) => r.status === 200 });

    // 3️⃣ WebSocket 연결
    const url = `ws://localhost:8080/plain-ws/chat`;
    const wsRes = ws.connect(url, {}, (socket) => {
        let msgInterval, readInterval, getMsgsInterval;

        socket.on('open', () => {
            console.log(`WS connected: VU ${vuId}, Room ${roomId}`);

            msgInterval = socket.setInterval(() => {
                const typingEvent = {
                    senderId: vuId,
                    senderName: `User-${vuId}`,
                    roomId: roomId,
                    isTyping: true,
                };
                socket.send(JSON.stringify(typingEvent));
                typingDuration.add(1);

                const message = {
                    roomId: roomId,
                    senderId: vuId,
                    content: `VU-${vuId}의 테스트 메시지`,
                };
                socket.send(JSON.stringify(message));
                wsMessagesSent.add(1);
            }, 1000);

            readInterval = socket.setInterval(() => {
                const readEvent = {
                    roomId: roomId,
                    readerId: vuId,
                };
                socket.send(JSON.stringify(readEvent));
                readMessagesDuration.add(1);
            }, 5000);

            getMsgsInterval = socket.setInterval(() => {
                const getAgainRes = http.get(`http://localhost:8080/api/v1/chat/rooms/${roomId}/messages?count=500`);
                getMessagesDuration.add(getAgainRes.timings.duration);
                check(getAgainRes, { 're-retrieved messages': (r) => r.status === 200 });
            }, 30000);
        });

        socket.on('close', () => {
            console.log(`WS closed: VU ${vuId}`);
            if (msgInterval) socket.clearInterval(msgInterval);
            if (readInterval) socket.clearInterval(readInterval);
            if (getMsgsInterval) socket.clearInterval(getMsgsInterval);
            sleep(1);
        });

        socket.on('error', (e) => {
            console.error(`WS error for VU ${vuId}: ${e}`);
        });
    });

    check(wsRes, { 'ws status 101': (r) => r && r.status === 101 });

    sleep(1);
}