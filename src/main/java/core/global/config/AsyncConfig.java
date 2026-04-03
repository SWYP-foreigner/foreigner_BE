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
     * 비동기 예외 처리기
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
     *  이미지 검열 전용 실행기 (moderationExecutor)
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
    @Bean(name = "chatAsyncExecutor")
    public Executor chatAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 2코어 환경 최적화 값
        executor.setCorePoolSize(10);  // 평소 유지할 일꾼
        executor.setMaxPoolSize(30);   // 정말 바쁠 때 늘어날 최대 일꾼
        executor.setQueueCapacity(500); // 일꾼이 다 차면 대기할 장소 (무제한 방지!)
        executor.setThreadNamePrefix("ChatAsync-");

        // 중요: 큐까지 꽉 찼을 때 어떻게 할 것인가?
        // CallerRunsPolicy: "나 바쁘니까 네(메인스레드)가 직접 해!" -> 시스템 전체 속도를 늦춰서 폭주 방지
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
