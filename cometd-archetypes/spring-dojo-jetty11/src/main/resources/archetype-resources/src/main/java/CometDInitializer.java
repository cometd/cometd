package $

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.servlet.ServletContext;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.http.JSONPTransport;
import org.cometd.server.http.JSONTransport;
import org.cometd.server.websocket.javax.WebSocketTransport;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.context.ServletContextAware;

@Component
public class CometDInitializer implements ServletContextAware {
    private ServletContext servletContext;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public BayeuxServer bayeuxServer() {
        BayeuxServerImpl bean = new BayeuxServerImpl();
        bean.setTransports(new WebSocketTransport(bean), new JSONTransport(bean), new JSONPTransport(bean));
        servletContext.setAttribute(BayeuxServer.ATTRIBUTE, bean);
        bean.setOption(ServletContext.class.getName(), servletContext);
        bean.setOption("ws.cometdURLMapping", "/cometd/*");
        return bean;
    }

    @Override
    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Component
    public static class Processor implements DestructionAwareBeanPostProcessor {
        @Inject
        private BayeuxServer bayeuxServer;
        private ServerAnnotationProcessor processor;

        @PostConstruct
        private void init() {
            this.processor = new ServerAnnotationProcessor(bayeuxServer);
        }

        @PreDestroy
        private void destroy() {
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String name) throws BeansException {
            processor.processDependencies(bean);
            processor.processConfigurations(bean);
            processor.processCallbacks(bean);
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
            return bean;
        }

        @Override
        public void postProcessBeforeDestruction(Object bean, String name) throws BeansException {
            processor.deprocessCallbacks(bean);
        }

        @Override
        public boolean requiresDestruction(Object bean) {
            return true;
        }
    }
}
