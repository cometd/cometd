package org.cometd.java.annotation;

import javax.servlet.ServletException;

import org.cometd.server.CometdServlet;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A specialized version of {@link CometdServlet} that can be configured with the init-parameter
 * <b>services</b> to be a comma separated list of class names of annotated services, that will
 * be processed by {@link ServerAnnotationProcessor} upon initialization.</p>
 *
 * A configuration example:
 * <pre>
 * &lt;web-app xmlns="http://java.sun.com/xml/ns/javaee" ...&gt;
 *
 *  &lt;servlet&gt;
 *    &lt;servlet-name&gt;cometd&lt;/servlet-name&gt;
 *    &lt;servlet-class&gt;org.cometd.java.annotation.AnnotationCometdServlet&lt;/servlet-class&gt;
 *    &lt;init-param&gt;
 *      &lt;param-name&gt;services&lt;/param-name&gt;
 *      &lt;param-value&gt;org.cometd.examples.FooService, org.cometd.examples.BarService&lt;/param-value&gt;
 *    &lt;/init-param&gt;
 *  &lt;/servlet&gt;
 * </pre>
 */
public class AnnotationCometdServlet extends CometdServlet
{
    private static final long serialVersionUID = 2821068017364051087L;

    @Override
    public void init() throws ServletException
    {
        super.init();

        String services = getInitParameter("services");
        if (services != null && services.length() > 0)
        {
            ServerAnnotationProcessor processor = new ServerAnnotationProcessor(getBayeux());

            for (String service : services.split(","))
            {
                processService(processor, service.trim());
            }
        }
    }

    protected void processService(ServerAnnotationProcessor processor, String serviceClassName) throws ServletException
    {
        Logger logger = getBayeux().getLogger();
        try
        {
            Object service = Loader.loadClass(getClass(), serviceClassName).newInstance();
            processor.process(service);
            logger.info("Processed annotated service " + serviceClassName);
        }
        catch (Exception x)
        {
            logger.warn("Failed to create annotated service " + serviceClassName, x);
            throw new ServletException(x);
        }
    }
}
