import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';


const SINGLE_ROOM_ID = "c5e3f6a2-a8b9-4d1e-8c3b-4f5d6e7a8b9c";

const wsMessagesSent = new Counter('ws_messages_sent');
const wsReadEventsSent = new Counter('ws_read_events_sent');

export const options = {
    stages: [
        { duration: '3m', target: 1000 },
    ],
    thresholds: {
        'ws_connect_duration': ['p(95)<1000'],
        'ws_messages_sent': ['count>100000'], // 총 보낸 메시지 수 10만개 이상
        'http_req_failed': ['rate<0.01'],
    },
};

export default function () {
    const vuId = __VU;

    // 1️⃣ WebSocket 연결
    const url = `ws://localhost:8080/plain-ws/chat`;
    const wsRes = ws.connect(url, null, (socket) => {

        // 2️⃣ 메시지 전송 및 '읽음' 이벤트 전송 간격 설정
        let messageInterval;
        let readEventInterval;

        socket.on('open', () => {
            console.log(`WS connected: VU ${vuId} to Room ${SINGLE_ROOM_ID}`);

            // 1초마다 메시지 전송
            messageInterval = socket.setInterval(() => {
                const message = {
                    roomId: SINGLE_ROOM_ID,
                    senderId: vuId,
                    content: `VU-${vuId}의 테스트 메시지 - ${uuidv4()}`,
                };
                socket.send(JSON.stringify(message));
                wsMessagesSent.add(1);
            }, 1000);

            // 5초마다 '읽음 확인' 이벤트 전송
            readEventInterval = socket.setInterval(() => {
                const readEvent = {
                    type: "READ_CONFIRM",
                    roomId: SINGLE_ROOM_ID,
                    userId: vuId,
                    messageId: "latest"
                };
                // 실제 API에서는 'messageId'를 최신 메시지 ID로 설정해야 합니다.
                // 지금은 테스트용이므로 "latest"로 가정합니다.
                socket.send(JSON.stringify(readEvent));
                wsReadEventsSent.add(1);
            }, 5000);
        });

        socket.on('close', () => {
            if (messageInterval) socket.clearInterval(messageInterval);
            if (readEventInterval) socket.clearInterval(readEventInterval);
            sleep(1);
        });

        socket.on('error', (e) => {
            console.error(`WS error for VU ${vuId}: ${e.error}`);
        });

        // 3️⃣ 세션 유지 (VU가 계속 연결된 상태로 테스트 지속)
        socket.on('message', (data) => {
            // 메시지 수신 로직 (선택적)
        });
    });

    check(wsRes, { 'ws status 101': (r) => r && r.status === 101 });

    sleep(1);
}