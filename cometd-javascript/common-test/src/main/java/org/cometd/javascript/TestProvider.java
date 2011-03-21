package org.cometd.javascript;

public interface TestProvider
{
    public void provideCometD(ThreadModel threadModel, String contextURL) throws Exception;

    public void provideMessageAcknowledgeExtension(ThreadModel threadModel, String contextURL) throws Exception;

    public void provideReloadExtension(ThreadModel threadModel, String contextURL) throws Exception;

    public void provideTimestampExtension(ThreadModel threadModel, String contextURL) throws Exception;

    public void provideTimesyncExtension(ThreadModel threadModel, String contextURL) throws Exception;
}
