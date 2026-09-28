package com.abhinav.taskflow;

import com.abhinav.taskflow.common.config.ApiProperties;
import com.abhinav.taskflow.common.config.FrontendProperties;
import com.abhinav.taskflow.user.LockoutProperties;
import com.abhinav.taskflow.user.token.TokenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ApiProperties.class, TokenProperties.class, FrontendProperties.class, LockoutProperties.class})
public class TaskflowApplication {

	public static void main(String[] args) {
		SpringApplication.run(TaskflowApplication.class, args);
	}

}
