package com.intland.codebeamer.wiki.plugins.streamreuse;
import java.util.concurrent.ThreadPoolExecutor;import org.springframework.context.annotation.*;import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration
public class StreamReuseExecutorConfiguration {
 @Bean(name="streamReuseExecutor") public ThreadPoolTaskExecutor executor(){ThreadPoolTaskExecutor e=new ThreadPoolTaskExecutor();e.setCorePoolSize(1);e.setMaxPoolSize(2);e.setQueueCapacity(20);e.setThreadNamePrefix("stream-reuse-");e.setWaitForTasksToCompleteOnShutdown(true);e.setAwaitTerminationSeconds(60);e.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());e.initialize();return e;}
}
