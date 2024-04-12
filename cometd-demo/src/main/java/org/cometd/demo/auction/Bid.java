/*
 * Copyright (c) 2008 the original author or authors.
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

package org.cometd.demo.auction;

import java.util.Map;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.ajax.JSON.Output;

public class Bid implements Comparable<Bid>, JSON.Convertible {
    private int id;
    private double amount;
    private String bidder;

    public Bid() {
    }

    public Bid(int id, double amount, String bidder) {
        this.id = id;
        this.amount = amount;
        this.bidder = bidder;
    }

    public int id() {
        return id;
    }

    public double amount() {
        return amount;
    }

    public String bidder() {
        return bidder;
    }

    @Override
    public int compareTo(Bid that) {
        int result = Double.compare(amount(), that.amount());
        if (result != 0) {
            return result;
        }
        return bidder().compareTo(that.bidder());
    }

    @Override
    public void fromJSON(Map<String, Object> object) {
        this.id = ((Number)object.get("id")).intValue();
        this.amount = ((Number)object.get("amount")).doubleValue();
        this.bidder = (String)object.get("bidder");
    }

    @Override
    public void toJSON(Output out) {
        out.addClass(Bid.class);
        out.add("id", id());
        out.add("amount", amount());
        out.add("bidder", bidder());
    }

    @Override
    public String toString() {
        return "%s[id=%d,amount=%.2f,bidder=%s]".formatted(
                getClass().getSimpleName(),
                id(),
                amount(),
                bidder()
        );
    }
}
