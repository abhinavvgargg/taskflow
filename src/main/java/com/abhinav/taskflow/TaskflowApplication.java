package com.abhinav.taskflow;

import com.abhinav.taskflow.common.config.ApiProperties;
import com.abhinav.taskflow.common.config.FrontendProperties;
import com.abhinav.taskflow.common.config.TokenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ApiProperties.class, TokenProperties.class, FrontendProperties.class})
public class TaskflowApplication {

	public static void main(String[] args) {
		SpringApplication.run(TaskflowApplication.class, args);
	}

}
