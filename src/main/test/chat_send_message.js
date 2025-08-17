import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// 1. JSON 파일에서 채팅방 ID 목록을 로드
const preExistingRoomIds = new SharedArray('roomIds', function () {
    const file = open('./roomIds.json');
    return JSON.parse(file);
});

const wsMessagesSent = new Counter('ws_messages_sent');

export const options = {
    // 3분 동안 0명에서 1000명으로 VU를 증가
    stages: [
        { duration: '3m', target: 1000 },
    ],
    thresholds: {
        'ws_connect_duration': ['p(95)<1000'],
        'ws_messages_sent': ['count>100000'], // 총 보낸 메시지 수 10만개 이상
    },
};

export default function () {
    const vuId = __VU;

    // 2. SharedArray에서 무작위로 채팅방 ID 선택
    const roomId = preExistingRoomIds[Math.floor(Math.random() * preExistingRoomIds.length)];

    // 3. WebSocket 연결
    const url = `ws://localhost:8080/plain-ws/chat`;
    const wsRes = ws.connect(url, {}, (socket) => {
        let msgInterval;

        socket.on('open', () => {
            console.log(`WS connected: VU ${vuId}, Room ${roomId}`);

            msgInterval = socket.setInterval(() => {
                const message = {
                    roomId: roomId,
                    senderId: vuId,
                    content: `VU-${vuId}의 테스트 메시지`,
                };
                socket.send(JSON.stringify(message));
                wsMessagesSent.add(1);
            }, 1000); // 1초마다 메시지 전송
        });

        socket.on('close', () => {
            if (msgInterval) socket.clearInterval(msgInterval);
            sleep(1);
        });

        socket.on('error', (e) => {
            console.error(`WS error for VU ${vuId}: ${e}`);
        });
    });

    check(wsRes, { 'ws status 101': (r) => r && r.status === 101 });

    sleep(1);
}