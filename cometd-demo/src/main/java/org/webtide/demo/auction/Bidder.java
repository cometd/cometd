/*
 * Copyright (c) 2008-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.webtide.demo.auction;

import java.util.Map;

import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.ajax.JSON.Output;

public class Bidder implements Cloneable, JSON.Convertible {
    private String name;
    private String username;

    public Bidder() {
    }

    public Bidder(String name, String username) {
        setName(name);
        setUsername(username);
    }

    public String getName() {
        return name;
    }

    public void setName(String aName) {
        name = aName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String aUsername) {
        username = aUsername;
    }


    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof Bidder)) {
            return false;
        }
        return ((Bidder)obj).getName().equals(getUsername());
    }

    @Override
    public int hashCode() {
        if (getUsername() == null) {
            return 0;
        }
        return getUsername().hashCode();
    }

    @Override
    public Bidder clone() {
        try {
            return (Bidder)super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void fromJSON(Map object) {
    }

    @Override
    public void toJSON(Output out) {
        out.add("username", username);
        out.add("name", name);
    }

    @Override
    public String toString() {
        return JSON.toString(this);
    }
}
