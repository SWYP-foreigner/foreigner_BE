import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { randomString, randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';
import { Trend } from 'k6/metrics'; // 🔥 1. [추가] 측정 도구 가져오기

// 🔥 2. [추가] 응답 시간을 기록할 그래프 생성
const chatLatency = new Trend('chat_msg_latency_ms');

export const options = {
    scenarios: {
        daily_traffic: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 300 },
                { duration: '1m',  target: 300 },
                { duration: '10s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
    },
};

const BASE_URL = 'ws://localhost:8080/ws'; // ⚠️ 주의: 경로 확인 (ws-stomp 인지 ws 인지)
const ROOM_ID = '9999';
const START_USER_ID = 2693;

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
    const isTalker = (__VU % 3 === 0);

    const params = {
        headers: { 'user-id': userId.toString() },
        tags: { my_tag: 'chat_test' },
    };

    const res = ws.connect(BASE_URL, params, function (socket) {
        socket.on('open', function open() {
            const connectFrame = makeStompFrame('CONNECT', {
                'accept-version': '1.2,1.1,1.0',
                'heart-beat': '10000,10000'
            });
            socket.send(connectFrame);
        });

        socket.on('message', function (message) {
            if (message.includes("CONNECTED")) {
                const subscribeFrame = makeStompFrame('SUBSCRIBE', {
                    'id': 'sub-0',
                    'destination': '/topic/user/' + userId + '/messages' // ⚠️ 중요: 개인 큐 구독 확인
                });
                socket.send(subscribeFrame);

                if (isTalker) {
                    socket.setInterval(function timeout() {
                        // 🔥 3. [추가] 보낼 때 시간 찍기
                        const sendTime = Date.now();

                        const chatContent = JSON.stringify({
                            "roomId": ROOM_ID,
                            "senderId": userId,
                            // 내용 뒤에 __ts:시간 형식으로 붙임
                            "content": `TestMsg__ts:${sendTime}`,
                        });

                        const sendFrame = makeStompFrame('SEND', {
                            'destination': '/app/chat.sendMessageBad',
                            'content-type': 'application/json'
                        }, chatContent);

                        socket.send(sendFrame);
                    }, randomIntBetween(3000, 10000));
                }
            }

            // 🔥 4. [추가] 받을 때 시간 계산
            // 메시지 내용에 "__ts:"가 포함되어 있으면 우리가 보낸 메시지임
            if (message.includes("__ts:")) {
                try {
                    // 정규식으로 숫자만 쏙 빼냄
                    const match = message.match(/__ts:(\d+)/);
                    if (match) {
                        const sentTime = parseInt(match[1]);
                        const now = Date.now();
                        const duration = now - sentTime;

                        // 결과 기록 (밀리초 단위)
                        chatLatency.add(duration);
                    }
                } catch (e) {
                    // 파싱 에러는 무시
                }
            }
        });

        socket.on('error', (e) => {
            if (e.error() != "websocket: close 1000 (normal)") {
                // console.log(`Error: ${e.error()}`);
            }
        });

        sleep(randomIntBetween(60, 180));
    });

    check(res, { 'status is 101': (r) => r && r.status === 101 });
}