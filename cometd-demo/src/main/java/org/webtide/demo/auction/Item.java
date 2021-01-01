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

public class Item implements Cloneable, JSON.Convertible {
    private static final long serialVersionUID = -3390759314943527594L;
    private Integer id;
    // private String id;
    private String itemName;
    private Category category;
    private String description;
    private Double initialPrice;

    public Item() {
    }

    ;

    public Item(Integer id, String itemName, Category category, String description, Double initialPrice) {
        setId(id);
        setItemName(itemName);
        setCategory(category);
        setDescription(description);
        setInitialPrice(initialPrice);
    }

    public String getFormattedAmount() {
        return Utils.formatCurrency(initialPrice.doubleValue());
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer aId) {
        id = aId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String aDescription) {
        description = aDescription;
    }

    public Double getInitialPrice() {
        return initialPrice;
    }

    public void setInitialPrice(Double aInitialPrice) {
        initialPrice = aInitialPrice;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String aItemName) {
        itemName = aItemName;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category aCategory) {
        category = aCategory;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof Item)) {
            return false;
        }
        return ((Item)obj).getId().equals(id);
    }

    @Override
    public int hashCode() {

        return id.hashCode();
    }


    @Override
    public Item clone() {
        try {
            return (Item)super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void fromJSON(Map object) {
    }

    @Override
    public void toJSON(Output out) {
        out.add("itemId", id);
        out.add("name", itemName);
        out.add("description", description);
        out.add("categoryId", category.getId());
    }

    @Override
    public String toString() {
        return JSON.toString(this);
    }
}
