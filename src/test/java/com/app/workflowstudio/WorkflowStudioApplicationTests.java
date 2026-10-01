package com.app.workflowstudio;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-test",
        "app.open-browser=false",
        "server.port=0"
})
class WorkflowStudioApplicationTests {

    @Test
    void contextLoads() {
    }
}
