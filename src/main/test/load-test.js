import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { randomString, randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';
import { Trend, Counter } from 'k6/metrics';

// 메트릭 정의
const chatLatency = new Trend('chat_msg_latency_ms');
const errorCount = new Counter('errors');

export const options = {
    scenarios: {
        daily_traffic: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                // 1분 동안 200명 서서히 입장 (이 구간에서는 "없는 유저" 에러 발생 가능 -> 정상!)
                { duration: '1m', target: 200 },
                // 3분 동안 200명 풀 접속 유지 (이 구간 데이터가 중요)
                { duration: '3m',  target: 200 },
                // 10초 동안 퇴장
                { duration: '10s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
    },
};

const BASE_URL = 'wss://test.ko-ri.cloud/ws';
const ROOM_ID = '9999';
const START_USER_ID = 2605; // ★ DB에 있는 유저 범위와 일치해야 함!

function makeStompFrame(command, headers, body) {
    let frame = command + '\n';
    for (let key in headers) {
        frame += key + ':' + headers[key] + '\n';
    }
    frame += '\n';
    if (body) { frame += body; }
    frame += '\u0000';
    return frame;
}

export default function () {
    const userId = START_USER_ID + (__VU - 1);
    const isTalker = (__VU % 10 === 0); // 10명 중 1명만 떠듦 (현실적)

    const params = {
        headers: { 'user-id': userId.toString(), 'Origin': 'https://test.ko-ri.cloud' },
        tags: { my_tag: 'chat_test' },
    };

    const res = ws.connect(BASE_URL, params, function (socket) {
        socket.on('open', function open() {
            // 1. 연결 시도 (User-ID 헤더 포함)
            const connectFrame = makeStompFrame('CONNECT', {
                'accept-version': '1.2,1.1,1.0',
                'heart-beat': '10000,10000',
                'user-id': userId.toString()
            });
            socket.send(connectFrame);
        });

        socket.on('message', function (message) {
            // 2. 연결 성공 확인
            if (message.includes("CONNECTED")) {

                // 3. 구독 (내 ID로 오는 메시지 듣기)
                const subscribeFrame = makeStompFrame('SUBSCRIBE', {
                    'id': 'sub-0',
                    'destination': `/topic/user/${userId}/messages` // Suffix 주의 (서버 코드와 일치시킬 것)
                });
                socket.send(subscribeFrame);

                // 4. [핵심] 말하기 시작 (Talker인 경우만)
                if (isTalker) {
                    // ★ 중요: 구독하자마자 쏘지 말고, 1초만 확실히 기다렸다가 주기적 전송 시작
                    // 이렇게 하면 서버가 내 구독 정보를 완벽히 등록한 후에 메시지가 나갑니다.
                    socket.setTimeout(function() {

                        socket.setInterval(function timeout() {
                            const sendTime = Date.now();
                            const chatContent = JSON.stringify({
                                "roomId": ROOM_ID,
                                "senderId": userId,
                                "content": `TestMsg__ts:${sendTime}`,
                            });

                            const sendFrame = makeStompFrame('SEND', {
                                'destination': '/app/chat.sendMessage',
                                'content-type': 'application/json'
                            }, chatContent);

                            socket.send(sendFrame);

                            // 5초 ~ 15초 간격으로 채팅 입력 (사람 속도)
                        }, randomIntBetween(5000, 15000));

                    }, 1000); // 1초 대기 (안전장치)
                }
            }

            // 5. 메시지 수신 (Latency 측정)
            if (message.includes("__ts:")) {
                try {
                    const match = message.match(/__ts:(\d+)/);
                    if (match) {
                        const sentTime = parseInt(match[1]);
                        const duration = Date.now() - sentTime;
                        chatLatency.add(duration);
                    }
                } catch (e) { }
            }
        });

        socket.on('error', (e) => {
            if (e.error() != "websocket: close 1000 (normal)") {
                errorCount.add(1);
            }
        });

        // 소켓 유지 시간 (사용자가 방에 머무는 시간)
        sleep(randomIntBetween(60, 180));
    });

    check(res, { 'status is 101': (r) => r && r.status === 101 });
}