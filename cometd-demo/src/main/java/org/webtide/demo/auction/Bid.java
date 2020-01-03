/*
 * Copyright (c) 2008-2020 the original author or authors.
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

public class Bid implements Cloneable, JSON.Convertible {
    private Integer itemId;
    private Double amount;
    private Bidder bidder;

    public Double getAmount() {
        return amount;
    }

    public String getFormattedAmount() {
        if (amount == null) {
            return "";
        }

        return Utils.formatCurrency(getAmount());
    }

    public void setAmount(Double aAmount) {
        amount = aAmount;
    }

    public Integer getItemId() {
        return this.itemId;
    }

    public void setItemId(Integer itemId) {
        this.itemId = itemId;
    }

    public Bidder getBidder() {
        return bidder;
    }

    public void setBidder(Bidder aBidder) {
        bidder = aBidder;
    }

    @Override
    public Bid clone() {
        try {
            return (Bid)super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void fromJSON(Map object) {
    }

    @Override
    public void toJSON(Output out) {
        out.add("itemId", itemId);
        out.add("amount", amount);
        out.add("bidder", bidder);
    }

    @Override
    public String toString() {
        return JSON.toString(this);
    }
}
