package org.cometd.java.annotation;

import javax.servlet.ServletException;

import org.cometd.server.CometdServlet;
import org.eclipse.jetty.util.Loader;

public class AnnotatedCometdServlet extends CometdServlet
{
    private static final long serialVersionUID = 2821068017364051087L;

    @Override
    public void init() throws ServletException
    {
        super.init();
        
        String services = getInitParameter("services");
        if (services!=null && services.length()>0);
        {
            ServerAnnotationProcessor processor = new ServerAnnotationProcessor(getBayeux());
            
            for (String service : services.split(" +, +"))
            {
                getBayeux().getLogger().info("Annotated Service: "+service);
                try
                {
                    Object pojo = Loader.loadClass(this.getClass(),service).newInstance();
                    processor.process(pojo);
                }
                catch(Exception e)
                {
                    getServletContext().log("Failed to create cometd service "+service,e);
                    throw new ServletException(e);
                }
            }
        }
    }
    
}
