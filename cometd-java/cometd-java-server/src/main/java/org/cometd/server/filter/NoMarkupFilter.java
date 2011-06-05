package org.cometd.server.filter;

import java.util.regex.Pattern;

public class NoMarkupFilter extends JSONDataFilter
{
    private static Pattern __open=Pattern.compile("<");
    private static Pattern __close=Pattern.compile(">");

    @Override
    protected Object filterString(String string)
    {
        string=__open.matcher(string).replaceAll("&lt;");
        string=__close.matcher(string).replaceAll("&gt;");
        return string;
    }
}
