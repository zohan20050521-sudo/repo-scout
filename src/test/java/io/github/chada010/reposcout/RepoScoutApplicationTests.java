package io.github.chada010.reposcout;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.deepseek.api-key=dummy-key-for-tests")
@ActiveProfiles("test")
class RepoScoutApplicationTests {

    @Test
    void contextLoads() {
    }
}
