/*
 * Copyright (c) 2008-2021 the original author or authors.
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

public class Category implements Cloneable, Comparable<Category>, JSON.Convertible {
    private Integer id;
    private String categoryName;
    private String description;

    public Category() {
    }

    public Category(Integer id, String categoryName, String description) {
        this.id = id;
        this.categoryName = categoryName;
        this.description = description;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String aCategoryName) {
        categoryName = aCategoryName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String aDescription) {
        description = aDescription;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer aId) {
        id = aId;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof Category)) {
            return false;
        }
        return ((Category)obj).getId().equals(id);
    }

    @Override
    public Category clone() {
        try {
            return (Category)super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int compareTo(Category o) {
        return categoryName.compareTo(o.categoryName);
    }

    @Override
    public void fromJSON(Map<String, Object> object) {
    }

    @Override
    public void toJSON(Output out) {
        out.add("categoryId", id);
        out.add("categoryName", categoryName);
        out.add("description", description);
    }

    @Override
    public String toString() {
        return new JSON().toJSON(this);
    }
}
