import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// ==========================
// 1. 테스트 데이터
// ==========================
const preExistingRoomIds = new SharedArray('roomIds', function () {
    const file = open('roomIds.json');
    return JSON.parse(file);
});
const preExistingUserIds = new SharedArray('userIds', function () {
    const file = open('userIds.json');
    return JSON.parse(file);
});
const users = new SharedArray('user_ids', function () {
    return Array.from({ length: 1000 }, (_, i) => i + 1);
});

// ==========================
// 2. 커스텀 메트릭 정의
// ==========================
const getMessagesTrend = new Trend('get_messages_duration');
const sendMessageTrend = new Trend('send_message_duration');

// ==========================
// 3. 테스트 옵션 (더 하드하게)
// ==========================
export const options = {
    stages: [
        { duration: '30s', target: 500 },
        { duration: '30s', target: 1000 },
        { duration: '30s', target: 1500 },
        { duration: '30s', target: 2000 },
        { duration: '4m', target: 2000 },
        { duration: '1m', target: 0 },
    ],
    thresholds: {
        'http_req_duration': ['p(95)<500'],
        'ws_connecting': ['p(95)<200'],
        'ws_msgs_sent': ['count>500000'],
        'ws_msgs_received': ['count>500000'],
    },
};

// ==========================
// 4. 헬퍼 함수
// ==========================
function parseSafely(res) {
    try {
        if (res.body && res.body.length > 0) {
            return res.json();
        }
    } catch (e) {
        console.error(`JSON parse error: ${e} for response body: ${res.body}`);
    }
    return null;
}

// ==========================
// 5. 메인 시나리오
// ==========================
export default function () {
    const vuId = __VU;
    const userId = preExistingUserIds[vuId - 1];

    const baseUrl = 'http://localhost:8080';
    const wsUrl = `ws://localhost:8080/plain-ws/chat`;

    // 1. 채팅방 목록 조회
    const roomsRes = http.get(`${baseUrl}/api/v1/chat/rooms?userId=${userId}`);
    check(roomsRes, {
        'get rooms status is 200': (r) => r.status === 200,
    });
    sleep(0.5);

    // 응답이 단순 배열인지 확인하고 처리합니다.
    const roomsData = parseSafely(roomsRes);
    if (!roomsData || !Array.isArray(roomsData) || roomsData.length === 0) {
        console.log(`[VU ${vuId}] No rooms found for user ${userId}. Skipping test.`);
        return;
    }

    // 2. 무작위 채팅방 선택
    const roomId = roomsData[Math.floor(Math.random() * roomsData.length)];
    // roomId가 유효한지 확인합니다.
    if (!roomId) {
        console.log(`[VU ${vuId}] Invalid room data found. Skipping test.`);
        return;
    }
    console.log(`[VU ${vuId}] Selected room: ${roomId}`);

    // 3. 메시지 무한 스크롤 시뮬레이션
    let lastMessageId = null;
    for (let i = 0; i < 20; i++) {
        let url = `${baseUrl}/rooms/${roomId}/messages?limit=50`;
        if (lastMessageId) {
            url += `&lastMessageId=${lastMessageId}`;
        }

        const res = http.get(url);
        check(res, { 'get messages status is 200': (r) => r.status === 200 });
        getMessagesTrend.add(res.timings.duration);

        const messages = parseSafely(res);
        if (!Array.isArray(messages) || messages.length === 0) break;

        const lastMessage = messages[messages.length - 1];
        if (!lastMessage || !lastMessage.id) {
            console.log(`[VU ${vuId}] Last message has no ID. Stopping message history retrieval.`);
            break;
        }
        lastMessageId = lastMessage.id;
        sleep(0.2);
    }

    // 4. WebSocket 연결 및 메시지 전송
    const wsRes = ws.connect(wsUrl, null, (socket) => {
        let msgInterval;

        socket.on('open', () => {
            console.log(`[VU ${vuId}] connected to Room ${roomId}`);

            msgInterval = socket.setInterval(() => {
                const message = {
                    roomId: roomId,
                    senderId: userId,
                    content: `VU-${vuId}의 테스트 메시지`,
                };
                socket.send(JSON.stringify(message));
                sendMessageTrend.add(1);
            }, 200);
        });

        socket.on('message', (msg) => {
            sendMessageTrend.add(1);
        });

        socket.on('close', () => {
            if (msgInterval) socket.clearInterval(msgInterval);
            console.log(`VU ${vuId} disconnected from Room ${roomId}`);
        });

        socket.on('error', (e) => {
            console.error(`VU ${vuId} WS error: ${e.error}`);
        });
    });

    check(wsRes, { 'ws connected': (r) => r && r.status === 101 });
    sleep(1);
}