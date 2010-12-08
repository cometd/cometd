package org.cometd.java.annotation.spring;

import java.beans.Introspector;
import java.util.Arrays;

import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpringAnnotationTest
{
    @Test
    public void testSpringWiringOfCometDServices() throws Exception
    {
        ClassPathXmlApplicationContext applicationContext = new ClassPathXmlApplicationContext();
        applicationContext.setConfigLocation("classpath:applicationContext.xml");
        applicationContext.refresh();

        String beanName = Introspector.decapitalize(SpringBayeuxService.class.getSimpleName());

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        assertTrue(Arrays.asList(beanNames).contains(beanName));

        SpringBayeuxService service = (SpringBayeuxService)applicationContext.getBean(beanName);
        assertNotNull(service);
        assertNotNull(service.dependency);
        assertNotNull(service.bayeuxServer);
        assertNotNull(service.serverSession);
        assertTrue(service.active);
        assertTrue(service.active);
        assertEquals(1, service.bayeuxServer.getChannel(SpringBayeuxService.CHANNEL).getSubscribers().size());

        applicationContext.close();

        assertFalse(service.active);
    }
}
