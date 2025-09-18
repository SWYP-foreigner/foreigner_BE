package core.global.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.beans.factory.annotation.Autowired;

@Configuration
public class RedisListenerRegistrar {

    @Autowired
    public void register(RedisMessageListenerContainer container,
                         RedisChatSubscriber subscriber,
                         org.springframework.data.redis.listener.ChannelTopic topic) {
        container.addMessageListener(new MessageListenerAdapter(subscriber), topic);
    }
}