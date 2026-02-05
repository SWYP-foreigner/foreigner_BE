import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { randomString, randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';
import { Trend, Counter } from 'k6/metrics';

// 그래프 생성
const chatLatency = new Trend('chat_msg_latency_ms');
const errorCount = new Counter('errors'); // [추가] 에러 카운트용

export const options = {
    scenarios: {
        daily_traffic: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                // [수정] 10초는 너무 빠름. 1분 동안 천천히 200명 입장 (로그인 부하 분산)
                { duration: '1m', target: 200 },
                // [유지] 3분 동안 200명 유지하며 채팅 (실제 테스트 구간)
                { duration: '3m',  target: 200 },
                // [유지] 10초 동안 퇴장
                { duration: '10s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
    },
};
const BASE_URL = 'wss://test.ko-ri.cloud/ws';
//const BASE_URL = 'ws://localhost:8080/ws';
const ROOM_ID = '9999';
const START_USER_ID = 2605;

function makeStompFrame(command, headers, body) {
    let frame = command + '\n';
    for (let key in headers) {
        frame += key + ':' + headers[key] + '\n';
    }
    frame += '\n';
    if (body) {
        frame += body;
    }
    frame += '\u0000';
    return frame;
}

export default function () {
    const userId = START_USER_ID + (__VU - 1);

    // [변경] 발화자 비율 현실화: 3명 중 1명 -> 10명 중 1명 (10%)
    // 200명 접속 시 약 20명만 떠듦 (나머지는 눈팅)
    const isTalker = (__VU % 10 === 0);

    const params = {
        headers: { 'user-id': userId.toString(),'Origin': 'https://test.ko-ri.cloud' },
        tags: { my_tag: 'chat_test' },
    };

    const res = ws.connect(BASE_URL, params, function (socket) {
        socket.on('open', function open() {
            // ★ 여기에 user-id를 넣어줘야 인터셉터가 읽습니다.
            const connectFrame = makeStompFrame('CONNECT', {
                'accept-version': '1.2,1.1,1.0',
                'heart-beat': '10000,10000',
                'user-id': userId.toString() // <--- 추가!
            });
            socket.send(connectFrame);
        });

        socket.on('message', function (message) {
            if (message.includes("CONNECTED")) {
                const subscribeFrame = makeStompFrame('SUBSCRIBE', {
                    'id': 'sub-0',
                    'destination': `/topic/user/${userId}/${ROOM_ID}/messages`
                    //'destination': `/topic/user/${userId}/messages`
                });
                socket.send(subscribeFrame);
                // console.log(`[User ${userId}] Subscribed...`); // 로그 너무 많으면 주석

                if (isTalker) {
                    socket.setInterval(function timeout() {
                        const sendTime = Date.now();

                        const chatContent = JSON.stringify({
                            "roomId": ROOM_ID,
                            "senderId": userId,
                            // 내용 뒤에 __ts:시간 형식으로 붙임
                            "content": `TestMsg__ts:${sendTime}`,
                            //"targetLanguage": "en" // [옵션] 번역 부하 유발용 Payload
                        });

                        // [중요] 일반 전송(/sendMessage) 대신 부하 유발용(/sendMessageBad) 호출
                        const sendFrame = makeStompFrame('SEND', {
                            'destination': '/app/chat.sendMessage',
                            //'destination': '/app/chat.sendMessageBad',
                            'content-type': 'application/json'
                        }, chatContent);

                        socket.send(sendFrame);

                        // [변경] 전송 간격 현실화: 3~10초 -> 5~15초 (사람의 타이핑 속도)
                    }, randomIntBetween(5000, 15000));
                }
            }

            // 메시지 수신 시간 계산
            if (message.includes("__ts:")) {
                try {
                    const match = message.match(/__ts:(\d+)/);
                    if (match) {
                        const sentTime = parseInt(match[1]);
                        const now = Date.now();
                        const duration = now - sentTime;
                        chatLatency.add(duration);
                    }
                } catch (e) { }
            }
        });

        socket.on('error', (e) => {
            if (e.error() != "websocket: close 1000 (normal)") {
                errorCount.add(1); // 에러 발생 시 카운트
                // console.log(`Error: ${e.error()}`);
            }
        });

        sleep(randomIntBetween(60, 180));
    });

    check(res, { 'status is 101': (r) => r && r.status === 101 });
}