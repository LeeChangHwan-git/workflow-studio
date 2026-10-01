package com.app.workflowstudio.controller;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.service.PipelineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-http-test",
        "app.open-browser=false"
})
class HttpClientControllerTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired PipelineService pipelineService;

    @Test
    @SuppressWarnings("unchecked")
    void HTTP_클라이언트로_웹훅_테스트URL을_호출할_수_있다() {
        String path = "http-" + UUID.randomUUID();
        PipelineNode hook = new PipelineNode();
        hook.setId(path);
        hook.setType("WEBHOOK");
        hook.setConfig(new HashMap<>(Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST")));
        Pipeline p = new Pipeline();
        p.setName("http client test");
        p.setNodes(new ArrayList<>(List.of(hook)));
        p.setConnections(new ArrayList<>());
        pipelineService.create(p);

        Map<String, Object> req = Map.of(
                "method", "POST",
                "url", "http://localhost:" + port + "/webhook-test/" + path,
                "headers", List.of(Map.of("key", "Content-Type", "value", "application/json"),
                                   Map.of("key", "Host", "value", "ignored")),
                "body", "{\"hello\":\"world\"}");
        Map<String, Object> res = rest.postForObject("/api/http-client/send", req, Map.class);

        assertThat(res.get("status")).isEqualTo(200);
        assertThat((String) res.get("body")).contains("executionId");
        assertThat((List<String>) res.get("skippedHeaders")).containsExactly("Host");
    }

    @Test
    void 잘못된_URL은_오류로_응답한다() {
        Map<?, ?> res = rest.postForObject("/api/http-client/send",
                Map.of("method", "GET", "url", "ftp://example.com"), Map.class);
        assertThat((String) res.get("error")).contains("http://");
    }
}
