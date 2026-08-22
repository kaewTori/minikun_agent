package com.minikun.personalcare;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({PersonalCareStatusService.class, PersonalCareStatusController.class})
public class PersonalCareConfiguration { }
