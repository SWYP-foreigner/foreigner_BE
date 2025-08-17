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
function parseSafely(res) {
    try {
        if (res.body && res.body.length > 0) {
            // ApiResponse 구조에서 'data' 필드만 반환
            const jsonBody = res.json();
            return jsonBody.data;
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

    // 응답에서 data 배열을 올바르게 추출
    const roomsData = roomsRes.json().data;

    if (!roomsData || !Array.isArray(roomsData) || roomsData.length === 0) {
        console.log(`[VU ${vuId}] No rooms found for user ${userId}. Skipping test.`);
        return;
    }

    // 2. 무작위 채팅방 선택
    // 무작위로 선택된 것은 객체 자체이므로, 별도의 변수에 할당합니다.
    const selectedRoom = roomsData[Math.floor(Math.random() * roomsData.length)];

    // 선택된 객체에서 ID를 추출하여 roomId에 할당합니다.
    const roomId = selectedRoom.id;

    // roomId가 유효한지 확인합니다.
    if (!roomId) {
        console.log(`[VU ${vuId}] Invalid room data found. Skipping test.`);
        return;
    }
    console.log(`[VU ${vuId}] Selected room: ${roomId}`);

    // 3. 메시지 무한 스크롤 시뮬레이션
    let lastMessageId = null;
    for (let i = 0; i < 20; i++) {
        let url = `${baseUrl}/api/v1/chat/rooms/${roomId}/messages?userId=${userId}&limit=50`;

        if (lastMessageId) {
            url += `&lastMessageId=${lastMessageId}`;
        }

        const res = http.get(url);
        check(res, { 'get messages status is 200': (r) => r.status === 200 });
        getMessagesTrend.add(res.timings.duration);

        // API 응답 구조를 고려하여 `data` 필드에서 메시지 목록을 추출
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