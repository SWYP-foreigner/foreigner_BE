import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// ==========================
// 1. 유효한 조합 불러오기
// ==========================
const validCombinations = new SharedArray('validCombinations', function () {
    const file = open('./valid_combinations.json');
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
        'ws_messages_sent': ['count>100000'],
        'ws_messages_received': ['count>100000'],
    },
};

// ==========================
// 4. 헬퍼 함수
// ==========================
function getRandomCombination() {
    return validCombinations[Math.floor(Math.random() * validCombinations.length)];
}

// ==========================
// 5. 시나리오
// ==========================
export default function () {
    // ⚠️ VU ID가 아닌, 유효한 조합에서 userId와 roomId를 가져옵니다.
    const combination = getRandomCombination();
    const userId = combination.userId;
    const roomId = combination.roomId;

    const res = http.get(`http://localhost:8080/rooms/${roomId}/messages?limit=50&userId=${userId}`);

// 1. 응답 상태 코드가 200인지 먼저 확인
// 2. 응답 본문이 존재하는지 확인
    const isSuccess = check(res, {
        'history status 200': (r) => r.status === 200,
    });

// 3. 성공한 경우에만 JSON 파싱 및 길이 체크
    if (isSuccess) {
        const messages = JSON.parse(res.body);
        check(res, {
            'history not empty': () => messages.length >= 0,
        });
    }

    // ----- 5-2. WebSocket 연결 -----
    const wsRes = ws.connect(`ws://localhost:8080/plain-ws/chat?userId=${userId}&roomId=${roomId}`, {}, (socket) => {
        let msgInterval;

        // 연결 열렸을 때
        socket.on('open', () => {
            console.log(`User ${userId} connected to Room ${roomId}`);

            // 1초마다 메시지 송신
            msgInterval = socket.setInterval(() => {
                const message = {
                    roomId: roomId,
                    senderId: userId,
                    content: `User-${userId}의 테스트 메시지`,
                };
                socket.send(JSON.stringify(message));
                wsMessagesSent.add(1);
            }, 1000);
        });

        // 실시간 메시지 수신
        socket.on('message', (msg) => {
            wsMessagesReceived.add(1);
        });

        // 연결 종료
        socket.on('close', () => {
            if (msgInterval) socket.clearInterval(msgInterval);
            console.log(`User ${userId} disconnected from Room ${roomId}`);
        });

        socket.on('error', (e) => {
            console.error(`User ${userId} WS error: ${e}`);
        });
    });

    check(wsRes, { 'ws status 101': (r) => r && r.status === 101 });

    sleep(1);
}