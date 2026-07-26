package io.github.chada010.reposcout;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.deepseek.api-key=dummy-key-for-tests")
class RepoScoutApplicationTests {

    @Test
    void contextLoads() {
    }
}
