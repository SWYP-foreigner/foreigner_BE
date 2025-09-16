package core.global.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Bean
    public RestClient restClient(
            @Value("${spring.elasticsearch.uris}") String esUrl,
            @Value("${spring.elasticsearch.username:}") String esUsername,
            @Value("${spring.elasticsearch.password:}") String esPassword
    ) {
        // Basic 인증 설정
        BasicCredentialsProvider creds = new BasicCredentialsProvider();
        if (!esUsername.isEmpty()) {
            creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(esUsername, esPassword));
        }

        return RestClient.builder(org.apache.http.HttpHost.create(esUrl))
                .setHttpClientConfigCallback((HttpAsyncClientBuilder http) ->
                        http.setDefaultCredentialsProvider(creds)
                )
                .build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient,
                                                   ObjectMapper springObjectMapper) {
        var mapper = new JacksonJsonpMapper(springObjectMapper);
        var transport = new RestClientTransport(restClient, mapper);
        return new ElasticsearchClient(transport);
    }
}