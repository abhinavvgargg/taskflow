package com.abhinav.taskflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// Configuration deliberately identical to OrganizationApiIntegrationTest, so Spring's
// context cache reuses one application context (and one Postgres container) for both.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TaskflowApplicationTests {

	@Test
	void contextLoads() {
	}

}
