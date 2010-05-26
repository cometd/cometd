package org.cometd.javascript;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * The thread model object, that runs all javascript in a single thread to simulate browser's environment.
 *
 * @version $Revision$ $Date$
 */
public class JavaScriptThreadModel extends ScriptableObject implements Runnable, ThreadModel
{
    private final Thread thread = new Thread(this);
    private final BlockingQueue<FutureTask<Object>> queue = new LinkedBlockingQueue<FutureTask<Object>>();
    private final ScriptableObject rootScope;
    private volatile boolean running;
    private volatile Context context;
    private AtomicInteger scripts = new AtomicInteger();

    public JavaScriptThreadModel()
    {
        this(null);
    }

    public JavaScriptThreadModel(ScriptableObject rootScope)
    {
        this.rootScope = rootScope;
    }

    public void init() throws Exception
    {
        assert rootScope != null;
        running = true;
        thread.start();
    }

    public void destroy() throws Exception
    {
        running = false;
        thread.interrupt();
        thread.join();
    }

    public String getClassName()
    {
        return "JavaScriptThreadModel";
    }

    public void run()
    {
        context = Context.enter();
        context.setGeneratingDebug(true);
        context.setGeneratingSource(true);
        context.setOptimizationLevel(-1);
        try
        {
            while (running)
            {
                FutureTask<Object> command = queue.take();
                command.run();
            }
        }
        catch (InterruptedException x)
        {
            // We've been destroyed, just exit
        }
        finally
        {
            Context.exit();
            context = null;
        }
    }

    public Object evaluate(final URL url) throws IOException
    {
        FutureTask<Object> future = new FutureTask<Object>(new Callable<Object>()
        {
            public Object call() throws IOException
            {
                return context.evaluateReader(rootScope, new InputStreamReader(url.openStream()), url.toExternalForm(), 1, null);
            }
        });
        submit(future);
        try
        {
            return future.get();
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (ExecutionException x)
        {
            Throwable xx = x.getCause();
            if (xx instanceof IOException) throw (IOException)xx;
            if (xx instanceof Error) throw (Error)xx;
            throw (RuntimeException)xx;
        }
    }

    public Object evaluate(final String scriptName, final String script)
    {
        FutureTask<Object> future = new FutureTask<Object>(new Callable<Object>()
        {
            public Object call()
            {
                return context.evaluateString(rootScope, script, scriptName == null ? nextScriptName() : scriptName, 1, null);
            }
        });
        submit(future);
        try
        {
            return future.get();
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (ExecutionException x)
        {
            Throwable xx = x.getCause();
            if (xx instanceof Error) throw (Error)xx;
            throw (RuntimeException)xx;
        }
    }

    private String nextScriptName()
    {
        return "script_" + scripts.incrementAndGet();
    }

    public Object jsFunction_execute(Scriptable scope, Scriptable thiz, Function function)
    {
        return execute(scope, thiz, function);
    }

    public Object execute(final Scriptable scope, final Scriptable thiz, final Function function, final Object... arguments)
    {
        FutureTask<Object> future = new FutureTask<Object>(new Callable<Object>()
        {
            public Object call()
            {
                return function.call(context, scope, thiz, arguments);
            }
        });
        submit(future);
        try
        {
            return future.get();
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (ExecutionException x)
        {
            Throwable xx = x.getCause();
            if (xx instanceof Error) throw (Error)xx;
            throw (RuntimeException)xx;
        }
    }

    private void submit(FutureTask<Object> future)
    {
        if (Thread.currentThread() == thread)
            future.run();
        else
            queue.offer(future);
    }

    public void define(final Class clazz) throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        FutureTask<Object> future = new FutureTask<Object>(new Callable<Object>()
        {
            public Object call() throws InvocationTargetException, IllegalAccessException, InstantiationException
            {
                ScriptableObject.defineClass(rootScope, clazz);
                return null;
            }
        });
        submit(future);
        try
        {
            future.get();
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException x)
        {
            Throwable xx = x.getCause();
            if (xx instanceof InvocationTargetException) throw (InvocationTargetException)xx;
            if (xx instanceof IllegalAccessException) throw (IllegalAccessException)xx;
            if (xx instanceof InstantiationException) throw (InstantiationException)xx;
            if (xx instanceof Error) throw (Error)xx;
            throw (RuntimeException)xx;
        }
    }

    public Object get(final String name)
    {
        FutureTask<Object> future = new FutureTask<Object>(new Callable<Object>()
        {
            public Object call()
            {
                return rootScope.get(name, rootScope);
            }
        });
        submit(future);
        try
        {
            Object result = future.get();
            if (Context.getUndefinedValue().equals(result)) return null;
            if (Scriptable.NOT_FOUND.equals(result)) throw new IllegalArgumentException("No object named " + name + " exists in scope");
            return result;
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (ExecutionException x)
        {
            Throwable xx = x.getCause();
            if (xx instanceof Error) throw (Error)xx;
            throw (RuntimeException)xx;
        }
    }
}
