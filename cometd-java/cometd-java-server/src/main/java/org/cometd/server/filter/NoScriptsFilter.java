package org.cometd.server.filter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.StringUtil;

public class NoScriptsFilter extends JSONDataFilter
{
    private static Pattern __script=Pattern.compile("<\\s*[Ss][Cc][Rr][Ii][Pp][Tt]");

    @Override
    protected Object filterString(String string)
    {
        Matcher m=__script.matcher(string);
        if (m.matches())
            string=StringUtil.replace(string,"script","span");
        return string;
    }
}
