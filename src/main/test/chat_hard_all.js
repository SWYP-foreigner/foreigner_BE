import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';


// ==========================
// 1. Test Data: 유효한 조합 및 키워드 불러오기
// ==========================
const validCombinations = new SharedArray('validCombinations', function () {
    const file = open('./valid_combinations.json');
    return JSON.parse(file);
});// 테스트에 사용할 검색 키워드
const searchKeyword = '테스트';

// ==========================
// 2. Custom Metrics
// ==========================
const getMessagesTrend = new Trend('get_messages_duration');
const markReadTrend = new Trend('mark_messages_as_read_duration');
const searchMessagesTrend = new Trend('search_messages_duration');

// ==========================
// 3. Test Options (멀티 시나리오)
// ==========================
export const options = {
    scenarios: {
        http_scenario: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 10 },
            ],
            exec: 'httpTest',
        },
    },
    thresholds: {
        'get_messages_duration': ['p(95)<1500'],
        'search_messages_duration': ['p(95)<3000'],
        'mark_messages_as_read_duration': ['p(95)<1000'],
    },
};

// ==========================
// 4. Helper Functions
// ==========================
function parseSafely(res) {
    try {
        if (res.status === 200 && res.body && res.body.length > 0) {
            const jsonBody = JSON.parse(res.body);
            return jsonBody.data;
        }
    } catch (e) {
        console.error(`JSON parse error: ${e} for response status ${res.status}, body: ${res.body}`);
    }
    return null;
}

// 무작위로 유효한 조합 선택
function getRandomCombination() {
    return validCombinations[Math.floor(Math.random() * validCombinations.length)];
}

// ==========================
// 5. HTTP Scenario
// ==========================
export function httpTest() {
    const baseUrl = 'http://localhost:8080/api/v1/chat';
    const combination = getRandomCombination();
    const userId = combination.userId;
    const roomId = combination.roomId;

    // ---------------------------------------------
    // 5-1. 채팅방 메시지 조회 (무한 스크롤)
    // ---------------------------------------------
    group('get messages with infinite scroll', function () {
        let lastMessageId = null;
        for (let i = 0; i < 3; i++) { // 3번만 반복하여 메시지 조회
            let url = `${baseUrl}/rooms/${roomId}/messages?userId=${userId}`;
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

    // ---------------------------------------------
    // 5-2. 메시지 키워드 검색
    // ---------------------------------------------
    group('search messages', function () {
        // search 파라미터를 URL 인코딩하여 전송
        const encodedSearchKeyword = encodeURIComponent(searchKeyword);
        const url = `${baseUrl}/search?roomId=${roomId}&userId=${userId}&search=${encodedSearchKeyword}`;
        const res = http.get(url, { tags: { name: 'search_messages' } });

        check(res, { 'search messages status is 200': (r) => r.status === 200 });
        searchMessagesTrend.add(res.timings.duration);
        sleep(0.5);
    });

    // ---------------------------------------------
    // 5-3. 메시지 읽음 상태 업데이트
    // ---------------------------------------------
    group('mark messages as read', function () {
        // 먼저 메시지 목록을 가져와 마지막 메시지 ID를 확보
        const getMessagesRes = http.get(`${baseUrl}/rooms/${roomId}/messages?userId=${userId}`);
        const messages = parseSafely(getMessagesRes);

        if (messages && messages.length > 0) {
            const lastMessageId = messages[0].id; // 보통 최신 메시지를 읽음 처리
            const url = `${baseUrl}/rooms/read?roomId=${roomId}&userId=${userId}&messageId=${lastMessageId}`;

            const res = http.post(url, null, {
                headers: { 'Content-Type': 'application/json' },
                tags: { name: 'mark_messages_as_read' },
            });

            check(res, { 'mark read status is 200': (r) => r.status === 200 });
            markReadTrend.add(res.timings.duration);
        }
        sleep(0.5);
    });
}