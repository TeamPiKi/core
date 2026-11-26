package com.depromeet.piki.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

// test classpath 의 application.yml 이 main 을 대체함. 운영과 테스트가 같은 값을 읽도록 별도 파일에 둠
@Configuration
@PropertySource("classpath:event-publication.properties")
class EventPublicationConfig
