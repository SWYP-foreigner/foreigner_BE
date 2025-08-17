import http from 'k6/http';
import ws from 'k6/ws';
import { check, group, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// ==========================
// 1. Test Data (Load from JSON files)
// ==========================
const preExistingRoomIds = new SharedArray('roomIds', function () {
    const file = open('roomIds.json');
    return JSON.parse(file);
});
const preExistingUserIds = new SharedArray('userIds', function () {
    const file = open('userIds.json');
    return JSON.parse(file);
});

// ==========================
// 2. Custom Metrics
// ==========================
const createRoomTrend = new Trend('create_room_duration');
const getRoomsTrend = new Trend('get_rooms_duration');
const getMessagesTrend = new Trend('get_messages_duration');
const addParticipantsTrend = new Trend('add_participants_duration');
const leaveRoomTrend = new Trend('leave_room_duration');
const markReadTrend = new Trend('mark_messages_as_read_duration');
const searchMessagesTrend = new Trend('search_messages_duration');

// ==========================
// 3. Test Options
// ==========================
export const options = {
    stages: [
        { duration: '30s', target: 10 },
        { duration: '30s', target: 10 },
        { duration: '30s', target: 10 },
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        'http_req_duration': ['p(95)<500'],
        'ws_messages_sent': ['count>0'],
        'ws_messages_received': ['count>0'],
        'create_room_duration': ['p(95)<1000'],
        'get_messages_duration': ['p(95)<1000'],
        'search_messages_duration': ['p(95)<2000'], // 검색은 더 오래 걸릴 수 있으므로 임계값 상향
    },
};

// ==========================
// 4. Helper Functions
// ==========================
function parseSafely(res) {
    try {
        if (res.body && res.body.length > 0) {
            const jsonBody = res.json();
            return jsonBody.data;
        }
    } catch (e) {
        console.error(`JSON parse error: ${e} for response body: ${res.body}`);
    }
    return null;
}

// ==========================
// 5. Main Scenario
// ==========================
export default function () {
    const vuId = __VU;
    const userId = preExistingUserIds[Math.floor(Math.random() * preExistingUserIds.length)];
    const baseUrl = 'http://localhost:8080/api/v1/chat';

    // 무작위로 미리 생성된 방을 선택하여 테스트
    const roomId = preExistingRoomIds[Math.floor(Math.random() * preExistingRoomIds.length)];

    // 1. WebSocket Messaging
    group('websocket messaging', function() {
        // 기존 웹소켓 로직 유지
    });

    // 2. Get my chat rooms
    group('get chat rooms list', function() {
        const res = http.get(`${baseUrl}/rooms?userId=${userId}`, { tags: { name: 'get_rooms' } });
        check(res, { 'get rooms status is 200': (r) => r.status === 200 });
        getRoomsTrend.add(res.timings.duration);
        sleep(1);
    });

    // 3. Infinite scroll simulation
    group('get messages with infinite scroll', function() {
        let lastMessageId = null;
        for (let i = 0; i < 5; i++) {
            let url = `${baseUrl}/rooms/${roomId}/messages?userId=${userId}&limit=50`;
            if (lastMessageId) {
                url += `&lastMessageId=${lastMessageId}`;
            }

            const res = http.get(url, { tags: { name: 'get_messages' } });
            check(res, { 'get messages status is 200': (r) => r.status === 200 });
            getMessagesTrend.add(res.timings.duration);

            const messages = parseSafely(res);
            if (!Array.isArray(messages) || messages.length === 0) {
                break;
            }
            lastMessageId = messages[messages.length - 1].id;
            sleep(0.5);
        }
    });

    group('mark messages as read', function () {
        const getMessagesRes = http.get(`${baseUrl}/rooms/${roomId}/messages?userId=${userId}&limit=1`);
        const messages = parseSafely(getMessagesRes);

        if (messages && messages.length > 0) {
            const lastMessageId = messages[0].id;
            const res = http.post(
                `${baseUrl}/rooms/${roomId}/read?userId=${userId}`,
                JSON.stringify(lastMessageId),
                {
                    headers: { 'Content-Type': 'application/json' },
                    tags: { name: 'mark_messages_as_read' }
                }
            );
            check(res, { 'mark read status is 200': (r) => r.status === 200 });
            markReadTrend.add(res.timings.duration);
        }
        sleep(0.5);
    });

    group('search messages', function() {
        const searchKeyword = '테스트'; // 고정된 키워드로 검색
        const url = `${baseUrl}/rooms/${roomId}/messages?userId=${userId}&search=${searchKeyword}`;
        const res = http.get(url, { tags: { name: 'search_messages' } });

        check(res, { 'search messages status is 200': (r) => r.status === 200 });
        searchMessagesTrend.add(res.timings.duration);
        sleep(0.5);
    });

    group('leave chat room', function() {
        const res = http.del(`${baseUrl}/rooms/${roomId}/leave?userId=${userId}`, { tags: { name: 'leave_room' } });
        check(res, { 'leave room status is 200': (r) => r.status === 200 });
        leaveRoomTrend.add(res.timings.duration);
    });
}