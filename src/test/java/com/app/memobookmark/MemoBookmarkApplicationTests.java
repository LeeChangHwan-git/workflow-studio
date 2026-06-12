package com.app.memobookmark;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "app.storage.path=${java.io.tmpdir}/memo-bookmark-test")
class MemoBookmarkApplicationTests {

    @Test
    void contextLoads() {
    }
}
