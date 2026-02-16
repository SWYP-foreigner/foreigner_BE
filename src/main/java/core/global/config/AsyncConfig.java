package core.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j // 로깅을 위해 추가
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 1. 메인 비동기 실행기 (taskExecutor)
     * - 에러 메시지에서 찾던 그 빈입니다.
     * - @Async만 붙였을 때 기본으로 사용됩니다.
     * - 채팅 번역, 알림 전송 등 일반적인 비동기 작업 처리
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(500);

        executor.setThreadNamePrefix("Async-Executor-");

        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }

    /**
     * 2. AsyncConfigurer 인터페이스 구현
     * - @Async 어노테이션이 사용할 기본 Executor를 지정합니다.
     * - 위에서 만든 taskExecutor()를 리턴합니다.
     */
    @Override
    public Executor getAsyncExecutor() {
        return taskExecutor();
    }

    /**
     * 3. 비동기 예외 처리기
     * - 비동기 메서드(void 반환)에서 에러가 터지면 메인 스레드는 모릅니다.
     * - 여기서 로그를 찍어줘야 에러 추적이 가능합니다.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            log.error("💥 Async Method Error - Method: {}, Message: {}", method.getName(), ex.getMessage(), ex);
        };
    }

    /**
     * 4. 이미지 검열 전용 실행기 (moderationExecutor)
     * - 기존에 작성하신 코드 유지
     * - @Async("moderationExecutor") 라고 명시했을 때만 사용됨
     */
    @Bean(name = "moderationExecutor")
    public Executor moderationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("Async-Moderation-");
        executor.initialize();
        return executor;
    }
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10); // 동시에 예약 걸릴 수 있는 작업 수
        scheduler.setThreadNamePrefix("Scheduled-Task-"); // 로그에 찍힐 이름
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "imageExecutor")
    public Executor imageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(50);
        executor.setQueueCapacity(100);
        executor.setMaxPoolSize(150);
        executor.setKeepAliveSeconds(30);
        executor.setThreadNamePrefix("Async-Image-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        return executor;
    }

    @Bean(name = "websocketExecutor")
    public Executor websocketExecutor() {
         ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
         executor.setCorePoolSize(50);
         executor.initialize();
         return executor;
    }
}
