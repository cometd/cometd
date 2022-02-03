/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.webtide.demo.auction.dao;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.webtide.demo.auction.Bidder;

public class BidderDao {
    static final ConcurrentMap<String, Bidder> _bidders = new ConcurrentHashMap<>();

    public Bidder getBidder(String username) {
        return _bidders.get(username);
    }

    public boolean addBidder(Bidder bidder) {
        return _bidders.putIfAbsent(bidder.getUsername(), bidder.clone()) == null;
    }

    public void saveBidder(Bidder bidder) {
        _bidders.put(bidder.getUsername(), bidder.clone());
    }
}
