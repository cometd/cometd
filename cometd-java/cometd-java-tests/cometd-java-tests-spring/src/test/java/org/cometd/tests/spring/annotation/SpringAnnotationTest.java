/*
 * Copyright (c) 2008-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.tests.spring.annotation;

import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class SpringAnnotationTest {
    @Test
    public void testSpringWiringOfCometDServices() {
        ClassPathXmlApplicationContext applicationContext = new ClassPathXmlApplicationContext();
        applicationContext.setConfigLocation("classpath:applicationContext-annotation.xml");
        applicationContext.refresh();

        String serviceClass = SpringBayeuxService.class.getSimpleName();
        String beanName = Character.toLowerCase(serviceClass.charAt(0)) + serviceClass.substring(1);

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        Assertions.assertTrue(Arrays.asList(beanNames).contains(beanName));

        SpringBayeuxService service = (SpringBayeuxService)applicationContext.getBean(beanName);
        Assertions.assertNotNull(service);
        Assertions.assertNotNull(service.dependency);
        Assertions.assertNotNull(service.bayeuxServer);
        Assertions.assertNotNull(service.serverSession);
        Assertions.assertTrue(service.active);
        Assertions.assertEquals(1, service.bayeuxServer.getChannel(SpringBayeuxService.CHANNEL).getSubscribers().size());

        applicationContext.close();

        Assertions.assertFalse(service.active);
    }
}
