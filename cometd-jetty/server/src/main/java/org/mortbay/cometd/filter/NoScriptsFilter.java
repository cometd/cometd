// ========================================================================
// Copyright 2007 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.mortbay.cometd.filter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mortbay.util.StringUtil;


public class NoScriptsFilter extends JSONDataFilter
{
    private static Pattern __script=Pattern.compile("<\\s*[Ss][Cc][Rr][Ii][Pp][Tt]");

    @Override
	protected Object filterString(String string)
    {
        Matcher m = __script.matcher(string);
        if (m.matches())
            string = StringUtil.replace(string,"script","span");
        return string;
    }
}
