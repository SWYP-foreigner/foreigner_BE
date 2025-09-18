package core.global.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Bean
    public RestClient restClient(@Value("${app.search.es-url}") String esUrl) {
        return RestClient.builder(HttpHost.create(esUrl)).build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient,
                                                   ObjectMapper springObjectMapper) {
        var mapper = new JacksonJsonpMapper(springObjectMapper);
        var transport = new RestClientTransport(restClient, mapper);
        return new ElasticsearchClient(transport);
    }
}