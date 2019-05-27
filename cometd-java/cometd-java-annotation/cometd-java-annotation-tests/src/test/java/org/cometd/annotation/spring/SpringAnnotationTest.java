/*
 * Copyright (c) 2008-2019 the original author or authors.
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
package org.cometd.annotation.spring;

import java.util.Arrays;

import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpringAnnotationTest {
    @Test
    public void testSpringWiringOfCometDServices() {
        ClassPathXmlApplicationContext applicationContext = new ClassPathXmlApplicationContext();
        applicationContext.setConfigLocation("classpath:applicationContext.xml");
        applicationContext.refresh();

        String serviceClass = SpringBayeuxService.class.getSimpleName();
        String beanName = Character.toLowerCase(serviceClass.charAt(0)) + serviceClass.substring(1);

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        assertTrue(Arrays.asList(beanNames).contains(beanName));

        SpringBayeuxService service = (SpringBayeuxService)applicationContext.getBean(beanName);
        assertNotNull(service);
        assertNotNull(service.dependency);
        assertNotNull(service.bayeuxServer);
        assertNotNull(service.serverSession);
        assertTrue(service.active);
        assertEquals(1, service.bayeuxServer.getChannel(SpringBayeuxService.CHANNEL).getSubscribers().size());

        applicationContext.close();

        assertFalse(service.active);
    }
}
