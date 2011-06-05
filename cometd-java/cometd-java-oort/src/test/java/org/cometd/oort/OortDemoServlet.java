package org.cometd.oort;

import java.io.IOException;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.java.annotation.ServerAnnotationProcessor;
import org.cometd.server.ext.TimesyncExtension;

public class OortDemoServlet implements Servlet
{
    ServletConfig _config;


    public String getServletInfo()
    {
        return this.getClass().toString();
    }

    public ServletConfig getServletConfig()
    {
        return _config;
    }

    public void init(ServletConfig config) throws ServletException
    {
        _config=config;

        ServletContext context=config.getServletContext();

        BayeuxServer bayeux = (BayeuxServer)context.getAttribute(BayeuxServer.ATTRIBUTE);

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        processor.process(new OortChatService(context));

        bayeux.addExtension(new TimesyncExtension());
    }

    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        throw new UnsupportedOperationException();
    }

    public void destroy()
    {
    }

}
